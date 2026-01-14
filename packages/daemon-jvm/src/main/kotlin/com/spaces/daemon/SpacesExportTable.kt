package com.spaces.daemon

import java.net.InetAddress
import java.util.stream.Stream
import org.dcache.nfs.ExportTable
import org.dcache.nfs.FsExport
import org.slf4j.LoggerFactory

class SpacesExportTable() : ExportTable {
    private val logger = LoggerFactory.getLogger(SpacesExportTable::class.java)
    private val rootExport: FsExport =
            FsExport.FsExportBuilder()
                    .forClient("127.0.0.1/32")
                    .trusted()
                    .rw()
                    .withAllRoot()
                    .build("/")

    override fun exports(): Stream<FsExport> = Stream.of(rootExport)
    override fun exports(address: InetAddress): Stream<FsExport> = exports()

    override fun getExport(path: String, address: InetAddress): FsExport? = rootExport
    override fun getExport(index: Int, address: InetAddress): FsExport? = rootExport
}
