package com.spaces.daemon

import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import kotlin.io.path.name
import kotlin.io.path.writeBytes

const val OPAQUE_MARKER = ".wh..wh..opq"

fun markerName(name: String): String = ".wh.$name"

fun isWhiteoutMarker(name: String): String? {
    return if (name.startsWith(".wh.") && name != OPAQUE_MARKER) {
        name.removePrefix(".wh.")
    } else {
        null
    }
}

data class OverlayView(val entrypoint: Path, val layers: List<Path>)

data class ResolvedPath(val source: Path)

class OverlayEngine {
    fun resolvePath(view: OverlayView, relative: String): ResolvedPath? {
        return resolvePath(view, Paths.get(relative))
    }

    fun resolveLowerPath(view: OverlayView, relative: String): ResolvedPath? {
        return resolveLowerPath(view, Paths.get(relative))
    }

    fun exists(view: OverlayView, relative: String): Boolean = resolvePath(view, relative) != null

    fun existsInLower(view: OverlayView, relative: String): Boolean =
            resolveLowerPath(view, relative) != null

    fun resolvePath(view: OverlayView, relative: Path): ResolvedPath? {
        return resolveAgainstLayers(view.layers, view.entrypoint, relative)
    }

    fun resolveLowerPath(view: OverlayView, relative: Path): ResolvedPath? {
        return resolveAgainstLayers(view.layers.drop(1), view.entrypoint, relative)
    }

    private fun resolveAgainstLayers(
            layers: List<Path>,
            entrypoint: Path,
            relative: Path
    ): ResolvedPath? {
        val rel = relative
        for (layer in layers) {
            val candidate = layer.resolve(rel)
            if (existsNoFollow(candidate)) {
                return ResolvedPath(candidate)
            }
            if (isWhiteoutedInLayer(layer, rel) || isOpaqueParentInLayer(layer, rel)) {
                return null
            }
        }
        val lower = entrypoint.resolve(rel)
        return if (existsNoFollow(lower)) ResolvedPath(lower) else null
    }

    fun listDir(view: OverlayView, relative: String): List<String> {
        val rel = Paths.get(relative)
        val entries = sortedSetOf<String>()
        val hidden = mutableSetOf<String>()

        for (layer in view.layers) {
            val dir = layer.resolve(rel)
            if (!existsNoFollow(dir)) continue
            var opaqueHere = false
            try {
                Files.newDirectoryStream(dir).use { stream ->
                    for (entry in stream) {
                        val name = entry.fileName.toString()
                        if (name == OPAQUE_MARKER) {
                            opaqueHere = true
                            continue
                        }
                        val whiteout = isWhiteoutMarker(name)
                        if (whiteout != null) {
                            hidden.add(whiteout)
                            continue
                        }
                        if (!hidden.contains(name)) {
                            entries.add(name)
                        }
                    }
                }
            } catch (_: IOException) {
                continue
            }
            if (opaqueHere) {
                return entries.toList()
            }
        }

        val lowerDir = view.entrypoint.resolve(rel)
        if (existsNoFollow(lowerDir)) {
            try {
                Files.newDirectoryStream(lowerDir).use { stream ->
                    for (entry in stream) {
                        val name = entry.fileName.toString()
                        if (!hidden.contains(name)) {
                            entries.add(name)
                        }
                    }
                }
            } catch (_: IOException) {
                // ignore
            }
        }

        return entries.toList()
    }

    fun copyUpIfNeeded(view: OverlayView, relative: String) {
        val rel = Paths.get(relative)
        val topUpper = view.layers.firstOrNull() ?: return
        val upperPath = topUpper.resolve(rel)
        if (existsNoFollow(upperPath)) return

        val lowerPath = resolveLowerPath(view, rel)?.source ?: return

        if (Files.isDirectory(lowerPath, LinkOption.NOFOLLOW_LINKS)) {
            ensureParentDirs(view, rel)
            Files.createDirectories(upperPath)
            applyMetadata(upperPath, lowerPath)
        } else if (Files.isSymbolicLink(lowerPath)) {
            ensureParentDirs(view, rel)
            Files.createSymbolicLink(upperPath, Files.readSymbolicLink(lowerPath))
        } else {
            ensureParentDirs(view, rel)
            Files.copy(lowerPath, upperPath)
            applyMetadata(upperPath, lowerPath)
        }
    }

    fun ensureParentDirs(view: OverlayView, relative: Path) {
        val topUpper = view.layers.firstOrNull() ?: return
        val parent = relative.parent ?: return
        if (parent.nameCount == 0) return

        var current = Paths.get("")
        parent.forEach { component ->
            current = current.resolve(component)
            val upperDir = topUpper.resolve(current)
            if (existsNoFollow(upperDir)) return@forEach
            val metaSource = resolveLowerPath(view, current)?.source
            Files.createDirectories(upperDir)
            if (metaSource != null && Files.isDirectory(metaSource, LinkOption.NOFOLLOW_LINKS)) {
                applyMetadata(upperDir, metaSource)
            }
        }
    }

    fun applyLowerMetadata(view: OverlayView, relative: Path, target: Path) {
        val source = resolveLowerPath(view, relative)?.source ?: return
        applyMetadata(target, source)
    }

    fun applyEntrypointOwner(view: OverlayView, target: Path) {
        applyOwnerFromPath(view.entrypoint, target)
    }

    fun markWhiteout(view: OverlayView, relative: String) {
        val rel = Paths.get(relative)
        val topUpper = view.layers.firstOrNull() ?: return
        val parent = rel.parent ?: Paths.get("")
        val name = rel.fileName?.toString() ?: return
        val marker = topUpper.resolve(parent).resolve(markerName(name))
        Files.createDirectories(marker.parent)
        if (!existsNoFollow(marker)) {
            marker.writeBytes(byteArrayOf())
            applyOwnerFromPath(view.entrypoint, marker)
        }
    }

    fun markOpaque(view: OverlayView, relative: String) {
        val rel = Paths.get(relative)
        val topUpper = view.layers.firstOrNull() ?: return
        val marker = topUpper.resolve(rel).resolve(OPAQUE_MARKER)
        Files.createDirectories(marker.parent)
        if (!existsNoFollow(marker)) {
            marker.writeBytes(byteArrayOf())
            applyOwnerFromPath(view.entrypoint, marker)
        }
    }

    private fun isOpaqueParentInLayer(layer: Path, relative: Path): Boolean {
        if (relative.nameCount == 0) return false

        var current = Paths.get("")
        relative.forEach { component ->
            current = current.resolve(component)
            val dir = layer.resolve(current)
            val opaque = dir.resolve(OPAQUE_MARKER)
            if (existsNoFollow(opaque)) {
                return true
            }
        }
        return false
    }

    private fun isWhiteoutedInLayer(layer: Path, relative: Path): Boolean {
        val parent = relative.parent ?: Paths.get("")
        val name = relative.fileName?.toString() ?: return false
        val marker = layer.resolve(parent).resolve(markerName(name))
        return existsNoFollow(marker)
    }

    private fun applyMetadata(target: Path, source: Path) {
        val attrs = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val perms =
                try {
                    Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS)
                } catch (_: Exception) {
                    null
                }
        if (perms != null) {
            Files.setPosixFilePermissions(target, perms)
        }
        val ownerAttrs =
                try {
                    Files.readAttributes(source, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: Exception) {
                    null
                }
        val view = Files.getFileAttributeView(target, PosixFileAttributeView::class.java)
        if (view != null) {
            view.setTimes(attrs.lastModifiedTime(), attrs.lastAccessTime(), attrs.creationTime())
            if (ownerAttrs != null) {
                view.setOwner(ownerAttrs.owner())
                view.setGroup(ownerAttrs.group())
            }
        }
    }

    private fun applyOwnerFromPath(source: Path, target: Path) {
        val attrs = Files.readAttributes(source, PosixFileAttributes::class.java)
        val view = Files.getFileAttributeView(target, PosixFileAttributeView::class.java)
        if (view != null) {
            view.setOwner(attrs.owner())
            view.setGroup(attrs.group())
        }
    }

    private fun existsNoFollow(path: Path): Boolean =
            Files.exists(path, LinkOption.NOFOLLOW_LINKS)
}
