package com.spaces.daemon

import org.dcache.nfs.v4.NFSServerV41
import org.dcache.nfs.v4.nlm.SimpleLm
import org.dcache.oncrpc4j.rpc.OncRpcProgram
import org.dcache.oncrpc4j.rpc.OncRpcSvc
import org.dcache.oncrpc4j.rpc.OncRpcSvcBuilder

class NfsServer(
        private val config: Config,
        private val db: SpacesDatabase,
        private val replication: ReplicationService
) {
    private var rpcService: OncRpcSvc? = null

    fun start() {
        val nfs4 =
                NFSServerV41.Builder()
                        .withExportTable(SpacesExportTable())
                        .withVfs(SpacesVfs(db, replication))
                        .withOperationExecutor(SpacesOperationExecutor())
                        .withLockManager(SimpleLm())
                        .build()

        val rpc =
                OncRpcSvcBuilder()
                        .withPort(config.nfsPort)
                        .withBindAddress(config.nfsHost)
                        .withTCP()
                        .withUDP()
                        .withAutoPublish()
                        .withWorkerThreadIoStrategy()
                        .build()

        rpc.register(OncRpcProgram(100003, 4), nfs4)
        rpc.start()

        rpcService = rpc
    }

    fun stop() {
        rpcService?.stop()
    }
}
