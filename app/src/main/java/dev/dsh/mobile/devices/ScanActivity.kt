package dev.dsh.mobile.devices
import dev.dsh.mobile.R
import dev.dsh.mobile.native.WorkspaceActivity
import dev.dsh.mobile.core.Host
import dev.dsh.mobile.core.DeviceName
import dev.dsh.mobile.core.HostStore
import dev.dsh.mobile.core.PairClient
import dev.dsh.mobile.core.PinnedSsl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import org.json.JSONObject
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var barcodeView: DecoratedBarcodeView
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        barcodeView = findViewById(R.id.barcode_scanner)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            startScanner()
        }
    }

    private fun startScanner() {
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                onScanned(result.result)
            }

            override fun possibleResultPoints(result: List<ResultPoint>) {}
        })
        barcodeView.resume()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScanner()
        } else {
            Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun onScanned(result: Result) {
        if (handled) return
        handled = true
        barcodeView.pause()

        val text = result.text ?: ""
        var payload: JSONObject? = null
        try {
            payload = JSONObject(text)
        } catch (e: Exception) {
            payload = null
        }

        if (payload != null && payload.optString("type") == "dsh-link") {
            // 兼容 pairingCode / code 两种字段命名
            val code = payload.optString("pairingCode", payload.optString("code")).trim()
            val urlsArr = payload.optJSONArray("urls")
            val urls = (0 until (urlsArr?.length() ?: 0)).map { urlsArr!!.getString(it) }
            val fallbackName = payload.optString("name", "dsh").ifBlank { "dsh" }
            if (code.isEmpty() || urls.isEmpty()) {
                scanFailed("二维码内容不完整")
                return
            }
            val qrFp = payload.optString("certFingerprint").trim()
            executor.execute {
                var lastError = "所有地址都无法连接"
                for (u in urls) {
                    try {
                        val pin = qrFp.takeIf { it.isNotBlank() && PinnedSsl.shouldPin(u) }
                        val r = try {
                            PairClient.pair(u, code, DeviceName.of(this), pin)
                        } catch (e: PinnedSsl.CertChangedException) {
                            if (PinnedSsl.shouldPin(u)) throw e
                            PairClient.pair(u, code, DeviceName.of(this), null)
                        }
                        runOnUiThread {
                            HostStore.upsert(this, Host(fallbackName, r.baseUrl, r.token, r.deviceId, r.certFingerprint))
                            Toast.makeText(this, "已连接 $fallbackName", Toast.LENGTH_SHORT).show()
                            startActivity(android.content.Intent(this@ScanActivity, WorkspaceActivity::class.java).putExtra("hostBaseUrl", r.baseUrl))
                            finish()
                        }
                        return@execute
                    } catch (e: Exception) {
                        val unwrapped = PinnedSsl.unwrap(e)
                        lastError = "$u: ${unwrapped.message ?: unwrapped.javaClass.simpleName}"
                    }
                }
                val msg = lastError
                runOnUiThread { scanFailed(msg) }
            }
        } else {
            scanFailed("不是 dsh 连接二维码")
        }
    }

    private fun scanFailed(message: String) {
        runOnUiThread {
            Toast.makeText(this, "配对失败：$message", Toast.LENGTH_LONG).show()
            handled = false
            barcodeView.resume()
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::barcodeView.isInitialized && !handled) barcodeView.resume()
    }

    override fun onPause() {
        if (this::barcodeView.isInitialized) barcodeView.pause()
        super.onPause()
    }
}
