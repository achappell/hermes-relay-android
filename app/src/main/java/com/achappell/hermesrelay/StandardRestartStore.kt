package com.achappell.hermesrelay

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** A safety marker only: no conversation references or turn content are retained. */
internal interface StandardRestartStore {
    fun hasUnresolvedTurn(profile: RelayProfile): Boolean
    fun markUnresolvedTurn(profile: RelayProfile)
    fun clearUnresolvedTurn(profile: RelayProfile)
    fun deleteProfile(profileId: String)
}

internal class FileStandardRestartStore(
    directory: File,
) : StandardRestartStore {
    private val root = directory.toPath()

    constructor(context: Context) : this(File(context.noBackupFilesDir, "standard-restart"))

    @Synchronized
    override fun hasUnresolvedTurn(profile: RelayProfile): Boolean {
        val directory = profileDirectory(profile.id)
        if (!existingDirectory(root) || !existingDirectory(directory)) return false
        val marker = directory.resolve(identityKey(profile))
        val attributes = attributesOrAbsent(marker) ?: return false
        if (!attributes.isRegularFile || attributes.size() != MARKER.size.toLong()) {
            throw IOException("Invalid Standard restart marker")
        }
        Files.newInputStream(marker).use { input ->
            for (byte in MARKER) {
                if (input.read() != byte.toInt()) throw IOException("Invalid Standard restart marker")
            }
            if (input.read() != -1) throw IOException("Invalid Standard restart marker")
        }
        return true
    }

    @Synchronized
    override fun markUnresolvedTurn(profile: RelayProfile) {
        Files.createDirectories(root)
        existingDirectory(root)
        val directory = profileDirectory(profile.id)
        Files.createDirectories(directory)
        existingDirectory(directory)
        val temporary = Files.createTempFile(directory, "pending-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(MARKER)
                output.fd.sync()
            }
            // No non-atomic fallback: failure must prevent prompt submission.
            Files.move(
                temporary,
                directory.resolve(identityKey(profile)),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized
    override fun clearUnresolvedTurn(profile: RelayProfile) {
        val directory = profileDirectory(profile.id)
        if (!existingDirectory(root) || !existingDirectory(directory)) return
        Files.deleteIfExists(directory.resolve(identityKey(profile)))
    }

    @Synchronized
    override fun deleteProfile(profileId: String) {
        val directory = profileDirectory(profileId)
        if (!existingDirectory(root) || !existingDirectory(directory)) return
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) Files.delete(entry)
        }
        Files.delete(directory)
    }

    private fun profileDirectory(profileId: String): Path = root.resolve(opaqueKey(profileId))

    private fun existingDirectory(path: Path): Boolean {
        val attributes = attributesOrAbsent(path) ?: return false
        if (!attributes.isDirectory) throw IOException("Invalid Standard restart directory")
        return true
    }

    private fun attributesOrAbsent(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private companion object {
        val MARKER = "unresolved-v1\n".toByteArray(Charsets.US_ASCII)
    }
}

internal class InMemoryStandardRestartStore : StandardRestartStore {
    private val markers = mutableMapOf<String, MutableSet<String>>()

    @Synchronized
    override fun hasUnresolvedTurn(profile: RelayProfile): Boolean =
        markers[profile.id]?.contains(identityKey(profile)) == true

    @Synchronized
    override fun markUnresolvedTurn(profile: RelayProfile) {
        markers.getOrPut(profile.id) { mutableSetOf() }.add(identityKey(profile))
    }

    @Synchronized
    override fun clearUnresolvedTurn(profile: RelayProfile) {
        markers[profile.id]?.let { identities ->
            identities.remove(identityKey(profile))
            if (identities.isEmpty()) markers.remove(profile.id)
        }
    }

    @Synchronized
    override fun deleteProfile(profileId: String) {
        markers.remove(profileId)
    }
}

private fun identityKey(profile: RelayProfile): String =
    opaqueKey("${profile.mode.name}:${profile.historyKey}")

private fun opaqueKey(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val digits = "0123456789abcdef"
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xff
            append(digits[unsigned ushr 4])
            append(digits[unsigned and 0x0f])
        }
    }
}
