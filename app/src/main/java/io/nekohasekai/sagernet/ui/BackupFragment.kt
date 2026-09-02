package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Parcel
import android.os.Parcelable
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    companion object {
        private const val BACKUP_FORMAT = "nekobox-backup"
        private const val CURRENT_BACKUP_VERSION = 2
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PBKDF2_SALT_BYTES = 16
        private const val GCM_TAG_BITS = 128
        private const val STATE_PENDING_EXPORT = "backup.pendingExport"
        private const val PENDING_EXPORT_PREFIX = ".nekobox-backup-export-"
        private const val PENDING_EXPORT_SUFFIX = ".json"
        private val BACKUP_SECTIONS = listOf("profiles", "groups", "rules", "settings")
    }

    override fun name0() = app.getString(R.string.backup)

    var content = ""
    private var passwordDialog: AlertDialog? = null
    private var pendingExportFileName: String? = null
    private val exportSettings =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
            val stagedExport = consumePendingExportFile()
            if (data == null) {
                stagedExport?.delete()
                return@registerForActivityResult
            }
            runOnIoDispatcher {
                try {
                    val source = stagedExport?.takeIf { it.isFile }
                        ?: throw IOException("Pending backup export is missing")
                    source.inputStream().buffered().use { input ->
                        app.contentResolver.openOutputStream(data, "w")?.buffered()?.use { output ->
                            input.copyTo(output)
                        } ?: throw IOException("Failed to open backup destination")
                    }
                    val destination = exportDestinationLabel(data)
                    onMainDispatcher {
                        if (!isAdded || this@BackupFragment.view == null) {
                            return@onMainDispatcher
                        }
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.action_export_msg)
                            .setMessage(
                                getString(R.string.action_export_msg_with_path, destination)
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        if (isAdded && this@BackupFragment.view != null) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                } finally {
                    stagedExport?.delete()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportFileName = savedInstanceState
            ?.getString(STATE_PENDING_EXPORT)
            ?.takeIf(::isValidPendingExportName)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingExportFileName?.let { outState.putString(STATE_PENDING_EXPORT, it) }
        super.onSaveInstanceState(outState)
    }

    private fun isValidPendingExportName(name: String): Boolean =
        File(name).name == name &&
                name.startsWith(PENDING_EXPORT_PREFIX) &&
                name.endsWith(PENDING_EXPORT_SUFFIX)

    private fun pendingExportFile(name: String?): File? = name
        ?.takeIf(::isValidPendingExportName)
        ?.let { File(app.cacheDir, it) }

    private fun consumePendingExportFile(): File? {
        val result = pendingExportFile(pendingExportFileName)
        pendingExportFileName = null
        return result
    }

    private fun stagePendingExport(backupContent: String): File {
        app.cacheDir.mkdirs()
        return File.createTempFile(
            PENDING_EXPORT_PREFIX,
            PENDING_EXPORT_SUFFIX,
            app.cacheDir,
        ).also { staged ->
            try {
                staged.writeText(backupContent)
            } catch (e: Exception) {
                staged.delete()
                throw e
            }
        }
    }

    private fun exportDestinationLabel(uri: Uri): String {
        val path = exportFilesystemPath(uri)
        val displayName = runCatching {
            app.contentResolver.query(uri, null, null, null, null)
                ?.use { cursor ->
                    cursor.moveToFirst()
                    cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                        .let(cursor::getString)
                }
        }.getOrNull()

        return listOfNotNull(path, displayName?.takeIf { it != path }, Uri.decode(uri.toString()))
            .distinct()
            .joinToString("\n")
    }

    private fun exportFilesystemPath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (!DocumentsContract.isDocumentUri(app, uri)) return null

        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: return null
        if (documentId.startsWith("raw:")) return documentId.removePrefix("raw:")

        return when (uri.authority) {
            "com.android.externalstorage.documents" -> {
                val parts = documentId.split(":", limit = 2)
                val storage = parts.getOrNull(0)
                val relativePath = parts.getOrNull(1).orEmpty()
                if (storage.equals("primary", ignoreCase = true)) {
                    File(Environment.getExternalStorageDirectory(), relativePath).absolutePath
                } else {
                    null
                }
            }
            else -> null
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        binding.resetSettings.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
                .show()
        }

        binding.actionExport.setOnClickListener {
            prepareBackup(binding) {
                val backupContent = content
                viewLifecycleOwner.lifecycleScope.launch {
                    var stagedExport: File? = null
                    try {
                        val staged = withContext(NonCancellable + Dispatchers.IO) {
                            stagePendingExport(backupContent).also { stagedExport = it }
                        }
                        consumePendingExportFile()?.delete()
                        pendingExportFileName = staged.name
                        startFilesForResult(
                            exportSettings, "nekobox_backup_${backupTimestamp()}.json"
                        )
                        stagedExport = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logs.w(e)
                        if (isAdded && this@BackupFragment.view != null) {
                            snackbar(e.readableMessage).show()
                        }
                    } finally {
                        stagedExport?.let { abandoned ->
                            if (pendingExportFileName == abandoned.name) {
                                pendingExportFileName = null
                            }
                            withContext(NonCancellable + Dispatchers.IO) {
                                abandoned.delete()
                            }
                        }
                    }
                }
            }
        }

        binding.actionShare.setOnClickListener {
            prepareBackup(binding) {
                if (!isAdded || this@BackupFragment.view == null) return@prepareBackup
                val backupContent = content
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val uri = withContext(Dispatchers.IO) {
                            app.cacheDir.mkdirs()
                            val cacheFile = File(
                                app.cacheDir, "nekobox_backup_${backupTimestamp()}.json"
                            )
                            cacheFile.writeText(backupContent)
                            FileProvider.getUriForFile(
                                app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                            )
                        }
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("application/json")
                                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    .putExtra(Intent.EXTRA_STREAM, uri),
                                app.getString(R.string.abc_shareactionprovider_share_with)
                            )
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logs.w(e)
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }
    }

    private fun Parcelable.toBase64Str(
        section: String,
        index: Int,
        budget: BackupImportBudget,
    ): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            val data = parcel.marshall()
            budget.recordDecodedItem(section, index, data.size)
            return Util.b64EncodeUrlSafe(data).also {
                budget.requireEncodedItem(section, index, it.length)
            }
        } finally {
            parcel.recycle()
        }
    }

    fun doBackup(
        profile: Boolean,
        rule: Boolean,
        setting: Boolean,
        password: CharArray? = null
    ): String {
        val budget = BackupImportBudget()
        val out = JSONObject().apply {
            put("format", BACKUP_FORMAT)
            put("version", CURRENT_BACKUP_VERSION)
            put("createdAt", System.currentTimeMillis())
            if (profile) {
                val profiles = SagerDatabase.proxyDao.getAll()
                budget.requireEntryCount("profiles", profiles.size)
                put("profiles", JSONArray().apply {
                    profiles.forEachIndexed { index, entry ->
                        put(entry.toBase64Str("profiles", index, budget))
                    }
                })

                val groups = SagerDatabase.groupDao.allGroups()
                budget.requireEntryCount("groups", groups.size)
                put("groups", JSONArray().apply {
                    groups.forEachIndexed { index, entry ->
                        put(entry.toBase64Str("groups", index, budget))
                    }
                })
            }
            if (rule) {
                val rules = SagerDatabase.rulesDao.allRules()
                budget.requireEntryCount("rules", rules.size)
                put("rules", JSONArray().apply {
                    rules.forEachIndexed { index, entry ->
                        put(entry.toBase64Str("rules", index, budget))
                    }
                })
            }
            if (setting) {
                val settings = PublicDatabase.kvPairDao.all()
                budget.requireEntryCount("settings", settings.size)
                put("settings", JSONArray().apply {
                    settings.forEachIndexed { index, entry ->
                        put(entry.toBase64Str("settings", index, budget))
                    }
                })
            }
        }
        out.put("checksum", calculateBackupChecksum(out))
        val exported = if (password == null) {
            out.toStringPretty()
        } else {
            encryptBackup(out.toString(), password)
        }
        return exported.requireUtf8SizeAtMost(MAX_IMPORTED_CONFIG_BYTES)
    }

    private fun deriveBackupKey(
        password: CharArray,
        salt: ByteArray,
        algorithm: String
    ): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(algorithm)
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    override fun onDestroyView() {
        passwordDialog?.dismiss()
        passwordDialog = null
        super.onDestroyView()
    }

    private fun encryptBackup(payload: String, password: CharArray): String {
        val salt = ByteArray(PBKDF2_SALT_BYTES)
        SecureRandom().nextBytes(salt)
        val kdf = runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }
            .fold(onSuccess = { "PBKDF2WithHmacSHA256" }, onFailure = { "PBKDF2WithHmacSHA1" })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveBackupKey(password, salt, kdf))
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        return JSONObject().apply {
            put("format", BACKUP_FORMAT)
            put("version", CURRENT_BACKUP_VERSION)
            put("encrypted", true)
            put("algorithm", "AES-256-GCM")
            put("kdf", kdf)
            put("iterations", PBKDF2_ITERATIONS)
            put("salt", Util.b64EncodeUrlSafe(salt))
            put("iv", Util.b64EncodeUrlSafe(cipher.iv))
            put("payload", Util.b64EncodeUrlSafe(encrypted))
        }.toStringPretty()
    }

    private fun decryptBackup(envelope: JSONObject, password: CharArray): JSONObject {
        require(envelope.optBoolean("encrypted")) { "Not an encrypted backup" }
        require(envelope.optString("algorithm") == "AES-256-GCM") { "Unsupported backup encryption" }
        require(envelope.optInt("iterations") == PBKDF2_ITERATIONS) { "Unsupported backup KDF" }
        val kdf = envelope.optString("kdf")
        require(kdf == "PBKDF2WithHmacSHA256" || kdf == "PBKDF2WithHmacSHA1") {
            "Unsupported backup KDF"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveBackupKey(password, Util.b64Decode(envelope.getString("salt")), kdf),
            GCMParameterSpec(GCM_TAG_BITS, Util.b64Decode(envelope.getString("iv")))
        )
        return JSONObject(
            String(
                cipher.doFinal(Util.b64Decode(envelope.getString("payload"))),
                Charsets.UTF_8
            )
        )
    }

    private fun calculateBackupChecksum(content: JSONObject): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BACKUP_SECTIONS.forEach { section ->
            digest.update(section.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            if (content.has(section)) {
                digest.update(content.getJSONArray(section).toString().toByteArray(Charsets.UTF_8))
            }
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null && isAdded && view != null) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val fileName = app.contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.lastOrNull()
            ?.substringAfterLast('/')
            ?.substringAfter(':')
            .orEmpty()

        if (!fileName.endsWith(".json")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        suspend fun invalid() = onMainDispatcher {
            snackbar(getString(R.string.invalid_backup_file)).show()
        }

        var content = try {
            JSONObject((app.contentResolver.openInputStream(file) ?: return).use {
                it.readTextLimited(MAX_IMPORTED_CONFIG_BYTES)
            })
        } catch (e: Exception) {
            Logs.w(e)
            invalid()
            return
        }
        if (content.optBoolean("encrypted")) {
            val password = requestImportPassword() ?: return
            content = try {
                decryptBackup(content, password)
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    snackbar(getString(R.string.backup_password_invalid)).show()
                }
                return
            } finally {
                password.fill('\u0000')
            }
        }
        val version = content.optInt("version", 0)
        if (version !in 1..CURRENT_BACKUP_VERSION ||
            version >= 2 && content.optString("format") != BACKUP_FORMAT ||
            version >= 2 && !MessageDigest.isEqual(
                content.optString("checksum").toByteArray(Charsets.UTF_8),
                calculateBackupChecksum(content).toByteArray(Charsets.UTF_8)
            )
        ) {
            invalid()
            return
        }

        onMainDispatcher {
            val import = LayoutImportBinding.inflate(layoutInflater)
            if (!content.has("profiles")) {
                import.backupConfigurations.isVisible = false
            }
            if (!content.has("rules")) {
                import.backupRules.isVisible = false
            }
            if (!content.has("settings")) {
                import.backupSettings.isVisible = false
            }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                .setView(import.root)
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    val context = app
                    val service = (requireActivity() as? MainActivity)?.connection?.service

                    val binding = LayoutProgressBinding.inflate(layoutInflater)
                    binding.content.text = getString(R.string.backup_importing)
                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(binding.root)
                        .setCancelable(false)
                        .show()
                    runOnDefaultDispatcher {
                        runCatching {
                            check(requestServiceStopAndWait(context, service)) {
                                app.getString(R.string.action_import_err)
                            }
                            finishImport(
                                content,
                                import.backupConfigurations.isChecked,
                                import.backupRules.isChecked,
                                import.backupSettings.isChecked
                            )
                            triggerFullRestart(
                                context,
                                service = service,
                                serviceAlreadyStopped = true,
                            )
                        }.onFailure {
                            Logs.w(it)
                            onMainDispatcher {
                                if (isAdded && this@BackupFragment.view != null) {
                                    alert(it.readableMessage).tryToShow()
                                }
                            }
                        }

                        onMainDispatcher {
                            runCatching { dialog.dismiss() }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private data class ParsedBackup(
        val profiles: List<ProxyEntity>?,
        val groups: List<ProxyGroup>?,
        val rules: List<RuleEntity>?,
        val settings: List<KeyValuePair>?
    )

    private data class SagerImportSnapshot(
        val profiles: List<ProxyEntity>?,
        val groups: List<ProxyGroup>?,
        val rules: List<RuleEntity>?,
    )

    private inline fun <T> decodeArray(
        content: JSONObject,
        section: String,
        budget: BackupImportBudget,
        crossinline creator: (Parcel) -> T
    ): List<T> {
        val source = content.getJSONArray(section)
        budget.requireEntryCount(section, source.length())
        return List(source.length()) { index ->
            val encoded = source.getString(index)
            budget.requireEncodedItem(section, index, encoded.length)
            val data = Util.b64Decode(encoded)
            budget.recordDecodedItem(section, index, data.size)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                creator(parcel)
            } finally {
                parcel.recycle()
            }
        }
    }

    private fun prepareBackup(binding: LayoutBackupBinding, onReady: () -> Unit) {
        val profile = binding.backupConfigurations.isChecked
        val rule = binding.backupRules.isChecked
        val setting = binding.backupSettings.isChecked

        fun generate(password: CharArray?) {
            if (!isAdded || view == null) {
                password?.fill('\u0000')
                return
            }
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                try {
                    content = doBackup(profile, rule, setting, password)
                    withContext(Dispatchers.Main.immediate) {
                        if (isAdded && this@BackupFragment.view != null) onReady()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    withContext(Dispatchers.Main.immediate) {
                        if (isAdded && this@BackupFragment.view != null) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                } finally {
                    password?.fill('\u0000')
                }
            }
        }

        if (binding.backupEncrypt.isChecked) {
            requestPassword(R.string.backup_password, ::generate)
        } else {
            generate(null)
        }
    }

    private fun requestPassword(title: Int, onPassword: (CharArray) -> Unit) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.backup_password)
            setPadding(48, 0, 48, 0)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = input.text?.toString()?.toCharArray() ?: charArrayOf()
                if (password.isEmpty()) {
                    snackbar(getString(R.string.backup_password_required)).show()
                } else {
                    onPassword(password)
                }
            }
            .create()
        passwordDialog?.dismiss()
        passwordDialog = dialog
        dialog.setOnDismissListener {
            if (passwordDialog === dialog) passwordDialog = null
        }
        dialog.show()
    }

    private suspend fun requestImportPassword(): CharArray? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                if (!isAdded || view == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                val input = EditText(requireContext()).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    hint = getString(R.string.backup_password)
                    setPadding(48, 0, 48, 0)
                }
                val dialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.backup_password)
                    .setView(input)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val password = input.text?.toString()?.toCharArray() ?: charArrayOf()
                        if (password.isEmpty()) {
                            snackbar(getString(R.string.backup_password_required)).show()
                            if (continuation.isActive) continuation.resume(null)
                        } else if (continuation.isActive) {
                            continuation.resume(password)
                        }
                    }
                    .create()
                dialog.setOnDismissListener {
                    if (passwordDialog === dialog) passwordDialog = null
                    if (continuation.isActive) continuation.resume(null)
                }
                continuation.invokeOnCancellation {
                    runOnMainDispatcher {
                        dialog.dismiss()
                    }
                }
                passwordDialog?.dismiss()
                passwordDialog = dialog
                dialog.show()
            }
        }

    private fun parseBackup(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ): ParsedBackup {
        val budget = BackupImportBudget()
        val profiles = if (profile && content.has("profiles")) {
            require(content.has("groups")) { "Backup contains profiles without groups" }
            decodeArray(content, "profiles", budget, ProxyEntity.CREATOR::createFromParcel)
        } else null
        val groups = if (profiles != null) {
            decodeArray(content, "groups", budget, ProxyGroup.CREATOR::createFromParcel)
        } else null
        val rules = if (rule && content.has("rules")) {
            decodeArray(content, "rules", budget, ParcelizeBridge::createRule)
        } else null
        val settings = if (setting && content.has("settings")) {
            decodeArray(content, "settings", budget, KeyValuePair.CREATOR::createFromParcel)
        } else null
        return ParsedBackup(profiles, groups, rules, settings)
    }

    suspend fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ) {
        // Decode every selected section before touching either database. A malformed
        // entry therefore leaves the user's existing profiles, rules and settings intact.
        val parsed = parseBackup(content, profile, rule, setting)
        val hasSagerChanges = parsed.profiles != null || parsed.rules != null
        commitCompensatingImport(
            hasPrimaryChanges = hasSagerChanges,
            hasSecondaryChanges = parsed.settings != null,
            capturePrimary = {
                SagerDatabase.instance.withTransaction {
                    SagerImportSnapshot(
                        profiles = if (parsed.profiles != null) SagerDatabase.proxyDao.getAll() else null,
                        groups = if (parsed.profiles != null) SagerDatabase.groupDao.allGroups() else null,
                        rules = if (parsed.rules != null) SagerDatabase.rulesDao.allRules() else null,
                    )
                }
            },
            applyPrimary = { replaceSagerSections(parsed.profiles, parsed.groups, parsed.rules) },
            restorePrimary = { snapshot ->
                replaceSagerSections(snapshot.profiles, snapshot.groups, snapshot.rules)
            },
            applySecondary = {
                PublicDatabase.instance.withTransaction {
                    PublicDatabase.kvPairDao.reset()
                    PublicDatabase.kvPairDao.insert(checkNotNull(parsed.settings))
                }
            },
        )
    }

    private suspend fun replaceSagerSections(
        profiles: List<ProxyEntity>?,
        groups: List<ProxyGroup>?,
        rules: List<RuleEntity>?,
    ) = SagerDatabase.instance.withTransaction {
        profiles?.let {
            SagerDatabase.proxyDao.reset()
            SagerDatabase.proxyDao.insert(it)
            SagerDatabase.groupDao.reset()
            SagerDatabase.groupDao.insert(checkNotNull(groups))
        }
        rules?.let {
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.insert(it)
        }
    }

    private fun backupTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

}
