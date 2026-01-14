package com.spaces.daemon

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.concurrent.thread
import kotlinx.serialization.Serializable

fun main() {
    val config = Config.fromEnv()
    val db = SpacesDatabase(config.dbPath)
    db.initialize()
    val replicationEngine = ReplicationEngine()
    val replicationService = ReplicationService(db, replicationEngine)
    val mountManager = MountManager(config)
    val service = SpacesService(config, db, mountManager, replicationService)
    val nfsServer = NfsServer(config, db, replicationService)
    nfsServer.start()

    thread(start = true, name = "spaces-remount") { runCatching { service.remountAll() } }

    Runtime.getRuntime()
            .addShutdownHook(
                    Thread {
                        runCatching { service.unmountAll() }
                        runCatching { nfsServer.stop() }
                    }
            )

    embeddedServer(Netty, host = config.apiHost, port = config.apiPort) {
                install(ContentNegotiation) { json() }
                install(StatusPages) {
                    exception<IllegalArgumentException> { call, cause ->
                        call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(cause.message ?: "Invalid request")
                        )
                    }
                    exception<NoSuchElementException> { call, cause ->
                        call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse(cause.message ?: "Not found")
                        )
                    }
                    exception<Exception> { call, cause ->
                        call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(cause.message ?: "Internal error")
                        )
                    }
                }
                routing {
                    get("/openapi.json") {
                        val resource = this::class.java.classLoader.getResource("openapi.json")
                        if (resource == null) {
                            call.respond(HttpStatusCode.NotFound)
                            return@get
                        }
                        val text =
                                resource.openStream().use { stream ->
                                    stream.bufferedReader().readText()
                                }
                        call.respondText(
                                text,
                                contentType = io.ktor.http.ContentType.Application.Json
                        )
                    }
                    get("/system/health") { call.respond(HealthResponse("ok")) }
                    get("/system/config") { call.respond(config) }
                    get("/system/status") { call.respond(service.status()) }
                    post("/system/remount") {
                        service.remountAll()
                        call.respond(HttpStatusCode.OK)
                    }

                    get("/entrypoints") { call.respond(service.listEntrypoints()) }
                    post("/entrypoints") {
                        val payload = call.receive<CreateEntrypointRequest>()
                        val entrypoint = service.createEntrypoint(payload.name, payload.path)
                        call.respond(HttpStatusCode.Created, entrypoint)
                    }
                    get("/entrypoints/{id}") {
                        val id =
                                call.parameters["id"]
                                        ?: throw NoSuchElementException("Entrypoint not found")
                        val entrypoint =
                                service.getEntrypoint(id)
                                        ?: throw NoSuchElementException("Entrypoint not found")
                        call.respond(entrypoint)
                    }
                    delete("/entrypoints/{id}") {
                        val id =
                                call.parameters["id"]
                                        ?: throw NoSuchElementException("Entrypoint not found")
                        service.deleteEntrypoint(id)
                        call.respond(HttpStatusCode.NoContent)
                    }

                    get("/layers") {
                        val entrypointId = call.request.queryParameters["entrypointId"]
                        call.respond(service.listLayers(entrypointId))
                    }
                    post("/layers") {
                        val payload = call.receive<CreateLayerRequest>()
                        val layer =
                                service.createLayer(
                                        payload.name,
                                        payload.entrypointId,
                                        payload.parentId,
                                        payload.mountPath
                                )
                        call.respond(HttpStatusCode.Created, layer)
                    }
                    get("/layers/{id}") {
                        val id =
                                call.parameters["id"]
                                        ?: throw NoSuchElementException("Layer not found")
                        val layer =
                                service.getLayer(id)
                                        ?: throw NoSuchElementException("Layer not found")
                        call.respond(layer)
                    }
                    delete("/layers/{id}") {
                        val id =
                                call.parameters["id"]
                                        ?: throw NoSuchElementException("Layer not found")
                        service.deleteLayer(id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/layers/{id}/mount") {
                        val id =
                                call.parameters["id"]
                                        ?: throw NoSuchElementException("Layer not found")
                        val layer =
                                db.getLayer(id) ?: throw NoSuchElementException("Layer not found")
                        service.mountLayer(layer)
                        call.respond(HttpStatusCode.OK)
                    }
                    post("/layers/{id}/unmount") {
                        val id =
                                call.parameters["id"]
                                        ?: throw NoSuchElementException("Layer not found")
                        val layer =
                                db.getLayer(id) ?: throw NoSuchElementException("Layer not found")
                        service.unmountLayer(layer)
                        call.respond(HttpStatusCode.OK)
                    }
                    get("/layers/{id}/diff") {
                        val id =
                                call.parameters["id"]
                                        ?: throw NoSuchElementException("Layer not found")
                        call.respond(service.layerDiff(id))
                    }

                    get("/user-mounts") {
                        val entrypointId = call.request.queryParameters["entrypointId"]
                        call.respond(service.listUserMounts(entrypointId))
                    }
                    post("/user-mounts") {
                        val payload = call.receive<CreateUserMountRequest>()
                        val mount =
                                service.createUserMount(
                                        payload.name,
                                        payload.entrypointId,
                                        payload.mountPath,
                                        payload.attachedLayerId
                                )
                        call.respond(HttpStatusCode.Created, mount)
                    }
                    get("/user-mounts/{id}") {
                        val id =
                                call.parameters["id"]
                                        ?: throw NoSuchElementException("User mount not found")
                        val mount =
                                service.getUserMount(id)
                                        ?: throw NoSuchElementException("User mount not found")
                        call.respond(mount)
                    }
                    delete("/user-mounts/{id}") {
                        val id =
                                call.parameters["id"]
                                        ?: throw NoSuchElementException("User mount not found")
                        service.deleteUserMount(id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/user-mounts/{id}/mount") {
                        val id =
                                call.parameters["id"]
                                        ?: throw NoSuchElementException("User mount not found")
                        val mount =
                                db.getUserMount(id)
                                        ?: throw NoSuchElementException("User mount not found")
                        service.mountUserMount(mount)
                        call.respond(HttpStatusCode.OK)
                    }
                    post("/user-mounts/{id}/unmount") {
                        val id =
                                call.parameters["id"]
                                        ?: throw NoSuchElementException("User mount not found")
                        val mount =
                                db.getUserMount(id)
                                        ?: throw NoSuchElementException("User mount not found")
                        service.unmountUserMount(mount)
                        call.respond(HttpStatusCode.OK)
                    }
                    post("/user-mounts/attach") {
                        val payload = call.receive<AttachLayerRequest>()
                        service.attachLayer(payload.userMountId, payload.layerId)
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }
            .start(wait = true)
}

@Serializable data class HealthResponse(val status: String)

@Serializable
data class Config(
        val dataDir: String,
        val dbPath: String,
        val apiHost: String,
        val apiPort: Int,
        val nfsHost: String,
        val nfsPort: Int,
) {
    companion object {
        fun fromEnv(): Config {
            val dataDir = env("SPACES_DATA_DIR", "/var/lib/spaces")
            val dbPath = env("SPACES_DB_PATH", "$dataDir/spaces.db")
            val apiHost = env("SPACES_API_HOST", "127.0.0.1")
            val apiPort = env("SPACES_API_PORT", "3100").toInt()
            val nfsHost = env("SPACES_NFS_HOST", "127.0.0.1")
            val nfsPort = env("SPACES_NFS_PORT", "11111").toInt()
            return Config(
                    dataDir = dataDir,
                    dbPath = dbPath,
                    apiHost = apiHost,
                    apiPort = apiPort,
                    nfsHost = nfsHost,
                    nfsPort = nfsPort,
            )
        }

        private fun env(key: String, defaultValue: String): String {
            val value = System.getenv(key)
            if (value.isNullOrBlank()) {
                return defaultValue
            }
            return value.trim()
        }

        private fun normalizeExportRoot(value: String): String {
            val trimmed = value.trim()
            val withSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return withSlash.trimEnd('/')
        }
    }
}
