package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.net.toUri
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
import java.util.concurrent.atomic.AtomicBoolean
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

    val importCodeFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) {        runOnDefaultDispatcher {
            try {
                it.forEachTry { uri ->
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(
                                contentResolver, uri
                            )
                        ) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.isMutableRequired = true
                        }
                    } else {
                        @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(
                            contentResolver, uri
                        )
                    }
                    val text = CodeUtils.parseQRCode(bitmap)
                    onMainDispatcher {
                        this@ScannerActivity.onScanResultCallback(text, true)
                    }
                }
                finish()
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    Toast.makeText(app, e.readableMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.action_import_file) {
            importCodeFile.launch("image/*")
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    var finished = AtomicBoolean(false)
    var importedN = AtomicInteger(0)

    /**
     * 相机扫码结果回调（zxing-lite 3.x，[AnalyzeResult] 包装 [Result]）。
     */
    override fun onScanResultCallback(result: AnalyzeResult<Result>) {
        if (finished.getAndSet(true)) return
        finish()
        onScanResultCallback(result.result.text, false)
    }

    /**
     * 统一处理扫码/图片导入结果文本。
     */
    fun onScanResultCallback(text: String?, multi: Boolean) {
        if (!multi && finished.getAndSet(true)) return
        if (!multi) finish()
        runOnDefaultDispatcher {
            try {
                val resultText = text ?: throw Exception("QR code not found")
                val results = RawUpdater.parseRaw(resultText)
                if (!results.isNullOrEmpty()) {
                    val currentGroupId = DataStore.selectedGroupForImport()
                    if (DataStore.selectedGroup != currentGroupId) {
                        DataStore.selectedGroup = currentGroupId
                    }

                    for (profile in results) {
                        ProfileManager.createProfile(currentGroupId, profile)
                        importedN.addAndGet(1)
                    }
                } else {
                    onMainDispatcher {
                        Toast.makeText(app, R.string.action_import_err, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SubscriptionFoundException) {
                startActivity(Intent(this@ScannerActivity, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = e.link.toUri()
                })
            } catch (e: Throwable) {
                Logs.w(e)
                onMainDispatcher {
                    var message = getString(R.string.action_import_err)
                    message += "\n" + e.readableMessage
                    Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
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
