package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/**
 * Validation shared by the asset picker and its unit tests.
 *
 * Asset files are published separately by [AssetsActivity] so Android's atomic rename can be
 * used after this policy has completely copied and validated a staging file.
 */
internal object AssetImportPolicy {

    const val MAX_FILE_NAME_BYTES = 240
    const val MAX_ASSET_BYTES = 64L * 1024L * 1024L

    private const val MAX_GEOSITE_CODES = 100_000L
    private const val MAX_GEOSITE_ITEMS = 5_000_000L
    private const val MAX_GEOSITE_STRING_BYTES = 1024L * 1024L
    private const val MAX_ASSET_VERSION_BYTES = 4096L
    private const val TRANSACTION_FORMAT_VERSION = "3"
    private const val MAX_TRANSACTION_MANIFEST_BYTES = 64L * 1024L
    private const val ORPHAN_STAGING_MIN_AGE_MILLIS = 60L * 60L * 1000L
    private const val LOCK_FILE_NAME = ".asset-publish.lock"
    private const val TRANSACTION_FILE_NAME = ".asset-publish.transaction"
    private const val TRANSACTION_TEMP_PREFIX = ".asset-publish.transaction.tmp-"
    private const val TRANSACTION_CORRUPT_PREFIX = ".asset-publish.transaction.corrupt-"
    private const val DELETE_TRANSACTION_FORMAT_VERSION = "1"
    private const val DELETE_TRANSACTION_FILE_NAME = ".asset-delete.transaction"
    private const val DELETE_TRANSACTION_TEMP_PREFIX = ".asset-delete.transaction.tmp-"
    private const val DELETE_TRANSACTION_CORRUPT_PREFIX = ".asset-delete.transaction.corrupt-"
    private val publishMonitor = Any()
    private val isAndroidRuntime by lazy {
        System.getProperty("java.runtime.name")
            ?.contains("Android", ignoreCase = true) == true ||
                System.getProperty("java.vm.name")
                    ?.contains("Dalvik", ignoreCase = true) == true
    }

    /** Returns the provider name unchanged after proving it is one safe basename. */
    fun requireSafeFileName(reportedName: String): String {
        require(reportedName.isNotBlank()) { "Asset file name is empty" }
        require(reportedName.toByteArray(StandardCharsets.UTF_8).size <= MAX_FILE_NAME_BYTES) {
            "Asset file name is too long"
        }
        require(reportedName.none { it.isISOControl() }) {
            "Asset file name contains control characters"
        }

        val basename = reportedName.substringAfterLast('/').substringAfterLast('\\')
        require(basename == reportedName) { "Asset file name must not contain a path" }
        require(reportedName != "." && reportedName != "..") {
            "Asset file name contains a dot segment"
        }
        require(reportedName.endsWith(".db")) { "Asset file name must end with .db" }

        val stem = reportedName.removeSuffix(".db")
        require(stem.isNotBlank() && stem != "." && stem != "..") {
            "Asset file name contains an invalid stem"
        }
        return reportedName
    }

    /** Resolves a non-symlink child and verifies its canonical parent before any replacement. */
    @Throws(IOException::class)
    fun resolveContainedFile(directory: File, fileName: String): File {
        val safeName = requireSafeFileName(fileName)
        val root = directory.canonicalFile
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Failed to create asset directory")
        }

        val candidate = File(root, safeName).absoluteFile
        val canonicalCandidate = candidate.canonicalFile
        if (canonicalCandidate.parentFile != root || canonicalCandidate != candidate) {
            throw IOException("Asset path is outside the asset directory")
        }
        return candidate
    }

    @Throws(IOException::class)
    fun resolveVersionFile(directory: File, assetFileName: String): File {
        val safeName = requireSafeFileName(assetFileName)
        val versionName = safeName.removeSuffix(".db") + ".version.txt"
        val root = directory.canonicalFile
        val candidate = File(root, versionName).absoluteFile
        val canonicalCandidate = candidate.canonicalFile
        if (canonicalCandidate.parentFile != root || canonicalCandidate != candidate) {
            throw IOException("Asset version path is outside the asset directory")
        }
        return candidate
    }

    @Throws(IOException::class)
    fun copyLimited(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = MAX_ASSET_BYTES,
    ) {
        require(maxBytes >= 0L) { "maxBytes must not be negative" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                val next = input.read()
                if (next < 0) break
                total += 1L
                if (total > maxBytes) {
                    throw IOException("Asset file exceeds $maxBytes bytes")
                }
                output.write(next)
                continue
            }
            total += read
            if (total > maxBytes) {
                throw IOException("Asset file exceeds $maxBytes bytes")
            }
            output.write(buffer, 0, read)
        }
    }

    /**
     * Replaces an asset and its version marker as one crash-recoverable operation.
     *
     * Every rename happens while holding both a JVM monitor and a directory file lock. A synced,
     * checksummed manifest is published before either destination is touched. On the next process
     * start, [recoverPendingTransaction] restores the old complete pair for [Stage.PREPARED], or
     * keeps the new complete pair and finishes cleanup for [Stage.PAIR_INSTALLED].
     * [move] must perform a same-directory atomic replacement.
     */
    @Throws(IOException::class)
    fun replaceAssetAndVersion(
        stagedAsset: File,
        stagedVersion: File,
        destination: File,
        versionDestination: File,
        move: (source: File, destination: File) -> Unit,
    ) = replaceAssetAndVersionInternal(
        stagedAsset,
        stagedVersion,
        destination,
        versionDestination,
        move,
        File::delete,
        onTransactionPrepared = {},
    )

    /**
     * Variant that reports the exact point at which the durable PREPARED marker takes ownership of
     * both staging paths. Callers must retain ownership until [onTransactionPrepared] is invoked.
     */
    @Throws(IOException::class)
    fun replaceAssetAndVersion(
        stagedAsset: File,
        stagedVersion: File,
        destination: File,
        versionDestination: File,
        onTransactionPrepared: () -> Unit,
        move: (source: File, destination: File) -> Unit,
    ) = replaceAssetAndVersionInternal(
        stagedAsset,
        stagedVersion,
        destination,
        versionDestination,
        move,
        File::delete,
        onTransactionPrepared,
    )

    /** Test seam for exercising cleanup failures after the durable commit marker. */
    @Throws(IOException::class)
    internal fun replaceAssetAndVersion(
        stagedAsset: File,
        stagedVersion: File,
        destination: File,
        versionDestination: File,
        move: (source: File, destination: File) -> Unit,
        delete: (File) -> Boolean,
    ) = replaceAssetAndVersionInternal(
        stagedAsset,
        stagedVersion,
        destination,
        versionDestination,
        move,
        delete,
        onTransactionPrepared = {},
    )

    private fun replaceAssetAndVersionInternal(
        stagedAsset: File,
        stagedVersion: File,
        destination: File,
        versionDestination: File,
        move: (source: File, destination: File) -> Unit,
        delete: (File) -> Boolean,
        onTransactionPrepared: () -> Unit,
    ) {
        val directory = transactionDirectory(
            stagedAsset,
            stagedVersion,
            destination,
            versionDestination,
        )
        withPublishLock(directory) {
            prepareForNewOperationLocked(directory, move, delete)
            replaceAssetAndVersionLocked(
                directory,
                stagedAsset,
                stagedVersion,
                destination,
                versionDestination,
                move,
                delete,
                onTransactionPrepared,
            )
        }
    }

    /**
     * Publishes a replaceable database prepared by the Go startup extractor.
     *
     * GeoIP has already been parsed by libcore before this callback. GeoSite is parsed here so
     * neither bundled database can bypass the validation used by manual and downloaded imports.
     * Returning false leaves [stagedAsset] owned by the caller; returning true transfers it to the
     * transaction and publishes (or recoverably retains) the new asset/version pair.
     */
    @Throws(IOException::class)
    fun publishBundledAsset(
        directory: File,
        name: String,
        bundledVersion: String,
        stagedAsset: File,
        useOfficialAssets: Boolean,
        move: (source: File, destination: File) -> Unit,
    ): Boolean = publishBundledAsset(
        directory,
        name,
        bundledVersion,
        stagedAsset,
        useOfficialAssets = { useOfficialAssets },
        move = move,
    )

    /** Reads the current provider preference only after entering the cross-process publish lock. */
    @Throws(IOException::class)
    fun publishBundledAsset(
        directory: File,
        name: String,
        bundledVersion: String,
        stagedAsset: File,
        useOfficialAssets: () -> Boolean,
        move: (source: File, destination: File) -> Unit,
    ): Boolean = publishBundledAsset(
        directory,
        name,
        bundledVersion,
        stagedAsset,
        useOfficialAssets,
        validateAsset = { candidate, destinationName ->
            when (destinationName) {
                "geoip.db" -> false
                "geosite.db" -> isRecognizedAsset(candidate, destinationName)
                else -> false
            }
        },
        move = move,
    )

    /** Variant with a validator seam that is deliberately invoked while holding the publish lock. */
    @Throws(IOException::class)
    fun publishBundledAsset(
        directory: File,
        name: String,
        bundledVersion: String,
        stagedAsset: File,
        useOfficialAssets: Boolean,
        validateAsset: (file: File, destinationName: String) -> Boolean,
        move: (source: File, destination: File) -> Unit,
    ): Boolean = publishBundledAsset(
        directory,
        name,
        bundledVersion,
        stagedAsset,
        useOfficialAssets = { useOfficialAssets },
        validateAsset = validateAsset,
        move = move,
    )

    /** Variant with provider and validator seams that are both evaluated under the publish lock. */
    @Throws(IOException::class)
    fun publishBundledAsset(
        directory: File,
        name: String,
        bundledVersion: String,
        stagedAsset: File,
        useOfficialAssets: () -> Boolean,
        validateAsset: (file: File, destinationName: String) -> Boolean,
        move: (source: File, destination: File) -> Unit,
    ): Boolean {
        val safeName = requireSafeFileName(name)
        require(safeName == "geoip.db" || safeName == "geosite.db") {
            "Unsupported bundled asset $safeName"
        }
        requireBundledVersion(bundledVersion)

        val root = directory.canonicalFile
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Failed to create asset directory")
        }
        if (stagedAsset.parentFile?.canonicalFile != root) {
            throw IOException("Bundled asset staging file must share its destination directory")
        }
        val destination = resolveContainedFile(root, safeName)
        val versionDestination = resolveVersionFile(root, safeName)

        return withPublishLock(root) {
            prepareForNewOperationLocked(root, move, File::delete)
            requireRegularFileNoFollow(stagedAsset, "Bundled asset staging file")
            val shouldPublish = bundledAssetNeedsPublication(
                destination,
                versionDestination,
                bundledVersion,
                useOfficialAssets(),
            )
            if (!shouldPublish) return@withPublishLock false

            val beforeValidation = fingerprint(
                stagedAsset,
                "bundled staged asset",
                MAX_ASSET_BYTES,
            )
            if (beforeValidation.size <= 0L) {
                throw IOException("Bundled asset staging file is empty")
            }
            if (!runCatching { validateAsset(stagedAsset, safeName) }.getOrDefault(false)) {
                throw IOException("Bundled asset failed validation")
            }
            val afterValidation = fingerprint(
                stagedAsset,
                "validated bundled staged asset",
                MAX_ASSET_BYTES,
            )
            if (afterValidation != beforeValidation) {
                throw IOException("Bundled asset changed while it was validated")
            }

            val ownedAsset = uniqueTransactionFile(root, ".asset-", ".tmp")
            val ownedVersion = uniqueTransactionFile(root, ".asset-version-", ".tmp")
            try {
                moveRegularFile(root, stagedAsset, ownedAsset, move)
                if (!ownedVersion.createNewFile()) {
                    throw IOException("Failed to reserve bundled asset version staging file")
                }
                FileOutputStream(ownedVersion).use { output ->
                    output.write(bundledVersion.toByteArray(StandardCharsets.UTF_8))
                    output.fd.sync()
                }
                syncDirectory(root)

                replaceAssetAndVersionLocked(
                    root,
                    ownedAsset,
                    ownedVersion,
                    destination,
                    versionDestination,
                    move,
                    File::delete,
                    onTransactionPrepared = {},
                )
                true
            } catch (failure: Exception) {
                if (pathKindNoFollow(File(root, TRANSACTION_FILE_NAME)) == PathKind.ABSENT) {
                    runCatching { deleteIfPresent(ownedAsset, File::delete) }
                        .onFailure(failure::addSuppressed)
                    runCatching { deleteIfPresent(ownedVersion, File::delete) }
                        .onFailure(failure::addSuppressed)
                }
                throw failure
            }
        }
    }

    /** Deletes an asset and its marker only after repairing any interrupted publication. */
    @Throws(IOException::class)
    fun deleteAssetAndVersion(
        directory: File,
        assetName: String,
        move: (source: File, destination: File) -> Unit,
    ) = deleteAssetAndVersion(directory, assetName, move, File::delete)

    /** Test seam for simulating a process exit between the two durable deletion steps. */
    @Throws(IOException::class)
    internal fun deleteAssetAndVersion(
        directory: File,
        assetName: String,
        move: (source: File, destination: File) -> Unit,
        delete: (File) -> Boolean,
    ) {
        val root = directory.canonicalFile
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Failed to create asset directory")
        }
        val destination = resolveContainedFile(root, assetName)
        val versionDestination = resolveVersionFile(root, assetName)
        withPublishLock(root) {
            prepareForNewOperationLocked(root, move, delete)
            requireAbsentOrRegularFile(destination, "Asset deletion target")
            requireAbsentOrRegularFile(versionDestination, "Asset version deletion target")
            if (pathKindNoFollow(destination) == PathKind.ABSENT &&
                pathKindNoFollow(versionDestination) == PathKind.ABSENT
            ) return@withPublishLock

            val transaction = AssetDeletionTransaction(
                transactionId = UUID.randomUUID().toString(),
                destinationName = destination.name,
                versionDestinationName = versionDestination.name,
            ).also { validateDeletionTransaction(root, it) }
            writeDeletionTransaction(root, transaction, move)
            finishDeletionTransaction(root, transaction, delete)
        }
    }

    private fun bundledAssetNeedsPublication(
        destination: File,
        versionDestination: File,
        bundledVersion: String,
        useOfficialAssets: Boolean,
    ): Boolean {
        val assetExists = requireAbsentOrRegularFile(destination, "Bundled asset destination")
        val versionExists = requireAbsentOrRegularFile(
            versionDestination,
            "Bundled asset version destination",
        )
        if (!assetExists) return true
        if (!useOfficialAssets) return false
        if (!versionExists) return true

        val localVersion = readAssetVersion(versionDestination)
        if (localVersion == "Custom") return false
        val bundledNumber = parseUnsignedDecimal(bundledVersion)
            ?: return bundledVersion != localVersion
        val localNumber = parseUnsignedDecimal(localVersion) ?: return true
        return compareUnsignedDecimal(bundledNumber, localNumber) > 0
    }

    private fun readAssetVersion(file: File): String {
        requireRegularFileNoFollow(file, "Bundled asset version destination")
        val bytes = ByteArrayOutputStream().also { output ->
            FileInputStream(file).use { input ->
                copyLimited(input, output, MAX_ASSET_VERSION_BYTES)
            }
        }.toByteArray()
        val value = bytes.decodeUtf8Strict()
            ?: throw IOException("Bundled asset version is not valid UTF-8")
        return value.trim()
    }

    private fun requireBundledVersion(version: String) {
        require(version.isNotEmpty()) { "Bundled asset version is empty" }
        require(version.toByteArray(StandardCharsets.UTF_8).size <= MAX_ASSET_VERSION_BYTES) {
            "Bundled asset version is too long"
        }
        require(version.none { it.isISOControl() }) {
            "Bundled asset version contains control characters"
        }
    }

    /** Returns a canonical decimal representation when [value] fits an unsigned 64-bit integer. */
    private fun parseUnsignedDecimal(value: String): String? {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        val normalized = value.trimStart('0').ifEmpty { "0" }
        val maximum = "18446744073709551615"
        if (normalized.length > maximum.length ||
            normalized.length == maximum.length && normalized > maximum
        ) return null
        return normalized
    }

    private fun compareUnsignedDecimal(left: String, right: String): Int =
        if (left.length != right.length) left.length.compareTo(right.length) else left.compareTo(right)

    private fun uniqueTransactionFile(directory: File, prefix: String, suffix: String): File {
        repeat(16) {
            val candidate = File(directory, "$prefix${UUID.randomUUID()}$suffix")
            if (pathKindNoFollow(candidate) == PathKind.ABSENT) return candidate
        }
        throw IOException("Failed to reserve a unique asset transaction path")
    }

    private fun replaceAssetAndVersionLocked(
        directory: File,
        stagedAsset: File,
        stagedVersion: File,
        destination: File,
        versionDestination: File,
        move: (source: File, destination: File) -> Unit,
        delete: (File) -> Boolean,
        onTransactionPrepared: () -> Unit,
    ) {
        requireRegularFileNoFollow(stagedAsset, "Asset transaction staged asset")
        requireRegularFileNoFollow(stagedVersion, "Asset transaction staged version")
        val assetExisted = requireAbsentOrRegularFile(destination, "Asset destination")
        val versionExisted = requireAbsentOrRegularFile(
            versionDestination,
            "Asset version destination",
        )
        val stagedAssetFingerprint = fingerprint(stagedAsset, "staged asset")
        val stagedVersionFingerprint = fingerprint(stagedVersion, "staged version")
        val originalAssetFingerprint = if (assetExisted) {
            fingerprint(destination, "original asset")
        } else {
            FileFingerprint.ABSENT
        }
        val originalVersionFingerprint = if (versionExisted) {
            fingerprint(versionDestination, "original version")
        } else {
            FileFingerprint.ABSENT
        }
        val transactionId = UUID.randomUUID().toString()
        val transaction = AssetTransaction(
            transactionId = transactionId,
            stage = Stage.PREPARED,
            destinationName = destination.name,
            versionDestinationName = versionDestination.name,
            stagedAssetName = stagedAsset.name,
            stagedVersionName = stagedVersion.name,
            assetBackupName = ".asset-backup-$transactionId.tmp",
            versionBackupName = ".asset-version-backup-$transactionId.tmp",
            assetExisted = assetExisted,
            versionExisted = versionExisted,
            assetSize = stagedAssetFingerprint.size,
            assetSha256 = stagedAssetFingerprint.sha256,
            versionSize = stagedVersionFingerprint.size,
            versionSha256 = stagedVersionFingerprint.sha256,
            originalAssetSize = originalAssetFingerprint.size,
            originalAssetSha256 = originalAssetFingerprint.sha256,
            originalVersionSize = originalVersionFingerprint.size,
            originalVersionSha256 = originalVersionFingerprint.sha256,
        ).also { validateTransaction(directory, it) }
        requireAbsentFile(transaction.assetBackup(directory), "Asset transaction backup")
        requireAbsentFile(
            transaction.versionBackup(directory),
            "Asset transaction version backup",
        )

        try {
            writeTransaction(directory, transaction, move)
            onTransactionPrepared()
            if (transaction.assetExisted) {
                moveRegularFile(
                    directory,
                    destination,
                    transaction.assetBackup(directory),
                    move,
                )
            }
            if (transaction.versionExisted) {
                moveRegularFile(
                    directory,
                    versionDestination,
                    transaction.versionBackup(directory),
                    move,
                )
            }
            moveRegularFile(directory, stagedAsset, destination, move)
            moveRegularFile(directory, stagedVersion, versionDestination, move)

            val installedAsset = fingerprint(destination, "installed asset")
            val installedVersion = fingerprint(versionDestination, "installed version")
            if (installedAsset != stagedAssetFingerprint ||
                installedVersion != stagedVersionFingerprint
            ) {
                throw IOException("Installed asset pair differs from its staged files")
            }
            val installedTransaction = transaction.copy(
                stage = Stage.PAIR_INSTALLED,
                assetSize = installedAsset.size,
                assetSha256 = installedAsset.sha256,
                versionSize = installedVersion.size,
                versionSha256 = installedVersion.sha256,
            )
            writeTransaction(directory, installedTransaction, move)
            finishInstalledTransaction(directory, installedTransaction, delete)
        } catch (failure: Exception) {
            runCatching {
                recoverPendingTransactionLocked(
                    directory,
                    move,
                    delete,
                    cleanupOrphans = false,
                )
            }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    /** Recovers a transaction left by a previous process before the core reads external assets. */
    @Throws(IOException::class)
    fun recoverPendingTransaction(
        directory: File,
        move: (source: File, destination: File) -> Unit,
    ) = recoverPendingTransaction(
        directory = directory,
        onCorruptManifest = { _, _ -> },
        move = move,
    )

    /**
     * Startup recovery variant that quarantines an unreadable durable marker and reports its
     * unique evidence path. Recovery used before a new mutation remains strict.
     */
    @Throws(IOException::class)
    fun recoverPendingTransaction(
        directory: File,
        onCorruptManifest: (evidence: File, failure: IOException) -> Unit,
        move: (source: File, destination: File) -> Unit,
    ) {
        val root = directory.canonicalFile
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Failed to create asset directory")
        }
        val reports = ArrayList<Pair<File, IOException>>(1)
        withPublishLock(root) {
            var canContinue = true
            try {
                recoverPendingTransactionLocked(
                    root,
                    move,
                    File::delete,
                    cleanupOrphans = false,
                )
            } catch (failure: InvalidTransactionManifestException) {
                val evidence = quarantineCorruptManifest(
                    root,
                    File(root, TRANSACTION_FILE_NAME),
                    TRANSACTION_CORRUPT_PREFIX,
                    move,
                )
                reports += evidence to failure
                canContinue = false
            }

            if (canContinue &&
                pathKindNoFollow(File(root, TRANSACTION_FILE_NAME)) == PathKind.ABSENT
            ) {
                try {
                    recoverPendingDeletionLocked(root, File::delete)
                } catch (failure: InvalidDeletionManifestException) {
                    val evidence = quarantineCorruptManifest(
                        root,
                        File(root, DELETE_TRANSACTION_FILE_NAME),
                        DELETE_TRANSACTION_CORRUPT_PREFIX,
                        move,
                    )
                    reports += evidence to failure
                    canContinue = false
                }
            }
            if (canContinue) cleanupOrphanStaging(root, File::delete)
        }
        reports.forEach { (evidence, failure) ->
            runCatching { onCorruptManifest(evidence, failure) }
        }
    }

    private fun quarantineCorruptManifest(
        directory: File,
        manifest: File,
        prefix: String,
        move: (source: File, destination: File) -> Unit,
    ): File {
        val evidence = uniqueTransactionFile(directory, prefix, "")
        moveRegularFile(directory, manifest, evidence, move)
        return evidence
    }

    /**
     * Completes older work before a new operation is allowed to publish another durable marker.
     * A committed publication may deliberately retain its marker when cleanup fails; never replace
     * that marker, because doing so would orphan its backups and discard the retry evidence.
     */
    private fun prepareForNewOperationLocked(
        directory: File,
        move: (source: File, destination: File) -> Unit,
        delete: (File) -> Boolean,
    ) {
        recoverPendingTransactionLocked(directory, move, delete, cleanupOrphans = false)
        requireMarkerAbsent(
            File(directory, TRANSACTION_FILE_NAME),
            "Asset publication cleanup is incomplete",
        )
        recoverPendingDeletionLocked(directory, delete)
        requireMarkerAbsent(
            File(directory, DELETE_TRANSACTION_FILE_NAME),
            "Asset deletion cleanup is incomplete",
        )
    }

    private fun requireMarkerAbsent(marker: File, description: String) {
        when (pathKindNoFollow(marker)) {
            PathKind.ABSENT -> Unit
            PathKind.REGULAR_FILE -> throw IOException("$description; evidence preserved")
            PathKind.OTHER -> throw IOException("$description and its marker is invalid; evidence preserved")
        }
    }

    private fun recoverPendingTransactionLocked(
        directory: File,
        move: (source: File, destination: File) -> Unit,
        delete: (File) -> Boolean,
        cleanupOrphans: Boolean,
    ) {
        val manifestFile = File(directory, TRANSACTION_FILE_NAME)
        when (pathKindNoFollow(manifestFile)) {
            PathKind.ABSENT -> {
                cleanupManifestTemps(directory, delete)
                if (cleanupOrphans) cleanupOrphanStaging(directory, delete)
                return
            }
            PathKind.REGULAR_FILE -> Unit
            PathKind.OTHER -> throw IOException(
                "Asset transaction manifest is not a regular file; evidence preserved"
            )
        }

        val transaction = readTransaction(directory, manifestFile)
        when (transaction.stage) {
            Stage.PREPARED -> restorePreparedTransaction(
                directory,
                transaction,
                move,
                delete,
            )
            Stage.PAIR_INSTALLED -> finishInstalledTransaction(directory, transaction, delete)
        }
    }

    private fun restorePreparedTransaction(
        directory: File,
        transaction: AssetTransaction,
        move: (source: File, destination: File) -> Unit,
        delete: (File) -> Boolean,
    ) {
        var restoreFailure: Exception? = null
        fun attemptRestore(block: () -> Unit) {
            try {
                block()
            } catch (failure: Exception) {
                val firstFailure = restoreFailure
                if (firstFailure == null) {
                    restoreFailure = failure
                } else {
                    firstFailure.addSuppressed(failure)
                }
            }
        }

        // Repair both halves when possible. A failure restoring one file must not prevent the
        // independent backup from being put back; the manifest and failed backup remain for retry.
        attemptRestore {
            restoreOriginal(
                directory,
                transaction.versionDestination(directory),
                transaction.versionBackup(directory),
                transaction.versionExisted,
                FileFingerprint(
                    transaction.originalVersionSize,
                    transaction.originalVersionSha256,
                ),
                FileFingerprint(transaction.versionSize, transaction.versionSha256),
                move,
                delete,
            )
        }
        attemptRestore {
            restoreOriginal(
                directory,
                transaction.destination(directory),
                transaction.assetBackup(directory),
                transaction.assetExisted,
                FileFingerprint(
                    transaction.originalAssetSize,
                    transaction.originalAssetSha256,
                ),
                FileFingerprint(transaction.assetSize, transaction.assetSha256),
                move,
                delete,
            )
        }
        restoreFailure?.let { throw it }

        deleteIfPresent(transaction.stagedAsset(directory), delete)
        deleteIfPresent(transaction.stagedVersion(directory), delete)
        deleteIfPresent(transaction.assetBackup(directory), delete)
        deleteIfPresent(transaction.versionBackup(directory), delete)
        deleteManifestLast(directory, delete)
    }

    private fun restoreOriginal(
        directory: File,
        destination: File,
        backup: File,
        hadOriginal: Boolean,
        originalFingerprint: FileFingerprint,
        installedFingerprint: FileFingerprint,
        move: (source: File, destination: File) -> Unit,
        delete: (File) -> Boolean,
    ) {
        if (hadOriginal) {
            when (pathKindNoFollow(backup)) {
                PathKind.REGULAR_FILE -> {
                    verifyFingerprint(
                        backup,
                        originalFingerprint,
                        "original backup ${backup.name}",
                    )
                    requireAbsentOrRegularFile(destination, "Asset transaction destination")
                    moveRegularFile(directory, backup, destination, move)
                    verifyFingerprint(
                        destination,
                        originalFingerprint,
                        "restored destination ${destination.name}",
                    )
                }
                PathKind.ABSENT -> when (pathKindNoFollow(destination)) {
                    PathKind.REGULAR_FILE -> {
                        val actual = fingerprint(
                            destination,
                            "prepared destination ${destination.name}",
                        )
                        when {
                            actual == originalFingerprint -> Unit
                            actual == installedFingerprint -> throw IOException(
                                "Asset transaction backup is missing while its new destination " +
                                    "is installed; evidence preserved",
                            )
                            else -> throw IOException(
                                "Asset transaction backup is missing and its destination does " +
                                    "not match the original; evidence preserved",
                            )
                        }
                    }
                    PathKind.ABSENT -> throw IOException(
                        "Asset transaction original and backup are both missing; evidence preserved"
                    )
                    PathKind.OTHER -> throw IOException(
                        "Asset transaction destination is not a regular file; evidence preserved"
                    )
                }
                PathKind.OTHER -> throw IOException(
                    "Asset transaction backup is not a regular file; evidence preserved"
                )
            }
        } else {
            when (pathKindNoFollow(backup)) {
                PathKind.ABSENT -> Unit
                PathKind.REGULAR_FILE,
                PathKind.OTHER,
                -> throw IOException("Unexpected asset transaction backup; evidence preserved")
            }
            when (pathKindNoFollow(destination)) {
                PathKind.ABSENT -> Unit
                PathKind.REGULAR_FILE -> {
                    if (fingerprint(
                            destination,
                            "prepared destination ${destination.name}",
                        ) != installedFingerprint
                    ) {
                        throw IOException(
                            "Unexpected asset transaction destination content; evidence preserved",
                        )
                    }
                    deleteIfPresent(destination, delete)
                }
                PathKind.OTHER -> throw IOException(
                    "Asset transaction destination is not a regular file; evidence preserved"
                )
            }
        }
    }

    private fun finishInstalledTransaction(
        directory: File,
        transaction: AssetTransaction,
        delete: (File) -> Boolean,
    ) {
        // Re-read the durable marker. A PREPARED marker always means rollback, even when both
        // destination renames happened in the previous process.
        val durable = readTransaction(directory, File(directory, TRANSACTION_FILE_NAME))
        if (durable.stage != Stage.PAIR_INSTALLED || durable.transactionId != transaction.transactionId) {
            throw IOException("Asset transaction commit marker was not persisted")
        }
        verifyFingerprint(
            durable.destination(directory),
            FileFingerprint(durable.assetSize, durable.assetSha256),
            "committed asset",
        )
        verifyFingerprint(
            durable.versionDestination(directory),
            FileFingerprint(durable.versionSize, durable.versionSha256),
            "committed asset version",
        )

        // The pair is committed once the synced PAIR_INSTALLED manifest and both fingerprints
        // agree. Cleanup is retryable housekeeping and must not turn that successful publication
        // into a reported failure.
        val payloadCleaned = listOf(
            durable.assetBackup(directory),
            durable.versionBackup(directory),
            durable.stagedAsset(directory),
            durable.stagedVersion(directory),
        ).all { file -> runCatching { deleteIfPresent(file, delete) }.isSuccess }
        if (!payloadCleaned) return

        val tempsCleaned = cleanupManifestTempsBestEffort(directory, delete)
        if (!tempsCleaned) return
        runCatching { deleteIfPresent(File(directory, TRANSACTION_FILE_NAME), delete) }
    }

    private fun deleteManifestLast(directory: File, delete: (File) -> Boolean) {
        cleanupManifestTemps(directory, delete)
        deleteIfPresent(File(directory, TRANSACTION_FILE_NAME), delete)
    }

    private fun cleanupManifestTemps(directory: File, delete: (File) -> Boolean) {
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith(TRANSACTION_TEMP_PREFIX) }
            .forEach { deleteIfPresent(it, delete) }
    }

    private fun cleanupManifestTempsBestEffort(
        directory: File,
        delete: (File) -> Boolean,
    ): Boolean = directory.listFiles().orEmpty()
        .filter { it.name.startsWith(TRANSACTION_TEMP_PREFIX) }
        .all { runCatching { deleteIfPresent(it, delete) }.isSuccess }

    /** Completes an interrupted pair deletion while its durable marker still owns both paths. */
    private fun recoverPendingDeletionLocked(
        directory: File,
        delete: (File) -> Boolean,
    ) {
        val manifestFile = File(directory, DELETE_TRANSACTION_FILE_NAME)
        when (pathKindNoFollow(manifestFile)) {
            PathKind.ABSENT -> {
                cleanupDeletionManifestTemps(directory, delete)
                return
            }
            PathKind.REGULAR_FILE -> Unit
            PathKind.OTHER -> throw IOException(
                "Asset deletion manifest is not a regular file; evidence preserved",
            )
        }

        finishDeletionTransaction(
            directory,
            readDeletionTransaction(directory, manifestFile),
            delete,
        )
    }

    private fun finishDeletionTransaction(
        directory: File,
        transaction: AssetDeletionTransaction,
        delete: (File) -> Boolean,
    ) {
        val manifestFile = File(directory, DELETE_TRANSACTION_FILE_NAME)
        val durable = readDeletionTransaction(directory, manifestFile)
        if (durable != transaction) {
            throw IOException("Asset deletion marker changed; evidence preserved")
        }

        val destination = durable.destination(directory)
        val versionDestination = durable.versionDestination(directory)
        // Validate both paths before touching either one. This keeps a replaced directory or link
        // from turning a recovered pair deletion into a partial operation.
        requireAbsentOrRegularFile(destination, "Asset deletion target")
        requireAbsentOrRegularFile(versionDestination, "Asset version deletion target")
        deleteIfPresent(destination, delete)
        deleteIfPresent(versionDestination, delete)
        cleanupDeletionManifestTemps(directory, delete)
        deleteIfPresent(manifestFile, delete)
    }

    private fun cleanupDeletionManifestTemps(directory: File, delete: (File) -> Boolean) {
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith(DELETE_TRANSACTION_TEMP_PREFIX) }
            .forEach { deleteIfPresent(it, delete) }
    }

    private fun cleanupOrphanStaging(directory: File, delete: (File) -> Boolean) {
        val staleBefore = System.currentTimeMillis() - ORPHAN_STAGING_MIN_AGE_MILLIS
        directory.listFiles().orEmpty()
            .filter { isOrphanStagingName(it.name) }
            .filter { it.lastModified() in 1..staleBefore }
            .filter { pathKindNoFollow(it) == PathKind.REGULAR_FILE }
            .forEach { deleteIfPresent(it, delete) }
    }

    private fun isOrphanStagingName(name: String): Boolean {
        if (!name.endsWith(".tmp")) return false
        if (name.startsWith(".asset-version-backup-")) return false
        if (name.startsWith(".asset-backup-")) return false
        if (name.startsWith(".asset-publish.")) return false
        if (name.startsWith(".asset-delete.")) return false
        return name.startsWith(".asset-version-") ||
            name.startsWith(".asset-") && !name.startsWith(".asset-version-")
    }

    private fun deleteIfPresent(file: File, delete: (File) -> Boolean) {
        when (pathKindNoFollow(file)) {
            PathKind.ABSENT -> return
            PathKind.OTHER -> throw IOException(
                "Asset transaction file ${file.name} is not a regular file; evidence preserved"
            )
            PathKind.REGULAR_FILE -> Unit
        }
        if (!delete(file)) {
            throw IOException("Failed to delete asset transaction file ${file.name}")
        }
        file.parentFile?.let(::syncDirectory)
    }

    private inline fun <T> withPublishLock(directory: File, block: () -> T): T =
        synchronized(publishMonitor) {
            val lockFile = File(directory, LOCK_FILE_NAME)
            requireAbsentOrRegularFile(lockFile, "Asset transaction lock")
            RandomAccessFile(lockFile, "rw").use { lockAccess ->
                requireRegularFileNoFollow(lockFile, "Asset transaction lock")
                val lock = lockAccess.channel.lock()
                try {
                    block()
                } finally {
                    lock.release()
                }
            }
        }

    private fun transactionDirectory(
        stagedAsset: File,
        stagedVersion: File,
        destination: File,
        versionDestination: File,
    ): File {
        val directory = destination.parentFile?.canonicalFile
            ?: throw IOException("Asset destination has no parent directory")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Failed to create asset directory")
        }
        val files = listOf(stagedAsset, stagedVersion, destination, versionDestination)
        if (files.any { it.parentFile?.canonicalFile != directory }) {
            throw IOException("Asset transaction files must share one directory")
        }
        if (files.map { it.canonicalFile }.distinct().size != files.size) {
            throw IOException("Asset transaction files must be distinct")
        }
        return directory
    }

    private fun writeTransaction(
        directory: File,
        transaction: AssetTransaction,
        move: (source: File, destination: File) -> Unit,
    ) {
        validateTransaction(directory, transaction)
        val properties = transaction.toProperties()
        val temporary = File(directory, "$TRANSACTION_TEMP_PREFIX${UUID.randomUUID()}")
        if (!temporary.createNewFile()) {
            throw IOException("Failed to reserve asset transaction manifest")
        }
        try {
            FileOutputStream(temporary).use { output ->
                properties.store(output, null)
                output.fd.sync()
            }
            moveRegularFile(
                directory,
                temporary,
                File(directory, TRANSACTION_FILE_NAME),
                move,
            )
        } finally {
            runCatching { deleteIfPresent(temporary, File::delete) }
        }
    }

    private fun writeDeletionTransaction(
        directory: File,
        transaction: AssetDeletionTransaction,
        move: (source: File, destination: File) -> Unit,
    ) {
        validateDeletionTransaction(directory, transaction)
        val manifestFile = File(directory, DELETE_TRANSACTION_FILE_NAME)
        requireAbsentFile(manifestFile, "Asset deletion manifest")
        val temporary = File(directory, "$DELETE_TRANSACTION_TEMP_PREFIX${UUID.randomUUID()}")
        if (!temporary.createNewFile()) {
            throw IOException("Failed to reserve asset deletion manifest")
        }
        try {
            FileOutputStream(temporary).use { output ->
                transaction.toProperties().store(output, null)
                output.fd.sync()
            }
            moveRegularFile(directory, temporary, manifestFile, move)
        } finally {
            runCatching { deleteIfPresent(temporary, File::delete) }
        }
    }

    private fun readTransaction(directory: File, manifestFile: File): AssetTransaction = try {
        requireRegularFileNoFollow(manifestFile, "Asset transaction manifest")
        require(manifestFile.length() in 1..MAX_TRANSACTION_MANIFEST_BYTES) {
            "Invalid transaction manifest size"
        }
        val properties = Properties().apply {
            FileInputStream(manifestFile).use(::load)
        }
        val expectedKeys = MANIFEST_KEYS + "checksum"
        require(properties.stringPropertyNames() == expectedKeys) {
            "Unexpected transaction manifest fields"
        }
        val transaction = AssetTransaction(
            transactionId = properties.required("transactionId"),
            stage = Stage.valueOf(properties.required("stage")),
            destinationName = properties.required("destination"),
            versionDestinationName = properties.required("versionDestination"),
            stagedAssetName = properties.required("stagedAsset"),
            stagedVersionName = properties.required("stagedVersion"),
            assetBackupName = properties.required("assetBackup"),
            versionBackupName = properties.required("versionBackup"),
            assetExisted = properties.requiredBoolean("assetExisted"),
            versionExisted = properties.requiredBoolean("versionExisted"),
            assetSize = properties.requiredLong("assetSize"),
            assetSha256 = properties.required("assetSha256"),
            versionSize = properties.requiredLong("versionSize"),
            versionSha256 = properties.required("versionSha256"),
            originalAssetSize = properties.requiredLong("originalAssetSize"),
            originalAssetSha256 = properties.getProperty("originalAssetSha256")
                ?: throw IllegalArgumentException(
                    "Missing transaction manifest field originalAssetSha256",
                ),
            originalVersionSize = properties.requiredLong("originalVersionSize"),
            originalVersionSha256 = properties.getProperty("originalVersionSha256")
                ?: throw IllegalArgumentException(
                    "Missing transaction manifest field originalVersionSha256",
                ),
        )
        require(properties.required("formatVersion") == TRANSACTION_FORMAT_VERSION) {
            "Unsupported transaction manifest version"
        }
        val expectedChecksum = transaction.checksum()
        val actualChecksum = properties.required("checksum")
        require(
            MessageDigest.isEqual(
                expectedChecksum.toByteArray(StandardCharsets.US_ASCII),
                actualChecksum.toByteArray(StandardCharsets.US_ASCII),
            )
        ) { "Transaction manifest checksum mismatch" }
        validateTransaction(directory, transaction)
        transaction
    } catch (failure: Exception) {
        throw InvalidTransactionManifestException(failure)
    }

    private fun readDeletionTransaction(
        directory: File,
        manifestFile: File,
    ): AssetDeletionTransaction = try {
        requireRegularFileNoFollow(manifestFile, "Asset deletion manifest")
        require(manifestFile.length() in 1..MAX_TRANSACTION_MANIFEST_BYTES) {
            "Invalid asset deletion manifest size"
        }
        val properties = Properties().apply {
            FileInputStream(manifestFile).use(::load)
        }
        val expectedKeys = DELETE_MANIFEST_KEYS + "checksum"
        require(properties.stringPropertyNames() == expectedKeys) {
            "Unexpected asset deletion manifest fields"
        }
        require(properties.required("formatVersion") == DELETE_TRANSACTION_FORMAT_VERSION) {
            "Unsupported asset deletion manifest version"
        }
        val transaction = AssetDeletionTransaction(
            transactionId = properties.required("transactionId"),
            destinationName = properties.required("destination"),
            versionDestinationName = properties.required("versionDestination"),
        )
        val expectedChecksum = transaction.checksum()
        val actualChecksum = properties.required("checksum")
        require(
            MessageDigest.isEqual(
                expectedChecksum.toByteArray(StandardCharsets.US_ASCII),
                actualChecksum.toByteArray(StandardCharsets.US_ASCII),
            ),
        ) { "Asset deletion manifest checksum mismatch" }
        validateDeletionTransaction(directory, transaction)
        transaction
    } catch (failure: Exception) {
        throw InvalidDeletionManifestException(failure)
    }

    private fun validateTransaction(directory: File, transaction: AssetTransaction) {
        UUID.fromString(transaction.transactionId)
        requireSafeFileName(transaction.destinationName)
        require(
            transaction.versionDestinationName ==
                transaction.destinationName.removeSuffix(".db") + ".version.txt"
        ) { "Asset transaction version destination does not match its asset" }
        requireTransactionName(transaction.versionDestinationName)
        require(
            transaction.stagedAssetName.startsWith(".asset-") &&
                transaction.stagedAssetName.endsWith(".tmp")
        ) { "Invalid staged asset name" }
        require(
            transaction.stagedVersionName.startsWith(".asset-version-") &&
                transaction.stagedVersionName.endsWith(".tmp")
        ) { "Invalid staged asset version name" }
        require(
            transaction.assetBackupName ==
                ".asset-backup-${transaction.transactionId}.tmp"
        ) { "Invalid asset backup name" }
        require(
            transaction.versionBackupName ==
                ".asset-version-backup-${transaction.transactionId}.tmp"
        ) { "Invalid asset version backup name" }
        require(transaction.assetSize >= 0L && transaction.versionSize >= 0L) {
            "Invalid asset transaction file size"
        }
        require(isSha256(transaction.assetSha256) && isSha256(transaction.versionSha256)) {
            "Invalid asset transaction digest"
        }
        require(
            isOriginalFingerprint(
                transaction.assetExisted,
                transaction.originalAssetSize,
                transaction.originalAssetSha256,
            ) && isOriginalFingerprint(
                transaction.versionExisted,
                transaction.originalVersionSize,
                transaction.originalVersionSha256,
            ),
        ) { "Invalid original asset transaction fingerprint" }
        val names = listOf(
            transaction.destinationName,
            transaction.versionDestinationName,
            transaction.stagedAssetName,
            transaction.stagedVersionName,
            transaction.assetBackupName,
            transaction.versionBackupName,
        )
        names.forEach(::requireTransactionName)
        require(names.distinct().size == names.size) { "Asset transaction paths must be distinct" }
        require(names.all { File(directory, it).parentFile?.canonicalFile == directory }) {
            "Asset transaction path escaped its directory"
        }
    }

    private fun validateDeletionTransaction(
        directory: File,
        transaction: AssetDeletionTransaction,
    ) {
        UUID.fromString(transaction.transactionId)
        requireSafeFileName(transaction.destinationName)
        require(
            transaction.versionDestinationName ==
                transaction.destinationName.removeSuffix(".db") + ".version.txt",
        ) { "Asset deletion version destination does not match its asset" }
        requireTransactionName(transaction.versionDestinationName)
        val names = listOf(transaction.destinationName, transaction.versionDestinationName)
        require(names.distinct().size == names.size) { "Asset deletion paths must be distinct" }
        require(names.all { File(directory, it).parentFile?.canonicalFile == directory }) {
            "Asset deletion path escaped its directory"
        }
    }

    private fun requireTransactionName(name: String) {
        require(name.isNotBlank() && File(name).name == name && name != "." && name != "..") {
            "Invalid asset transaction file name"
        }
        require(name.none { it.isISOControl() }) { "Invalid asset transaction file name" }
    }

    private fun Properties.required(key: String): String =
        getProperty(key)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing transaction manifest field $key")

    private fun Properties.requiredBoolean(key: String): Boolean = when (val value = required(key)) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Invalid transaction manifest boolean $key=$value")
    }

    private fun Properties.requiredLong(key: String): Long =
        required(key).toLongOrNull()
            ?: throw IllegalArgumentException("Invalid transaction manifest integer $key")

    private enum class Stage {
        PREPARED,
        PAIR_INSTALLED,
    }

    private class InvalidTransactionManifestException(cause: Exception) : IOException(
        "Invalid asset transaction manifest; evidence preserved",
        cause,
    )

    private class InvalidDeletionManifestException(cause: Exception) : IOException(
        "Invalid asset deletion manifest; evidence preserved",
        cause,
    )

    private data class AssetTransaction(
        val transactionId: String,
        val stage: Stage,
        val destinationName: String,
        val versionDestinationName: String,
        val stagedAssetName: String,
        val stagedVersionName: String,
        val assetBackupName: String,
        val versionBackupName: String,
        val assetExisted: Boolean,
        val versionExisted: Boolean,
        val assetSize: Long,
        val assetSha256: String,
        val versionSize: Long,
        val versionSha256: String,
        val originalAssetSize: Long,
        val originalAssetSha256: String,
        val originalVersionSize: Long,
        val originalVersionSha256: String,
    ) {
        fun destination(directory: File) = File(directory, destinationName)
        fun versionDestination(directory: File) = File(directory, versionDestinationName)
        fun stagedAsset(directory: File) = File(directory, stagedAssetName)
        fun stagedVersion(directory: File) = File(directory, stagedVersionName)
        fun assetBackup(directory: File) = File(directory, assetBackupName)
        fun versionBackup(directory: File) = File(directory, versionBackupName)

        fun toProperties() = Properties().apply {
            setProperty("formatVersion", TRANSACTION_FORMAT_VERSION)
            setProperty("transactionId", transactionId)
            setProperty("stage", stage.name)
            setProperty("destination", destinationName)
            setProperty("versionDestination", versionDestinationName)
            setProperty("stagedAsset", stagedAssetName)
            setProperty("stagedVersion", stagedVersionName)
            setProperty("assetBackup", assetBackupName)
            setProperty("versionBackup", versionBackupName)
            setProperty("assetExisted", assetExisted.toString())
            setProperty("versionExisted", versionExisted.toString())
            setProperty("assetSize", assetSize.toString())
            setProperty("assetSha256", assetSha256)
            setProperty("versionSize", versionSize.toString())
            setProperty("versionSha256", versionSha256)
            setProperty("originalAssetSize", originalAssetSize.toString())
            setProperty("originalAssetSha256", originalAssetSha256)
            setProperty("originalVersionSize", originalVersionSize.toString())
            setProperty("originalVersionSha256", originalVersionSha256)
            setProperty("checksum", checksum())
        }

        fun checksum(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val values = listOf(
                TRANSACTION_FORMAT_VERSION,
                transactionId,
                stage.name,
                destinationName,
                versionDestinationName,
                stagedAssetName,
                stagedVersionName,
                assetBackupName,
                versionBackupName,
                assetExisted.toString(),
                versionExisted.toString(),
                assetSize.toString(),
                assetSha256,
                versionSize.toString(),
                versionSha256,
                originalAssetSize.toString(),
                originalAssetSha256,
                originalVersionSize.toString(),
                originalVersionSha256,
            )
            values.forEach { value ->
                digest.update(value.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().toHex()
        }
    }

    private data class AssetDeletionTransaction(
        val transactionId: String,
        val destinationName: String,
        val versionDestinationName: String,
    ) {
        fun destination(directory: File) = File(directory, destinationName)
        fun versionDestination(directory: File) = File(directory, versionDestinationName)

        fun toProperties() = Properties().apply {
            setProperty("formatVersion", DELETE_TRANSACTION_FORMAT_VERSION)
            setProperty("transactionId", transactionId)
            setProperty("destination", destinationName)
            setProperty("versionDestination", versionDestinationName)
            setProperty("checksum", checksum())
        }

        fun checksum(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(
                DELETE_TRANSACTION_FORMAT_VERSION,
                transactionId,
                destinationName,
                versionDestinationName,
            ).forEach { value ->
                digest.update(value.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().toHex()
        }
    }

    private val MANIFEST_KEYS = setOf(
        "formatVersion",
        "transactionId",
        "stage",
        "destination",
        "versionDestination",
        "stagedAsset",
        "stagedVersion",
        "assetBackup",
        "versionBackup",
        "assetExisted",
        "versionExisted",
        "assetSize",
        "assetSha256",
        "versionSize",
        "versionSha256",
        "originalAssetSize",
        "originalAssetSha256",
        "originalVersionSize",
        "originalVersionSha256",
    )

    private val DELETE_MANIFEST_KEYS = setOf(
        "formatVersion",
        "transactionId",
        "destination",
        "versionDestination",
    )

    private fun requireRegularFileNoFollow(file: File, description: String) {
        if (pathKindNoFollow(file) != PathKind.REGULAR_FILE) {
            throw IOException("$description is missing or is not a regular file")
        }
    }

    /** Returns true when the file exists and is a regular file, false when it is absent. */
    private fun requireAbsentOrRegularFile(file: File, description: String): Boolean =
        when (pathKindNoFollow(file)) {
            PathKind.ABSENT -> false
            PathKind.REGULAR_FILE -> true
            PathKind.OTHER -> throw IOException("$description is not a regular file")
        }

    private fun requireAbsentFile(file: File, description: String) {
        if (pathKindNoFollow(file) != PathKind.ABSENT) {
            throw IOException("$description already exists or is not a regular file")
        }
    }

    @SuppressLint("NewApi")
    private fun pathKindNoFollow(file: File): PathKind {
        if (isAndroidRuntime) {
            return try {
                val stat = Os.lstat(file.absolutePath)
                if (OsConstants.S_ISREG(stat.st_mode)) {
                    PathKind.REGULAR_FILE
                } else {
                    PathKind.OTHER
                }
            } catch (failure: ErrnoException) {
                if (failure.errno == OsConstants.ENOENT) {
                    PathKind.ABSENT
                } else {
                    throw IOException("Failed to inspect ${file.name} without following links", failure)
                }
            }
        }
        // Local JVM tests need NIO's no-follow semantics. Android always returns above, including
        // API 23-25 where java.nio.file is absent; keeping the calls in Api26Impl also prevents
        // class verification and R8 inlining from exposing those references on older devices.
        return Api26Impl.pathKindNoFollow(file)
    }

    private fun moveRegularFile(
        directory: File,
        source: File,
        destination: File,
        move: (source: File, destination: File) -> Unit,
    ) {
        requireRegularFileNoFollow(source, "Asset transaction source ${source.name}")
        requireAbsentOrRegularFile(destination, "Asset transaction target ${destination.name}")
        move(source, destination)
        syncDirectory(directory)
    }

    @SuppressLint("NewApi")
    private fun syncDirectory(directory: File) {
        if (isAndroidRuntime) {
            syncAndroidDirectory(directory)
            return
        }
        Api26Impl.syncDirectory(directory)
    }

    private fun syncAndroidDirectory(directory: File) {
        var descriptor: FileDescriptor? = null
        try {
            descriptor = Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY,
                0,
            )
            Os.fsync(descriptor)
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.EINVAL) {
                throw IOException("Failed to sync asset directory", failure)
            }
            // A small number of Android file systems report EINVAL for directory fsync.
        } finally {
            descriptor?.let { runCatching { Os.close(it) } }
        }
    }

    private fun fingerprint(
        file: File,
        description: String,
        maxBytes: Long = Long.MAX_VALUE,
    ): FileFingerprint {
        requireRegularFileNoFollow(file, "Asset transaction $description")
        val expectedSize = file.length()
        if (expectedSize > maxBytes) {
            throw IOException("Asset transaction $description exceeds $maxBytes bytes")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var actualSize = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (actualSize > Long.MAX_VALUE - read) {
                    throw IOException("Asset transaction $description is too large")
                }
                actualSize += read
                if (actualSize > maxBytes) {
                    throw IOException("Asset transaction $description exceeds $maxBytes bytes")
                }
                digest.update(buffer, 0, read)
            }
        }
        if (actualSize != expectedSize ||
            pathKindNoFollow(file) != PathKind.REGULAR_FILE ||
            file.length() != expectedSize
        ) {
            throw IOException("Asset transaction $description changed while it was read")
        }
        return FileFingerprint(actualSize, digest.digest().toHex())
    }

    private fun verifyFingerprint(
        file: File,
        expected: FileFingerprint,
        description: String,
    ) {
        if (fingerprint(file, description) != expected) {
            throw IOException("Asset transaction $description fingerprint mismatch; evidence preserved")
        }
    }

    private fun isSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun isOriginalFingerprint(existed: Boolean, size: Long, sha256: String): Boolean =
        if (existed) size >= 0L && isSha256(sha256) else size == -1L && sha256.isEmpty()

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    @RequiresApi(26)
    private object Api26Impl {
        @DoNotInline
        fun pathKindNoFollow(file: File): PathKind = try {
            val attributes = Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (attributes.isRegularFile) PathKind.REGULAR_FILE else PathKind.OTHER
        } catch (_: NoSuchFileException) {
            PathKind.ABSENT
        }

        @DoNotInline
        fun syncDirectory(directory: File) {
            try {
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
                    channel.force(true)
                }
            } catch (failure: AccessDeniedException) {
                if (!System.getProperty("os.name").orEmpty().startsWith("Windows", true)) {
                    throw failure
                }
                // Windows does not expose directory handles through FileChannel.
            } catch (_: UnsupportedOperationException) {
                // Some JVM file-system providers do not implement directory fsync.
            }
        }
    }

    private enum class PathKind {
        ABSENT,
        REGULAR_FILE,
        OTHER,
    }

    private data class FileFingerprint(
        val size: Long,
        val sha256: String,
    ) {
        companion object {
            val ABSENT = FileFingerprint(-1L, "")
        }
    }

    /**
     * Recognizes the two database formats consumed by the core. GeoIP acceptance is delegated to
     * the real MaxMind reader exported by libcore instead of trusting the metadata marker bytes.
     */
    fun isRecognizedAsset(
        file: File,
        destinationName: String = file.name,
        validateGeoIp: (File) -> Boolean = { false },
    ): Boolean {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_ASSET_BYTES) return false
        val validGeoIp = { runCatching { validateGeoIp(file) }.getOrDefault(false) }
        return when (destinationName.lowercase()) {
            "geoip.db" -> validGeoIp()
            "geosite.db" -> isValidGeosite(file)
            else -> isValidGeosite(file) || validGeoIp()
        }
    }

    private fun isValidGeosite(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.read() != 0) return@use false

            val codeCount = input.readUVarInt()
            if (codeCount !in 1..MAX_GEOSITE_CODES) return@use false

            val entries = ArrayList<GeositeEntry>(codeCount.toInt())
            val codes = HashSet<String>(codeCount.toInt())
            var totalItems = 0L
            repeat(codeCount.toInt()) {
                val codeLength = input.readUVarInt()
                if (codeLength !in 1..MAX_GEOSITE_STRING_BYTES ||
                    codeLength > input.length() - input.filePointer
                ) return@use false

                val codeBytes = ByteArray(codeLength.toInt())
                input.readFully(codeBytes)
                val code = codeBytes.decodeUtf8Strict() ?: return@use false
                if (code.isBlank() || !codes.add(code)) return@use false

                val index = input.readUVarInt()
                val itemCount = input.readUVarInt()
                if (itemCount > MAX_GEOSITE_ITEMS - totalItems) return@use false
                totalItems += itemCount
                entries += GeositeEntry(index, itemCount)
            }

            val contentStart = input.filePointer
            val contentLength = input.length() - contentStart
            if (contentLength <= 0L || entries.first().index != 0L) return@use false

            entries.forEachIndexed { entryIndex, entry ->
                val nextIndex = entries.getOrNull(entryIndex + 1)?.index ?: contentLength
                if (entry.index < 0L || entry.index > nextIndex || nextIndex > contentLength) {
                    return@use false
                }

                input.seek(contentStart + entry.index)
                val boundary = contentStart + nextIndex
                repeat(entry.itemCount.toInt()) {
                    if (input.filePointer >= boundary) return@use false
                    val type = input.read()
                    if (type !in 0..3) return@use false

                    val valueLength = input.readUVarInt()
                    if (valueLength > MAX_GEOSITE_STRING_BYTES ||
                        valueLength > boundary - input.filePointer
                    ) return@use false
                    input.seek(input.filePointer + valueLength)
                }
                if (input.filePointer != boundary) return@use false
            }
            true
        }
    }.getOrDefault(false)

    private fun RandomAccessFile.readUVarInt(): Long {
        var value = 0L
        var shift = 0
        repeat(10) {
            val next = read()
            if (next < 0) throw IOException("Unexpected end of asset database")
            if (shift >= 63 && next != 0) throw IOException("Invalid asset database integer")
            value = value or ((next and 0x7F).toLong() shl shift)
            if (next and 0x80 == 0) return value
            shift += 7
        }
        throw IOException("Invalid asset database integer")
    }

    private fun ByteArray.decodeUtf8Strict(): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    }.getOrNull()

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        if (size < needle.size) return -1
        for (index in 0..size - needle.size) {
            var matches = true
            for (needleIndex in needle.indices) {
                if (this[index + needleIndex] != needle[needleIndex]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return -1
    }

    private data class GeositeEntry(
        val index: Long,
        val itemCount: Long,
    )
}
