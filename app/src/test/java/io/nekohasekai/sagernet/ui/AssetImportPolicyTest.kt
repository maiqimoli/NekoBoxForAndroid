package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AssetImportPolicyTest {

    @Test
    fun acceptsSafeBasenames() {
        assertEquals("custom.db", AssetImportPolicy.requireSafeFileName("custom.db"))
        assertEquals("规则集.db", AssetImportPolicy.requireSafeFileName("规则集.db"))
    }

    @Test
    fun rejectsPathsControlCharactersAndInvalidStems() {
        listOf(
            "../evil.db",
            "..\\evil.db",
            "bad\u0000name.db",
            ".db",
        ).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                AssetImportPolicy.requireSafeFileName(name)
            }
        }
    }

    @Test
    fun rejectsFileNamesOverUtf8ByteLimit() {
        val name = "界".repeat(80) + ".db"
        assertTrue(name.length <= AssetImportPolicy.MAX_FILE_NAME_BYTES)
        assertTrue(name.toByteArray(Charsets.UTF_8).size > AssetImportPolicy.MAX_FILE_NAME_BYTES)

        assertThrows(IllegalArgumentException::class.java) {
            AssetImportPolicy.requireSafeFileName(name)
        }
    }

    @Test
    fun resolvesAssetAndVersionInsideDirectory() = withTempDirectory { directory ->
        val asset = AssetImportPolicy.resolveContainedFile(directory, "custom.db")
        val version = AssetImportPolicy.resolveVersionFile(directory, "custom.db")

        assertEquals(directory.canonicalFile, asset.parentFile)
        assertEquals("custom.db", asset.name)
        assertEquals(directory.canonicalFile, version.parentFile)
        assertEquals("custom.version.txt", version.name)
    }

    @Test
    fun copyLimitedAcceptsExactLimitAndRejectsOverflow() {
        val accepted = ByteArrayOutputStream()
        AssetImportPolicy.copyLimited(
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
            accepted,
            maxBytes = 4,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), accepted.toByteArray())

        assertThrows(IOException::class.java) {
            AssetImportPolicy.copyLimited(
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                ByteArrayOutputStream(),
                maxBytes = 4,
            )
        }
    }

    @Test
    fun copyLimitedToleratesAZeroLengthBulkRead() {
        val output = ByteArrayOutputStream()
        AssetImportPolicy.copyLimited(
            ZeroFirstBulkReadInputStream(byteArrayOf(1, 2, 3, 4)),
            output,
            maxBytes = 4,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
    }

    @Test
    fun assetPairReplacementPublishesBothFiles() = withTempDirectory { directory ->
        val destination = File(directory, "custom.db").apply { writeText("old asset") }
        val versionDestination = File(directory, "custom.version.txt").apply {
            writeText("old version")
        }
        val stagedAsset = stagedAsset(directory, "new asset")
        val stagedVersion = stagedVersion(directory, "new version")

        AssetImportPolicy.replaceAssetAndVersion(
            stagedAsset,
            stagedVersion,
            destination,
            versionDestination,
            ::replaceFile,
        )

        assertEquals("new asset", destination.readText())
        assertEquals("new version", versionDestination.readText())
        assertFalse(stagedAsset.exists())
        assertFalse(stagedVersion.exists())
        assertTrue(directory.listFiles().orEmpty().none { "backup-" in it.name })
    }

    @Test
    fun stagingOwnershipTransfersOnlyAfterPreparedMarkerIsDurable() =
        withTempDirectory { directory ->
            val failedStagedAsset = stagedAsset(directory, "failed asset")
            val failedStagedVersion = stagedVersion(directory, "failed version")
            var ownershipTransferred = false

            assertThrows(IOException::class.java) {
                AssetImportPolicy.replaceAssetAndVersion(
                    failedStagedAsset,
                    failedStagedVersion,
                    File(directory, "custom.db"),
                    File(directory, "custom.version.txt"),
                    onTransactionPrepared = { ownershipTransferred = true },
                    move = { _, _ -> throw IOException("manifest publication failed") },
                )
            }
            assertFalse(ownershipTransferred)
            assertTrue(failedStagedAsset.isFile)
            assertTrue(failedStagedVersion.isFile)

            val ownedStagedAsset = stagedAsset(directory, "owned asset")
            val ownedStagedVersion = stagedVersion(directory, "owned version")
            assertThrows(SimulatedProcessExit::class.java) {
                AssetImportPolicy.replaceAssetAndVersion(
                    ownedStagedAsset,
                    ownedStagedVersion,
                    File(directory, "custom.db"),
                    File(directory, "custom.version.txt"),
                    onTransactionPrepared = {
                        assertTrue(File(directory, ".asset-publish.transaction").isFile)
                        ownershipTransferred = true
                        throw SimulatedProcessExit()
                    },
                    move = ::replaceFile,
                )
            }
            assertTrue(ownershipTransferred)

            AssetImportPolicy.recoverPendingTransaction(directory, ::replaceFile)
            assertFalse(ownedStagedAsset.exists())
            assertFalse(ownedStagedVersion.exists())
        }

    @Test
    fun assetPairReplacementRollsBackWhenVersionPublicationFails() =
        withTempDirectory { directory ->
            val destination = File(directory, "custom.db").apply { writeText("old asset") }
            val versionDestination = File(directory, "custom.version.txt").apply {
                writeText("old version")
            }
            val stagedAsset = stagedAsset(directory, "new asset")
            val stagedVersion = stagedVersion(directory, "new version")
            var moveCount = 0

            assertThrows(IOException::class.java) {
                AssetImportPolicy.replaceAssetAndVersion(
                    stagedAsset,
                    stagedVersion,
                    destination,
                    versionDestination,
                ) { source, target ->
                    moveCount += 1
                    if (moveCount == 5) throw IOException("simulated publication failure")
                    replaceFile(source, target)
                }
            }

            assertEquals("old asset", destination.readText())
            assertEquals("old version", versionDestination.readText())
            assertFalse(stagedVersion.exists())
            assertTrue(directory.listFiles().orEmpty().none { "backup-" in it.name })
        }

    @Test
    fun assetPairReplacementPreservesBackupWhenRollbackFails() =
        withTempDirectory { directory ->
            val destination = File(directory, "custom.db").apply { writeText("old asset") }
            val versionDestination = File(directory, "custom.version.txt").apply {
                writeText("old version")
            }
            val stagedAsset = stagedAsset(directory, "new asset")
            val stagedVersion = stagedVersion(directory, "new version")
            var moveCount = 0

            val failure = assertThrows(IOException::class.java) {
                AssetImportPolicy.replaceAssetAndVersion(
                    stagedAsset,
                    stagedVersion,
                    destination,
                    versionDestination,
                ) { source, target ->
                    moveCount += 1
                    if (moveCount == 4) throw IOException("simulated publication failure")
                    if (moveCount == 5) throw IOException("simulated rollback failure")
                    replaceFile(source, target)
                }
            }

            assertEquals("old asset", destination.readText())
            assertFalse(versionDestination.exists())
            assertEquals(1, failure.suppressed.size)
            val preservedBackup = directory.listFiles().orEmpty().single {
                it.name.startsWith(".asset-version-backup-")
            }
            assertEquals("old version", preservedBackup.readText())
        }

    @Test
    fun recoveryKeepsACompletePairAtEveryRenameBoundaryAndIsIdempotent() {
        for (interruptedAfterMove in 1..6) {
            withTempDirectory { directory ->
                val destination = File(directory, "custom.db").apply { writeText("old asset") }
                val versionDestination = File(directory, "custom.version.txt").apply {
                    writeText("old version")
                }
                val stagedAsset = stagedAsset(directory, "new asset")
                val stagedVersion = stagedVersion(directory, "new version")
                var moveCount = 0

                assertThrows(SimulatedProcessExit::class.java) {
                    AssetImportPolicy.replaceAssetAndVersion(
                        stagedAsset,
                        stagedVersion,
                        destination,
                        versionDestination,
                    ) { source, target ->
                        replaceFile(source, target)
                        moveCount += 1
                        if (moveCount == interruptedAfterMove) throw SimulatedProcessExit()
                    }
                }

                AssetImportPolicy.recoverPendingTransaction(directory, ::replaceFile)
                AssetImportPolicy.recoverPendingTransaction(directory, ::replaceFile)

                val committed = interruptedAfterMove == 6
                assertEquals(if (committed) "new asset" else "old asset", destination.readText())
                assertEquals(
                    if (committed) "new version" else "old version",
                    versionDestination.readText(),
                )
                assertFalse(File(directory, ".asset-publish.transaction").exists())
                assertTrue(directory.listFiles().orEmpty().none { "backup-" in it.name })
            }
        }
    }

    @Test
    fun preparedRecoveryAcceptsOriginalWhenOldAndNewFingerprintsMatch() =
        withTempDirectory { directory ->
            val destination = File(directory, "custom.db").apply { writeText("same asset") }
            val versionDestination = File(directory, "custom.version.txt").apply {
                writeText("same version")
            }

            assertThrows(SimulatedProcessExit::class.java) {
                AssetImportPolicy.replaceAssetAndVersion(
                    stagedAsset(directory, "same asset"),
                    stagedVersion(directory, "same version"),
                    destination,
                    versionDestination,
                    onTransactionPrepared = { throw SimulatedProcessExit() },
                    move = ::replaceFile,
                )
            }

            assertTrue(File(directory, ".asset-publish.transaction").isFile)
            assertTrue(directory.listFiles().orEmpty().none { "backup-" in it.name })

            AssetImportPolicy.recoverPendingTransaction(directory, ::replaceFile)

            assertEquals("same asset", destination.readText())
            assertEquals("same version", versionDestination.readText())
            assertFalse(File(directory, ".asset-publish.transaction").exists())
        }

    @Test
    fun preparedRecoveryPreservesTamperedOriginalBackup() = withTempDirectory { directory ->
        val destination = File(directory, "custom.db").apply { writeText("old asset") }
        val versionDestination = File(directory, "custom.version.txt").apply {
            writeText("old version")
        }
        var moveCount = 0

        assertThrows(SimulatedProcessExit::class.java) {
            AssetImportPolicy.replaceAssetAndVersion(
                stagedAsset(directory, "new asset"),
                stagedVersion(directory, "new version"),
                destination,
                versionDestination,
            ) { source, target ->
                replaceFile(source, target)
                moveCount += 1
                if (moveCount == 4) throw SimulatedProcessExit()
            }
        }
        val tamperedBackup = directory.listFiles().orEmpty().single {
            it.name.startsWith(".asset-backup-")
        }
        tamperedBackup.writeText("tampered backup")

        assertThrows(IOException::class.java) {
            AssetImportPolicy.recoverPendingTransaction(directory, ::replaceFile)
        }

        assertEquals("tampered backup", tamperedBackup.readText())
        assertTrue(File(directory, ".asset-publish.transaction").isFile)
        assertEquals("new asset", destination.readText())
        assertEquals("old version", versionDestination.readText())
    }

    @Test
    fun preparedRecoveryPreservesEvidenceWhenNewDestinationLostItsBackup() =
        withTempDirectory { directory ->
            val destination = File(directory, "custom.db").apply { writeText("old asset") }
            val versionDestination = File(directory, "custom.version.txt").apply {
                writeText("old version")
            }
            var moveCount = 0

            assertThrows(SimulatedProcessExit::class.java) {
                AssetImportPolicy.replaceAssetAndVersion(
                    stagedAsset(directory, "new asset"),
                    stagedVersion(directory, "new version"),
                    destination,
                    versionDestination,
                ) { source, target ->
                    replaceFile(source, target)
                    moveCount += 1
                    if (moveCount == 4) throw SimulatedProcessExit()
                }
            }
            val missingBackup = directory.listFiles().orEmpty().single {
                it.name.startsWith(".asset-backup-")
            }
            assertTrue(missingBackup.delete())

            assertThrows(IOException::class.java) {
                AssetImportPolicy.recoverPendingTransaction(directory, ::replaceFile)
            }

            assertEquals("new asset", destination.readText())
            assertEquals("old version", versionDestination.readText())
            assertTrue(File(directory, ".asset-publish.transaction").isFile)
        }

    @Test
    fun damagedPublicationManifestIsQuarantinedAndReported() = withTempDirectory { directory ->
        val manifest = File(directory, ".asset-publish.transaction").apply {
            writeText("not a transaction")
        }
        val orphan = stagedAsset(directory, "orphan").apply {
            setLastModified(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2))
        }
        var reportedEvidence: File? = null
        var reportedFailure: IOException? = null

        AssetImportPolicy.recoverPendingTransaction(
            directory = directory,
            onCorruptManifest = { evidence, failure ->
                reportedEvidence = evidence
                reportedFailure = failure
            },
            move = ::replaceFile,
        )

        assertFalse(manifest.exists())
        val evidence = directory.listFiles().orEmpty().single {
            it.name.startsWith(".asset-publish.transaction.corrupt-")
        }
        assertEquals(evidence, reportedEvidence)
        assertTrue(reportedFailure is IOException)
        assertEquals("not a transaction", evidence.readText())
        assertTrue(orphan.isFile)
    }

    @Test
    fun committedFingerprintDetectsSameLengthTampering() = withTempDirectory { directory ->
        val destination = File(directory, "custom.db").apply { writeText("old-asset") }
        val versionDestination = File(directory, "custom.version.txt").apply {
            writeText("old-version")
        }
        val stagedAsset = stagedAsset(directory, "new-asset")
        val stagedVersion = stagedVersion(directory, "new-version")
        var moveCount = 0

        assertThrows(SimulatedProcessExit::class.java) {
            AssetImportPolicy.replaceAssetAndVersion(
                stagedAsset,
                stagedVersion,
                destination,
                versionDestination,
            ) { source, target ->
                replaceFile(source, target)
                moveCount += 1
                if (moveCount == 6) throw SimulatedProcessExit()
            }
        }
        destination.writeText("bad-asset")

        assertThrows(IOException::class.java) {
            AssetImportPolicy.recoverPendingTransaction(directory, ::replaceFile)
        }
        assertTrue(File(directory, ".asset-publish.transaction").isFile)
    }

    @Test
    fun rejectsDirectoryTargetsAndSymbolicLinksWithoutFollowingThem() =
        withTempDirectory { directory ->
            val destination = File(directory, "custom.db").apply { mkdir() }
            val versionDestination = File(directory, "custom.version.txt")
            val stagedAsset = stagedAsset(directory, "new asset")
            val stagedVersion = stagedVersion(directory, "new version")

            assertThrows(IOException::class.java) {
                AssetImportPolicy.replaceAssetAndVersion(
                    stagedAsset,
                    stagedVersion,
                    destination,
                    versionDestination,
                    ::replaceFile,
                )
            }

            destination.delete()
            val outside = Files.createTempFile("asset-link-target-", ".db").toFile().apply {
                writeText("outside")
            }
            try {
                val linkCreated = runCatching {
                    Files.createSymbolicLink(destination.toPath(), outside.toPath())
                }.isSuccess
                if (linkCreated) {
                    assertThrows(IOException::class.java) {
                        AssetImportPolicy.replaceAssetAndVersion(
                            stagedAsset,
                            stagedVersion,
                            destination,
                            versionDestination,
                            ::replaceFile,
                        )
                    }
                }
            } finally {
                Files.deleteIfExists(destination.toPath())
                outside.delete()
            }
        }

    @Test
    fun committedCleanupFailureIsRetriedWithoutRollingBack() = withTempDirectory { directory ->
        val destination = File(directory, "custom.db").apply { writeText("old asset") }
        val versionDestination = File(directory, "custom.version.txt").apply {
            writeText("old version")
        }
        val stagedAsset = stagedAsset(directory, "new asset")
        val stagedVersion = stagedVersion(directory, "new version")
        var failOneBackupDelete = true

        AssetImportPolicy.replaceAssetAndVersion(
            stagedAsset,
            stagedVersion,
            destination,
            versionDestination,
            ::replaceFile,
        ) { file ->
            if (failOneBackupDelete && file.name.startsWith(".asset-backup-")) {
                failOneBackupDelete = false
                false
            } else {
                file.delete()
            }
        }

        assertEquals("new asset", destination.readText())
        assertEquals("new version", versionDestination.readText())
        assertTrue(File(directory, ".asset-publish.transaction").isFile)

        AssetImportPolicy.recoverPendingTransaction(directory, ::replaceFile)
        assertEquals("new asset", destination.readText())
        assertEquals("new version", versionDestination.readText())
        assertFalse(File(directory, ".asset-publish.transaction").exists())
    }

    @Test
    fun newPublicationDoesNotOverwriteACommittedMarkerWhoseCleanupStillFails() =
        withTempDirectory { directory ->
            val destination = File(directory, "custom.db").apply { writeText("old asset") }
            val versionDestination = File(directory, "custom.version.txt").apply {
                writeText("old version")
            }
            val failBackupDeletion: (File) -> Boolean = { file ->
                if ("backup-" in file.name) false else file.delete()
            }

            AssetImportPolicy.replaceAssetAndVersion(
                stagedAsset(directory, "first asset"),
                stagedVersion(directory, "first version"),
                destination,
                versionDestination,
                ::replaceFile,
                failBackupDeletion,
            )
            val manifest = File(directory, ".asset-publish.transaction")
            val originalManifest = manifest.readBytes()
            val secondAsset = stagedAsset(directory, "second asset")
            val secondVersion = stagedVersion(directory, "second version")

            assertThrows(IOException::class.java) {
                AssetImportPolicy.replaceAssetAndVersion(
                    secondAsset,
                    secondVersion,
                    destination,
                    versionDestination,
                    ::replaceFile,
                    failBackupDeletion,
                )
            }

            assertArrayEquals(originalManifest, manifest.readBytes())
            assertEquals("first asset", destination.readText())
            assertEquals("first version", versionDestination.readText())
            assertTrue(secondAsset.isFile)
            assertTrue(secondVersion.isFile)
        }

    @Test
    fun startupCleanupDeletesOnlyStaleUnownedStagingFiles() = withTempDirectory { directory ->
        val staleAsset = File(directory, ".asset-stale.tmp").apply { writeText("stale") }
        val staleVersion = File(directory, ".asset-version-stale.tmp").apply { writeText("stale") }
        val freshAsset = File(directory, ".asset-fresh.tmp").apply { writeText("fresh") }
        val backup = File(directory, ".asset-backup-evidence.tmp").apply { writeText("backup") }
        val oldTimestamp = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)
        assertTrue(staleAsset.setLastModified(oldTimestamp))
        assertTrue(staleVersion.setLastModified(oldTimestamp))
        assertTrue(backup.setLastModified(oldTimestamp))

        AssetImportPolicy.recoverPendingTransaction(directory, ::replaceFile)

        assertFalse(staleAsset.exists())
        assertFalse(staleVersion.exists())
        assertTrue(freshAsset.isFile)
        assertTrue(backup.isFile)
    }

    @Test
    fun concurrentPairPublicationsNeverMixAssetAndVersion() = withTempDirectory { directory ->
        val destination = File(directory, "custom.db").apply { writeText("old") }
        val versionDestination = File(directory, "custom.version.txt").apply { writeText("old") }
        val stagedPairs = listOf("first", "second").map { value ->
            stagedAsset(directory, value) to stagedVersion(directory, value)
        }
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val threads = stagedPairs.map { (asset, version) ->
            Thread {
                try {
                    start.await()
                    AssetImportPolicy.replaceAssetAndVersion(
                        asset,
                        version,
                        destination,
                        versionDestination,
                        ::replaceFile,
                    )
                } catch (failure: Throwable) {
                    failures += failure
                }
            }.apply { start() }
        }

        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }

        assertTrue(threads.none(Thread::isAlive))
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(destination.readText(), versionDestination.readText())
        assertTrue(destination.readText() in setOf("first", "second"))
    }

    @Test
    fun bundledFirstInstallPublishesAssetAndVersion() = withTempDirectory { directory ->
        val staged = stagedAsset(directory, minimalGeosite())

        val published = AssetImportPolicy.publishBundledAsset(
            directory,
            "geosite.db",
            "42",
            staged,
            useOfficialAssets = false,
            move = ::replaceFile,
        )

        assertTrue(published)
        assertArrayEquals(minimalGeosite(), File(directory, "geosite.db").readBytes())
        assertEquals("42", File(directory, "geosite.version.txt").readText())
        assertFalse(staged.exists())
    }

    @Test
    fun bundledPublicationPreservesCallerStagingWhenPolicySkipsIt() =
        withTempDirectory { directory ->
            val destination = File(directory, "geoip.db").apply { writeText("local") }
            val versionDestination = File(directory, "geoip.version.txt").apply {
                writeText("1")
            }
            val unofficialStaging = stagedAsset(directory, "unofficial")
            val customStaging = stagedAsset(directory, "custom")
            val validatorCalled = AtomicBoolean(false)

            assertFalse(
                AssetImportPolicy.publishBundledAsset(
                    directory,
                    "geoip.db",
                    "2",
                    unofficialStaging,
                    useOfficialAssets = false,
                    validateAsset = { _, _ ->
                        validatorCalled.set(true)
                        true
                    },
                    move = ::replaceFile,
                ),
            )
            assertTrue(unofficialStaging.isFile)
            assertFalse(validatorCalled.get())

            versionDestination.writeText("Custom")
            assertFalse(
                AssetImportPolicy.publishBundledAsset(
                    directory,
                    "geoip.db",
                    "2",
                    customStaging,
                    useOfficialAssets = true,
                    validateAsset = { _, _ ->
                        validatorCalled.set(true)
                        true
                    },
                    move = ::replaceFile,
                ),
            )
            assertTrue(customStaging.isFile)
            assertFalse(validatorCalled.get())
            assertEquals("local", destination.readText())
            assertEquals("Custom", versionDestination.readText())
        }

    @Test
    fun bundledNumericVersionsUpgradeButNeverDowngrade() = withTempDirectory { directory ->
        val destination = File(directory, "geoip.db").apply { writeText("local") }
        val versionDestination = File(directory, "geoip.version.txt").apply { writeText("41") }

        assertTrue(
            publishBundledForTest(directory, "42", stagedAsset(directory, "version-42")),
        )
        assertEquals("version-42", destination.readText())
        assertEquals("42", versionDestination.readText())

        val same = stagedAsset(directory, "same")
        val lower = stagedAsset(directory, "lower")
        assertFalse(publishBundledForTest(directory, "42", same))
        assertFalse(publishBundledForTest(directory, "41", lower))
        assertTrue(same.isFile)
        assertTrue(lower.isFile)

        versionDestination.writeText("18446744073709551614")
        assertTrue(
            publishBundledForTest(
                directory,
                "18446744073709551615",
                stagedAsset(directory, "uint64-max"),
            ),
        )
        assertEquals("uint64-max", destination.readText())
        assertEquals("18446744073709551615", versionDestination.readText())

        val equivalentMaximum = stagedAsset(directory, "leading-zero-version")
        assertFalse(
            publishBundledForTest(
                directory,
                "00018446744073709551615",
                equivalentMaximum,
            ),
        )
        assertTrue(equivalentMaximum.isFile)
    }

    @Test
    fun bundledTextVersionsReplaceOnlyWhenDifferent() = withTempDirectory { directory ->
        val destination = File(directory, "geoip.db").apply { writeText("local") }
        val versionDestination = File(directory, "geoip.version.txt").apply {
            writeText("release-a")
        }
        val same = stagedAsset(directory, "same")

        assertFalse(publishBundledForTest(directory, "release-a", same))
        assertTrue(same.isFile)
        assertTrue(
            publishBundledForTest(
                directory,
                "release-b",
                stagedAsset(directory, "release-b"),
            ),
        )
        assertEquals("release-b", destination.readText())
        assertEquals("release-b", versionDestination.readText())
    }

    @Test
    fun bundledGeoIpDefaultRequiresARealValidator() =
        withTempDirectory { directory ->
            val empty = stagedAsset(directory, byteArrayOf())
            assertThrows(IOException::class.java) {
                AssetImportPolicy.publishBundledAsset(
                    directory,
                    "geoip.db",
                    "1",
                    empty,
                    useOfficialAssets = true,
                    move = ::replaceFile,
                )
            }
            assertTrue(empty.isFile)

            val oversized = stagedAsset(directory, byteArrayOf(1))
            RandomAccessFile(oversized, "rw").use {
                it.setLength(AssetImportPolicy.MAX_ASSET_BYTES + 1L)
            }
            assertThrows(IOException::class.java) {
                AssetImportPolicy.publishBundledAsset(
                    directory,
                    "geoip.db",
                    "1",
                    oversized,
                    useOfficialAssets = true,
                    move = ::replaceFile,
                )
            }
            assertTrue(oversized.isFile)

            val arbitrary = stagedAsset(directory, byteArrayOf(1))
            assertThrows(IOException::class.java) {
                AssetImportPolicy.publishBundledAsset(
                    directory,
                    "geoip.db",
                    "1",
                    arbitrary,
                    useOfficialAssets = true,
                    move = ::replaceFile,
                )
            }
            assertTrue(arbitrary.isFile)

            assertTrue(
                AssetImportPolicy.publishBundledAsset(
                    directory,
                    "geoip.db",
                    "1",
                    arbitrary,
                    useOfficialAssets = true,
                    validateAsset = { _, _ -> true },
                    move = ::replaceFile,
                ),
            )
            assertArrayEquals(byteArrayOf(1), File(directory, "geoip.db").readBytes())
        }

    @Test
    fun bundledPublicationRejectsCrossDirectoryAndNonRegularStaging() =
        withTempDirectory { directory ->
            val otherDirectory = Files.createTempDirectory("asset-staging-outside-").toFile()
            try {
                val crossDirectory = stagedAsset(otherDirectory, "outside")
                assertThrows(IOException::class.java) {
                    publishBundledForTest(directory, "1", crossDirectory)
                }

                val stagingDirectory = File(directory, ".asset-directory.tmp")
                assertTrue(stagingDirectory.mkdir())
                assertThrows(IOException::class.java) {
                    publishBundledForTest(directory, "1", stagingDirectory)
                }

                stagingDirectory.delete()
                val linkTarget = File(directory, ".asset-link-target.tmp").apply {
                    writeText("target")
                }
                val stagingLink = File(directory, ".asset-link.tmp")
                if (runCatching {
                        Files.createSymbolicLink(stagingLink.toPath(), linkTarget.toPath())
                    }.isSuccess
                ) {
                    assertThrows(IOException::class.java) {
                        publishBundledForTest(directory, "1", stagingLink)
                    }
                    assertEquals("target", linkTarget.readText())
                }
            } finally {
                otherDirectory.deleteRecursively()
            }
        }

    @Test
    fun bundledValidatorDetectsSameLengthMutation() = withTempDirectory { directory ->
        val staged = stagedAsset(directory, "before")

        assertThrows(IOException::class.java) {
            AssetImportPolicy.publishBundledAsset(
                directory,
                "geoip.db",
                "1",
                staged,
                useOfficialAssets = true,
                validateAsset = { file, _ ->
                    file.writeText("change")
                    true
                },
                move = ::replaceFile,
            )
        }

        assertEquals("change", staged.readText())
        assertFalse(File(directory, "geoip.db").exists())
    }

    @Test
    fun bundledFailureBeforeDurableManifestCleansOwnedStaging() =
        withTempDirectory { directory ->
            val staged = stagedAsset(directory, "bundled")

            assertThrows(IOException::class.java) {
                AssetImportPolicy.publishBundledAsset(
                    directory,
                    "geoip.db",
                    "1",
                    staged,
                    useOfficialAssets = true,
                    validateAsset = { _, _ -> true },
                ) { source, target ->
                    if (source.name.startsWith(".asset-publish.transaction.tmp-")) {
                        throw IOException("simulated manifest publication failure")
                    }
                    replaceFile(source, target)
                }
            }

            assertFalse(File(directory, "geoip.db").exists())
            assertFalse(File(directory, "geoip.version.txt").exists())
            assertFalse(File(directory, ".asset-publish.transaction").exists())
            assertTrue(
                directory.listFiles().orEmpty().none {
                    it.name.endsWith(".tmp") &&
                        (it.name.startsWith(".asset-") ||
                            it.name.startsWith(".asset-version-"))
                },
            )
        }

    @Test
    fun bundledValidatorRunsInsidePublicationLock() = withTempDirectory { directory ->
        val firstValidatorEntered = CountDownLatch(1)
        val releaseFirstValidator = CountDownLatch(1)
        val secondAttempted = CountDownLatch(1)
        val secondValidatorEntered = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()

        val first = Thread {
            try {
                AssetImportPolicy.publishBundledAsset(
                    directory,
                    "geoip.db",
                    "1",
                    stagedAsset(directory, "first"),
                    useOfficialAssets = true,
                    validateAsset = { _, _ ->
                        firstValidatorEntered.countDown()
                        releaseFirstValidator.await(5, TimeUnit.SECONDS)
                    },
                    move = ::replaceFile,
                )
            } catch (failure: Throwable) {
                failures += failure
            }
        }.apply { start() }

        assertTrue(firstValidatorEntered.await(5, TimeUnit.SECONDS))
        val second = Thread {
            try {
                secondAttempted.countDown()
                AssetImportPolicy.publishBundledAsset(
                    directory,
                    "geosite.db",
                    "1",
                    stagedAsset(directory, "second"),
                    useOfficialAssets = true,
                    validateAsset = { _, _ ->
                        secondValidatorEntered.countDown()
                        true
                    },
                    move = ::replaceFile,
                )
            } catch (failure: Throwable) {
                failures += failure
            }
        }.apply { start() }

        assertTrue(secondAttempted.await(5, TimeUnit.SECONDS))
        val enteredBeforeRelease = secondValidatorEntered.await(200, TimeUnit.MILLISECONDS)
        releaseFirstValidator.countDown()
        first.join(TimeUnit.SECONDS.toMillis(5))
        second.join(TimeUnit.SECONDS.toMillis(5))

        assertFalse(enteredBeforeRelease)
        assertTrue(secondValidatorEntered.await(1, TimeUnit.SECONDS))
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(failures.toString(), failures.isEmpty())
    }

    @Test
    fun bundledProviderEvaluatorRunsInsidePublicationLock() = withTempDirectory { directory ->
        val firstProviderEntered = CountDownLatch(1)
        val releaseFirstProvider = CountDownLatch(1)
        val secondAttempted = CountDownLatch(1)
        val secondProviderEntered = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val firstStaged = stagedAsset(directory, "first")
        val secondStaged = stagedAsset(directory, "second")

        val first = Thread {
            try {
                AssetImportPolicy.publishBundledAsset(
                    directory,
                    "geoip.db",
                    "1",
                    firstStaged,
                    useOfficialAssets = {
                        firstProviderEntered.countDown()
                        releaseFirstProvider.await(5, TimeUnit.SECONDS)
                        true
                    },
                    validateAsset = { _, _ -> true },
                    move = ::replaceFile,
                )
            } catch (failure: Throwable) {
                failures += failure
            }
        }.apply { start() }

        assertTrue(firstProviderEntered.await(5, TimeUnit.SECONDS))
        val second = Thread {
            try {
                secondAttempted.countDown()
                AssetImportPolicy.publishBundledAsset(
                    directory,
                    "geosite.db",
                    "1",
                    secondStaged,
                    useOfficialAssets = {
                        secondProviderEntered.countDown()
                        true
                    },
                    validateAsset = { _, _ -> true },
                    move = ::replaceFile,
                )
            } catch (failure: Throwable) {
                failures += failure
            }
        }.apply { start() }

        assertTrue(secondAttempted.await(5, TimeUnit.SECONDS))
        val enteredBeforeRelease = secondProviderEntered.await(200, TimeUnit.MILLISECONDS)
        releaseFirstProvider.countDown()
        first.join(TimeUnit.SECONDS.toMillis(5))
        second.join(TimeUnit.SECONDS.toMillis(5))

        assertFalse(enteredBeforeRelease)
        assertTrue(secondProviderEntered.await(1, TimeUnit.SECONDS))
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(failures.toString(), failures.isEmpty())
    }

    @Test
    fun deleteAssetAndVersionDeletesTheCompletePair() = withTempDirectory { directory ->
        val destination = File(directory, "custom.db").apply { writeText("asset") }
        val versionDestination = File(directory, "custom.version.txt").apply {
            writeText("version")
        }

        AssetImportPolicy.deleteAssetAndVersion(directory, "custom.db", ::replaceFile)

        assertFalse(destination.exists())
        assertFalse(versionDestination.exists())
    }

    @Test
    fun deletionRecoveryCompletesThePairAfterProcessExit() = withTempDirectory { directory ->
        val destination = File(directory, "custom.db").apply { writeText("asset") }
        val versionDestination = File(directory, "custom.version.txt").apply {
            writeText("version")
        }

        assertThrows(SimulatedProcessExit::class.java) {
            AssetImportPolicy.deleteAssetAndVersion(
                directory,
                "custom.db",
                ::replaceFile,
            ) { file ->
                val deleted = file.delete()
                if (file == destination) throw SimulatedProcessExit()
                deleted
            }
        }

        assertFalse(destination.exists())
        assertTrue(versionDestination.isFile)
        assertTrue(File(directory, ".asset-delete.transaction").isFile)

        AssetImportPolicy.recoverPendingTransaction(directory, ::replaceFile)
        AssetImportPolicy.recoverPendingTransaction(directory, ::replaceFile)

        assertFalse(destination.exists())
        assertFalse(versionDestination.exists())
        assertFalse(File(directory, ".asset-delete.transaction").exists())
    }

    @Test
    fun damagedDeletionManifestIsQuarantinedAndReported() = withTempDirectory { directory ->
        val manifest = File(directory, ".asset-delete.transaction").apply {
            writeText("not a deletion transaction")
        }
        val orphan = stagedAsset(directory, "orphan").apply {
            setLastModified(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2))
        }
        var reportedEvidence: File? = null
        var reportedFailure: IOException? = null

        AssetImportPolicy.recoverPendingTransaction(
            directory = directory,
            onCorruptManifest = { evidence, failure ->
                reportedEvidence = evidence
                reportedFailure = failure
            },
            move = ::replaceFile,
        )

        assertFalse(manifest.exists())
        val evidence = directory.listFiles().orEmpty().single {
            it.name.startsWith(".asset-delete.transaction.corrupt-")
        }
        assertEquals(evidence, reportedEvidence)
        assertTrue(reportedFailure is IOException)
        assertEquals("not a deletion transaction", evidence.readText())
        assertTrue(orphan.isFile)
    }

    @Test
    fun deleteAssetAndVersionRecoversInterruptedPublicationFirst() =
        withTempDirectory { directory ->
            val destination = File(directory, "custom.db").apply { writeText("old asset") }
            val versionDestination = File(directory, "custom.version.txt").apply {
                writeText("old version")
            }
            var moveCount = 0
            assertThrows(SimulatedProcessExit::class.java) {
                AssetImportPolicy.replaceAssetAndVersion(
                    stagedAsset(directory, "new asset"),
                    stagedVersion(directory, "new version"),
                    destination,
                    versionDestination,
                ) { source, target ->
                    replaceFile(source, target)
                    moveCount += 1
                    if (moveCount == 4) throw SimulatedProcessExit()
                }
            }

            AssetImportPolicy.deleteAssetAndVersion(directory, "custom.db", ::replaceFile)

            assertFalse(destination.exists())
            assertFalse(versionDestination.exists())
            assertFalse(File(directory, ".asset-publish.transaction").exists())
            assertTrue(directory.listFiles().orEmpty().none { "backup-" in it.name })
        }

    @Test
    fun deleteAssetAndVersionPreflightsBothTargets() {
        withTempDirectory { directory ->
            val destination = File(directory, "custom.db").apply { mkdir() }
            val versionDestination = File(directory, "custom.version.txt").apply {
                writeText("version")
            }

            assertThrows(IOException::class.java) {
                AssetImportPolicy.deleteAssetAndVersion(directory, "custom.db", ::replaceFile)
            }
            assertTrue(destination.isDirectory)
            assertTrue(versionDestination.isFile)
        }
        withTempDirectory { directory ->
            val destination = File(directory, "custom.db").apply { writeText("asset") }
            val versionDestination = File(directory, "custom.version.txt").apply { mkdir() }

            assertThrows(IOException::class.java) {
                AssetImportPolicy.deleteAssetAndVersion(directory, "custom.db", ::replaceFile)
            }
            assertTrue(destination.isFile)
            assertTrue(versionDestination.isDirectory)
        }
    }

    @Test
    fun deleteAssetAndVersionRejectsSymbolicLinkTargets() = withTempDirectory { directory ->
        val outside = Files.createTempFile("asset-delete-link-target-", ".db").toFile().apply {
            writeText("outside")
        }
        try {
            val destination = File(directory, "custom.db")
            if (runCatching {
                    Files.createSymbolicLink(destination.toPath(), outside.toPath())
                }.isSuccess
            ) {
                assertThrows(IOException::class.java) {
                    AssetImportPolicy.deleteAssetAndVersion(directory, "custom.db", ::replaceFile)
                }
                assertEquals("outside", outside.readText())
                Files.deleteIfExists(destination.toPath())
            }

            File(directory, "custom.db").writeText("asset")
            val versionDestination = File(directory, "custom.version.txt")
            if (runCatching {
                    Files.createSymbolicLink(versionDestination.toPath(), outside.toPath())
                }.isSuccess
            ) {
                assertThrows(IOException::class.java) {
                    AssetImportPolicy.deleteAssetAndVersion(directory, "custom.db", ::replaceFile)
                }
                assertTrue(File(directory, "custom.db").isFile)
                assertEquals("outside", outside.readText())
            }
        } finally {
            Files.deleteIfExists(File(directory, "custom.db").toPath())
            Files.deleteIfExists(File(directory, "custom.version.txt").toPath())
            outside.delete()
        }
    }

    @Test
    fun recognizesGeositeDatabaseForMatchingAndCustomNames() = withTempDirectory { directory ->
        val file = File(directory, "fixture.db").apply { writeBytes(minimalGeosite()) }

        assertTrue(AssetImportPolicy.isRecognizedAsset(file, "geosite.db"))
        assertTrue(AssetImportPolicy.isRecognizedAsset(file, "custom.db"))
        assertFalse(AssetImportPolicy.isRecognizedAsset(file, "geoip.db"))
    }

    @Test
    fun recognizesMaxMindDatabaseForMatchingAndCustomNames() = withTempDirectory { directory ->
        val file = File(directory, "fixture.db").apply { writeBytes(minimalMaxMind()) }

        assertTrue(AssetImportPolicy.isRecognizedAsset(file, "geoip.db") { true })
        assertTrue(AssetImportPolicy.isRecognizedAsset(file, "custom.db") { true })
        assertFalse(AssetImportPolicy.isRecognizedAsset(file, "geosite.db"))
    }

    @Test
    fun rejectsDamagedMismatchedAndTrailingData() = withTempDirectory { directory ->
        val damaged = File(directory, "damaged.db").apply {
            writeBytes(byteArrayOf(0, 1, 4, 't'.code.toByte()))
        }
        val trailing = File(directory, "trailing.db").apply {
            writeBytes(minimalGeosite() + byteArrayOf(0x7F))
        }

        assertFalse(AssetImportPolicy.isRecognizedAsset(damaged, "custom.db"))
        assertFalse(AssetImportPolicy.isRecognizedAsset(trailing, "geosite.db"))
        assertFalse(AssetImportPolicy.isRecognizedAsset(trailing, "geoip.db"))
    }

    @Test
    fun rejectsGeositeItemCountOverflow() = withTempDirectory { directory ->
        val overflowing = File(directory, "overflowing.db").apply {
            writeBytes(geositeWithOverflowingItemCount())
        }

        assertFalse(AssetImportPolicy.isRecognizedAsset(overflowing, "geosite.db"))
    }

    private fun minimalGeosite(): ByteArray = ByteArrayOutputStream().apply {
        write(0) // version
        writeUVarInt(1) // code count
        writeString("test")
        writeUVarInt(0) // first content offset
        writeUVarInt(1) // item count
        write(0) // exact-domain item
        writeString("example.org")
    }.toByteArray()

    private fun minimalMaxMind(): ByteArray = byteArrayOf(
        0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
        'M'.code.toByte(), 'a'.code.toByte(), 'x'.code.toByte(),
        'M'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(),
        'd'.code.toByte(), '.'.code.toByte(), 'c'.code.toByte(),
        'o'.code.toByte(), 'm'.code.toByte(),
    )

    private fun geositeWithOverflowingItemCount(): ByteArray = ByteArrayOutputStream().apply {
        write(0) // version
        writeUVarInt(2) // code count
        writeString("first")
        writeUVarInt(0)
        writeUVarInt(1)
        writeString("second")
        writeUVarInt(0)
        writeUVarInt(Long.MAX_VALUE)
        write(0) // make the content section non-empty if metadata validation regresses
    }.toByteArray()

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeUVarInt(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeUVarInt(initialValue: Long) {
        var value = initialValue
        do {
            var next = (value and 0x7F).toInt()
            value = value ushr 7
            if (value != 0L) next = next or 0x80
            write(next)
        } while (value != 0L)
    }

    private class ZeroFirstBulkReadInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        private var returnZero = true

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (returnZero) {
                returnZero = false
                return 0
            }
            return super.read(buffer, offset, length)
        }
    }

    private class SimulatedProcessExit : Error()

    private fun stagedAsset(directory: File, content: String): File =
        stagedAsset(directory, content.toByteArray())

    private fun stagedAsset(directory: File, content: ByteArray): File =
        File.createTempFile(".asset-", ".tmp", directory).apply { writeBytes(content) }

    private fun stagedVersion(directory: File, content: String): File =
        File.createTempFile(".asset-version-", ".tmp", directory).apply { writeText(content) }

    private fun publishBundledForTest(
        directory: File,
        version: String,
        staged: File,
    ): Boolean = AssetImportPolicy.publishBundledAsset(
        directory,
        "geoip.db",
        version,
        staged,
        useOfficialAssets = true,
        validateAsset = { _, _ -> true },
        move = ::replaceFile,
    )

    private fun replaceFile(source: File, destination: File) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("asset-import-policy-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
