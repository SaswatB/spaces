package com.spaces.daemon

import java.net.InetAddress
import java.util.stream.Stream
import org.dcache.nfs.ExportTable
import org.dcache.nfs.FsExport

class SpacesExportTable() : ExportTable {
    private val rootExport: FsExport =
            FsExport.FsExportBuilder()
                    .forClient("127.0.0.1/32")
                    .trusted()
                    .rw()
                    .withAllRoot()
                    .build("/")

    override fun exports(): Stream<FsExport> = Stream.of(rootExport)
    override fun exports(address: InetAddress): Stream<FsExport> =
            if (address.isLoopbackAddress) Stream.of(rootExport) else Stream.empty()

    override fun getExport(path: String, address: InetAddress): FsExport? =
            if (address.isLoopbackAddress) rootExport else null

    override fun getExport(index: Int, address: InetAddress): FsExport? =
            if (address.isLoopbackAddress) rootExport else null
}
