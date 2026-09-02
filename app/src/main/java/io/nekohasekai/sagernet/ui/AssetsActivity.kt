package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.system.Os
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutAssetsBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class AssetsActivity : ThemedActivity() {

    lateinit var adapter: AssetAdapter
    lateinit var layout: LayoutAssetsBinding
    lateinit var undoManager: UndoSnackbarManager<File>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = LayoutAssetsBinding.inflate(layoutInflater)
        layout = binding
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_assets)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        adapter = AssetAdapter()
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setOnRefreshListener {
            adapter.reloadAssets()
            binding.refreshLayout.isRefreshing = false
        }
        binding.refreshLayout.setColorSchemeColors(getColorAttr(R.attr.primaryOrTextPrimary))

        undoManager = UndoSnackbarManager(this, adapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.START
        ) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val index = viewHolder.bindingAdapterPosition
                if (index < 2) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                adapter.remove(index)
                undoManager.remove(index to (viewHolder as AssetHolder).file)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

        }).attachToRecyclerView(binding.recyclerView)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    }

    val assetNames = arrayOf("geoip.db", "geosite.db")

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_asset_menu, menu)
        return true
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            val reportedName = queryDisplayName(file)
                ?: file.pathSegments.lastOrNull()
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('\\')
                    ?.substringAfterLast(':')
                ?: ""
            val fileName = runCatching {
                AssetImportPolicy.requireSafeFileName(reportedName)
            }.getOrElse {
                alert(getString(R.string.route_not_asset, reportedName)).tryToShow()
                return@registerForActivityResult
            }
            val assetDirectory = getExternalFilesDir(null) ?: filesDir

            runOnIoDispatcher {
                runCatching {
                    importAsset(file, assetDirectory, fileName)
                }.onSuccess {
                    onMainDispatcher {
                        adapter.reloadAssets()
                    }
                }.onFailure { error ->
                    onMainDispatcher {
                        alert(error.readableMessage).tryToShow()
                    }
                }
            }
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    @Throws(IOException::class)
    private fun importAsset(uri: android.net.Uri, directory: File, fileName: String) {
        val destination = AssetImportPolicy.resolveContainedFile(directory, fileName)
        val versionDestination = AssetImportPolicy.resolveVersionFile(directory, fileName)
        val stagingFile = File.createTempFile(".asset-", ".tmp", directory)
        val stagingVersion = File.createTempFile(".asset-version-", ".tmp", directory)
        var handedToTransaction = false

        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("Failed to open selected asset")
            input.use {
                FileOutputStream(stagingFile).use { output ->
                    AssetImportPolicy.copyLimited(it, output)
                    output.fd.sync()
                }
            }

            if (!isRecognizedAsset(stagingFile, fileName)) {
                throw IOException(getString(R.string.route_not_asset, fileName))
            }

            FileOutputStream(stagingVersion).use { output ->
                output.write("Custom".toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }

            AssetImportPolicy.replaceAssetAndVersion(
                stagingFile,
                stagingVersion,
                destination,
                versionDestination,
                onTransactionPrepared = { handedToTransaction = true },
                move = { source, target ->
                    // Every transaction file shares one directory, making each replacement atomic.
                    Os.rename(source.absolutePath, target.absolutePath)
                },
            )
        } finally {
            if (!handedToTransaction) {
                stagingFile.delete()
                stagingVersion.delete()
            }
        }
    }

    private fun isRecognizedAsset(file: File, destinationName: String): Boolean =
        AssetImportPolicy.isRecognizedAsset(file, destinationName) { candidate ->
            Libcore.validateGeoIP(candidate.absolutePath)
            true
        }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
                return true
            }
        }
        return false
    }

    inner class AssetAdapter : RecyclerView.Adapter<AssetHolder>(),
        UndoSnackbarManager.Interface<File> {

        val assets = ArrayList<File>()

        init {
            reloadAssets()
        }

        fun reloadAssets() {
            val filesDir = getExternalFilesDir(null) ?: filesDir
            val files = filesDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".db") && it.name !in assetNames }
            assets.clear()
            assets.add(File(filesDir, "geoip.db"))
            assets.add(File(filesDir, "geosite.db"))
            if (files != null) assets.addAll(files)

            layout.refreshLayout.post {
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetHolder {
            return AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: AssetHolder, position: Int) {
            holder.bind(assets[position])
        }

        override fun getItemCount(): Int {
            return assets.size
        }

        fun remove(index: Int) {
            assets.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, File>>) {
            for ((index, item) in actions) {
                assets.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, File>>) {
            val groups = actions.map { it.second }.toTypedArray()
            runOnDefaultDispatcher {
                var firstFailure: Throwable? = null
                groups.forEach { asset ->
                    runCatching {
                        val directory = asset.parentFile
                            ?: throw IOException("Asset deletion target has no parent directory")
                        AssetImportPolicy.deleteAssetAndVersion(directory, asset.name) {
                                source, target ->
                            Os.rename(source.absolutePath, target.absolutePath)
                        }
                    }.onFailure { failure ->
                        if (firstFailure == null) firstFailure = failure
                    }
                }
                onMainDispatcher {
                    adapter.reloadAssets()
                    firstFailure?.let { alert(it.readableMessage).tryToShow() }
                }
            }
        }

    }

    val updating = AtomicInteger()

    inner class AssetHolder(val binding: LayoutAssetItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        lateinit var file: File

        fun bind(file: File) {
            this.file = file

            binding.assetName.text = file.name
            val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")

            val localVersion = if (file.isFile) {
                if (versionFile.isFile) {
                    try {
                        versionFile.readText().trim()
                    } catch (e: Throwable) {
                        snackbar(e.readableMessage)
                        "<unknown>"
                    }
                } else {
                    "Unknown-" + DateFormat.getDateFormat(app).format(Date(file.lastModified()))
                }
            } else {
                "<unknown>"
            }

            binding.assetStatus.text = getString(R.string.route_asset_status, localVersion)

            binding.rulesUpdate.isInvisible = file.name !in assetNames
            binding.rulesUpdate.setOnClickListener {
                updating.incrementAndGet()
                layout.refreshLayout.isEnabled = false
                binding.subscriptionUpdateProgress.isInvisible = false
                binding.rulesUpdate.isInvisible = true
                runOnDefaultDispatcher {
                    runCatching {
                        updateAsset(file, versionFile, localVersion)
                    }.onFailure {
                        onMainDispatcher {
                            alert(it.readableMessage).tryToShow()
                        }
                    }

                    onMainDispatcher {
                        binding.rulesUpdate.isInvisible = false
                        binding.subscriptionUpdateProgress.isInvisible = true
                        if (updating.decrementAndGet() == 0) {
                            layout.refreshLayout.isEnabled = true
                        }
                    }
                }
            }

        }

    }

    private val rulesProviders = listOf(
        RuleAssetsProvider(
            "SagerNet/sing-geoip",
            "SagerNet/sing-geosite",
        ),
        RuleAssetsProvider(
            "soffchen/sing-geoip",
            "soffchen/sing-geosite",
        ),
        RuleAssetsProvider(
            "Chocolate4U/Iran-sing-box-rules"
        ),
        RuleAssetsProvider(
            "L11R/antizapret-sing-box-geo"
        ),
    )

    suspend fun updateAsset(file: File, versionFile: File, localVersion: String) {
        val fileName = AssetImportPolicy.requireSafeFileName(file.name)

        val ruleProvider = rulesProviders[DataStore.rulesProvider]
        val repo = ruleProvider.repoByFileName[fileName]

        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            trySocks5(DataStore.mixedPort)
        }

        try {
            var response = client.newRequest().apply {
                setURL("https://api.github.com/repos/$repo/releases/latest")
            }.execute()

            val release = JSONObject(Util.getStringBox(response.contentString))
            val tagName = release.optString("tag_name").trim()
            require(tagName.isNotEmpty() &&
                    tagName.toByteArray(StandardCharsets.UTF_8).size <= 4096 &&
                    tagName.none { it.isISOControl() }) {
                "Release contains an invalid asset version"
            }

            if (tagName == localVersion) {
                onMainDispatcher {
                    snackbar(R.string.route_asset_no_update).show()
                }
                return
            }

            val releaseAssets = release.getJSONArray("assets").filterIsInstance<JSONObject>()
            val assetToDownload = releaseAssets.find { it.getStr("name") == fileName }
                ?: error("File $fileName not found in release ${release["url"]}")
            val browserDownloadUrl = assetToDownload.getStr("browser_download_url")

            response = client.newRequest().apply {
                setURL(browserDownloadUrl)
            }.execute()

            val directory = file.parentFile?.canonicalFile
                ?: throw IOException("Asset destination has no parent directory")
            val destination = AssetImportPolicy.resolveContainedFile(directory, fileName)
            val versionDestination = AssetImportPolicy.resolveVersionFile(directory, fileName)
            require(versionDestination.canonicalFile == versionFile.canonicalFile) {
                "Asset version destination changed"
            }
            val stagingFile = File.createTempFile(".asset-", ".tmp", directory)
            val stagingVersion = File.createTempFile(".asset-version-", ".tmp", directory)
            var handedToTransaction = false

            try {
                response.writeTo(stagingFile.canonicalPath)
                RandomAccessFile(stagingFile, "rw").use { it.fd.sync() }
                if (!isRecognizedAsset(stagingFile, fileName)) {
                    throw IOException(getString(R.string.route_not_asset, fileName))
                }

                FileOutputStream(stagingVersion).use { output ->
                    output.write(tagName.toByteArray(StandardCharsets.UTF_8))
                    output.fd.sync()
                }

                AssetImportPolicy.replaceAssetAndVersion(
                    stagingFile,
                    stagingVersion,
                    destination,
                    versionDestination,
                    onTransactionPrepared = { handedToTransaction = true },
                    move = { source, target ->
                        Os.rename(source.absolutePath, target.absolutePath)
                    },
                )
            } finally {
                // Once publication starts, the transaction policy owns both staging paths so a
                // failed recovery can retain every file referenced by its durable manifest.
                if (!handedToTransaction) {
                    stagingFile.delete()
                    stagingVersion.delete()
                }
            }

            onMainDispatcher {
                adapter.reloadAssets()
                snackbar(R.string.route_asset_updated).show()
            }
        } finally {
            client.close()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()

        if (::adapter.isInitialized) {
            adapter.reloadAssets()
        }
    }

    private data class RuleAssetsProvider(
        val repoByFileName: Map<String, String>
    ) {
        constructor(
            geoipRepo: String,
            geositeRepo: String = geoipRepo,
        ) : this(
            mapOf(
                "geoip.db" to geoipRepo,
                "geosite.db" to geositeRepo,
            )
        )
    }
}
