package com.spaces.daemon

import java.nio.file.AccessDeniedException
import org.dcache.nfs.nfsstat
import org.dcache.nfs.status.AccessException
import org.dcache.nfs.status.InvalException
import org.dcache.nfs.status.IsDirException
import org.dcache.nfs.status.NfsIoException
import org.dcache.nfs.v4.AbstractNFSv4Operation
import org.dcache.nfs.v4.CompoundContext
import org.dcache.nfs.v4.MDSOperationExecutor
import org.dcache.nfs.v4.OperationCLOSE
import org.dcache.nfs.v4.OperationOPEN
import org.dcache.nfs.v4.OperationOPEN_CONFIRM
import org.dcache.nfs.v4.OperationOPEN_DOWNGRADE
import org.dcache.nfs.v4.Stateids
import org.dcache.nfs.v4.xdr.WRITE4resok
import org.dcache.nfs.v4.xdr.count4
import org.dcache.nfs.v4.xdr.nfs4_prot
import org.dcache.nfs.v4.xdr.nfs_argop4
import org.dcache.nfs.v4.xdr.nfs_opnum4
import org.dcache.nfs.v4.xdr.nfs_resop4
import org.dcache.nfs.vfs.PseudoFs
import org.dcache.nfs.vfs.Stat
import org.dcache.nfs.vfs.VirtualFileSystem

private val pseudoFsInnerField =
        try {
            PseudoFs::class.java.getDeclaredField("_inner").apply { isAccessible = true }
        } catch (_: Exception) {
            null
        }

fun getSpacesVfs(context: CompoundContext): SpacesVfs? {
    val fs = context.fs
    if (fs is SpacesVfs) {
        return fs
    }
    if (fs is PseudoFs) {
        return try {
            pseudoFsInnerField?.get(fs) as? SpacesVfs
        } catch (_: Exception) {
            null
        }
    }
    return null
}

class SpacesOperationExecutor : MDSOperationExecutor() {
    override fun getOperation(op: nfs_argop4): AbstractNFSv4Operation {
        return when (op.argop) {
            nfs_opnum4.OP_OPEN -> SpacesOperationOPEN(op)
            nfs_opnum4.OP_OPEN_CONFIRM -> SpacesOperationOPEN_CONFIRM(op)
            nfs_opnum4.OP_CLOSE -> SpacesOperationCLOSE(op)
            nfs_opnum4.OP_OPEN_DOWNGRADE -> SpacesOperationOPEN_DOWNGRADE(op)
            nfs_opnum4.OP_WRITE -> SpacesOperationWRITE(op)
            else -> super.getOperation(op)
        }
    }
}

private class SpacesOperationOPEN(args: nfs_argop4) : OperationOPEN(args) {
    override fun process(context: CompoundContext, result: nfs_resop4) {
        super.process(context, result)
        val fs = getSpacesVfs(context)
        if (fs != null) {
            val stateid = context.currentStateid()
            try {
                fs.registerOpenState(
                        context.currentInode(),
                        stateid,
                        _args.opopen.share_access.value
                )
            } catch (_: AccessDeniedException) {
                throw AccessException()
            }
            val client =
                    if (context.minorversion > 0) {
                        context.session.client
                    } else {
                        context.stateHandler.getClientIdByStateId(stateid)
                    }
            val state = client.state(stateid)
            state.addDisposeListener { fs.closeOpenState(stateid) }
        }
    }
}

private class SpacesOperationCLOSE(args: nfs_argop4) : OperationCLOSE(args) {
    override fun process(context: CompoundContext, result: nfs_resop4) {
        val stateid = Stateids.getCurrentStateidIfNeeded(context, _args.opclose.open_stateid)
        super.process(context, result)
        val fs = getSpacesVfs(context)
        if (fs != null) {
            fs.closeOpenState(stateid)
        }
    }
}

private class SpacesOperationOPEN_DOWNGRADE(args: nfs_argop4) : OperationOPEN_DOWNGRADE(args) {
    override fun process(context: CompoundContext, result: nfs_resop4) {
        val stateid =
                Stateids.getCurrentStateidIfNeeded(context, _args.opopen_downgrade.open_stateid)
        super.process(context, result)
        val fs = getSpacesVfs(context)
        if (fs != null) {
            fs.downgradeOpenState(stateid, _args.opopen_downgrade.share_access.value)
        }
    }
}

private class SpacesOperationOPEN_CONFIRM(args: nfs_argop4) : OperationOPEN_CONFIRM(args) {
    override fun process(context: CompoundContext, result: nfs_resop4) {
        super.process(context, result)
        val fs = getSpacesVfs(context)
        if (fs != null) {
            val confirmedStateid = result.opopen_confirm.resok4.open_stateid
            val client = context.stateHandler.getClientIdByStateId(confirmedStateid)
            val shareAccess =
                    context.stateHandler
                            .getFileTracker()
                            .getShareAccess(client, context.currentInode(), confirmedStateid)
            try {
                fs.registerOpenState(context.currentInode(), confirmedStateid, shareAccess)
            } catch (_: AccessDeniedException) {
                throw AccessException()
            }
            val state = client.state(confirmedStateid)
            state.addDisposeListener { fs.closeOpenState(confirmedStateid) }
        }
    }
}

private class SpacesOperationWRITE(args: nfs_argop4) :
        AbstractNFSv4Operation(args, nfs_opnum4.OP_WRITE) {
    override fun process(context: CompoundContext, result: nfs_resop4) {
        val res = result.opwrite

        _args.opwrite.offset.checkOverflow(
                _args.opwrite.data.remaining(),
                "offset + length overflow"
        )

        val stat = context.fs.getattr(context.currentInode())
        if (stat.type() == Stat.Type.DIRECTORY) {
            throw IsDirException()
        }

        if (stat.type() == Stat.Type.SYMLINK) {
            throw InvalException("path is a symlink")
        }

        var stateid = Stateids.getCurrentStateidIfNeeded(context, _args.opwrite.stateid)
        if (Stateids.isStateLess(stateid)) {
            try {
                val current = context.currentStateid()
                if (!Stateids.isStateLess(current)) {
                    stateid = current
                }
            } catch (_: Exception) {
            }
        }
        if (context.minorversion == 0) {
            context.stateHandler.updateClientLeaseTime(stateid)
        }

        val fs = context.fs
        val spacesFs = getSpacesVfs(context)
        if (spacesFs != null && !Stateids.isStateLess(stateid)) {
            val client =
                    if (context.minorversion > 0) {
                        context.session.client
                    } else {
                        context.stateHandler.getClientIdByStateId(stateid)
                    }
            val shareAccess =
                    context.stateHandler
                            .getFileTracker()
                            .getShareAccess(client, context.currentInode(), stateid)
            if (shareAccess and nfs4_prot.OPEN4_SHARE_ACCESS_WRITE == 0) {
                throw AccessException()
            }
            try {
                spacesFs.registerOpenState(context.currentInode(), stateid, shareAccess)
            } catch (_: AccessDeniedException) {
                throw AccessException()
            }
        }

        val offset = _args.opwrite.offset.value
        val count = _args.opwrite.data.remaining()
        val data = ByteArray(count)
        _args.opwrite.data.get(data)

        val writeResult =
                if (spacesFs != null) {
                    spacesFs.writeWithState(
                            context.currentInode(),
                            stateid,
                            data,
                            offset,
                            count,
                            VirtualFileSystem.StabilityLevel.fromStableHow(_args.opwrite.stable)
                    )
                } else {
                    fs.write(
                            context.currentInode(),
                            data,
                            offset,
                            count,
                            VirtualFileSystem.StabilityLevel.fromStableHow(_args.opwrite.stable)
                    )
                }

        if (writeResult.bytesWritten < 0) {
            throw NfsIoException("IO not allowed")
        }

        res.status = nfsstat.NFS_OK
        res.resok4 = WRITE4resok()
        res.resok4.count = count4(writeResult.bytesWritten)
        res.resok4.committed = writeResult.stabilityLevel.toStableHow()
        res.resok4.writeverf = context.rebootVerifier
    }
}
