package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.zxing.Result
import com.king.camera.scan.AnalyzeResult
import com.king.camera.scan.CameraScan
import com.king.camera.scan.analyze.Analyzer
import com.king.zxing.BarcodeCameraScanActivity
import com.king.zxing.analyze.QRCodeAnalyzer
import com.king.zxing.util.CodeUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * 二维码扫描页（zxing-lite 3.x 实现）。
 *
 * 基于 [BarcodeCameraScanActivity]（camera-scan 库），相机预览与手电筒由基类管理。
 * 扫码结果经 [RawUpdater.parseRaw] 解析后导入当前分组。
 */
class ScannerActivity : BarcodeCameraScanActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 25) getSystemService<ShortcutManager>()!!.reportShortcutUsed("scan")
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
    }

    override fun getLayoutId(): Int = R.layout.layout_scanner

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.scanner_menu, menu)
        return true
    }

    val importCodeFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        try {
            ScannerImageImportPolicy.requireSelectionCount(uris.size)
        } catch (error: IllegalArgumentException) {
            showImportError(error)
            return@registerForActivityResult
        }
        if (!importSession.tryStart()) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                importSession.runBatch(uris) { uri ->
                    try {
                        val text = withContext(Dispatchers.IO) {
                            val bytes = contentResolver.openInputStream(uri)?.use {
                                it.readBytesLimited(ScannerImageImportPolicy.MAX_IMAGE_BYTES)
                            } ?: throw IllegalArgumentException("Unable to open image")
                            val bitmap = decodeImportedBitmap(bytes)
                            try {
                                CodeUtils.parseQRCode(bitmap)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                        importScanText(text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        showImportError(e)
                    }
                }
            } finally {
                finish()
            }
        }
    }

    private fun decodeImportedBitmap(bytes: ByteArray): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ) { decoder, info, _ ->
                val plan = ScannerImageImportPolicy.createDecodePlan(
                    info.size.width,
                    info.size.height,
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                decoder.setTargetSize(plan.targetWidth, plan.targetHeight)
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val plan = ScannerImageImportPolicy.createDecodePlan(
            bounds.outWidth,
            bounds.outHeight,
        )
        val options = BitmapFactory.Options().apply {
            inSampleSize = plan.sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("Unsupported image")
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.action_import_file) {
            importCodeFile.launch("image/*")
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private val importSession = ScannerImportSession()
    var importedN = AtomicInteger(0)

    /**
     * 相机扫码结果回调（zxing-lite 3.x，[AnalyzeResult] 包装 [Result]）。
     */
    override fun onScanResultCallback(result: AnalyzeResult<Result>) {
        if (!importSession.tryStart()) return
        lifecycleScope.launch {
            try {
                importScanText(result.result.text)
            } finally {
                finish()
            }
        }
    }

    private suspend fun importScanText(text: String?) {
        try {
            val imported = withContext(Dispatchers.IO) {
                val resultText = text ?: throw Exception("QR code not found")
                val results = RawUpdater.parseRaw(resultText)
                if (!results.isNullOrEmpty()) {
                    val currentGroupId = DataStore.selectedGroupForImport()
                    if (DataStore.selectedGroup != currentGroupId) {
                        DataStore.selectedGroup = currentGroupId
                    }

                    for (profile in results) {
                        ProfileManager.createProfile(currentGroupId, profile)
                        importedN.incrementAndGet()
                    }
                    results.size
                } else {
                    0
                }
            }
            if (imported == 0) {
                Toast.makeText(app, R.string.action_import_err, Toast.LENGTH_SHORT).show()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SubscriptionFoundException) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = e.link.toUri()
            })
        } catch (e: Throwable) {
            showImportError(e)
        }
    }

    private fun showImportError(error: Throwable) {
        Logs.w(error)
        val message = getString(R.string.action_import_err) + "\n" + error.readableMessage
        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 初始化 CameraScan 配置。
     */
    override fun initCameraScan(cameraScan: CameraScan<Result>) {
        super.initCameraScan(cameraScan)
        cameraScan.setAnalyzeImage(true)
    }

    /**
     * 创建二维码分析器。
     */
    override fun createAnalyzer(): Analyzer<Result>? = QRCodeAnalyzer()

    override fun onDestroy() {
        super.onDestroy()
        if (importedN.get() > 0) {
            var text = getString(R.string.action_import_msg)
            text += "\n" + importedN.get() + " profile(s)"
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
    }
}
