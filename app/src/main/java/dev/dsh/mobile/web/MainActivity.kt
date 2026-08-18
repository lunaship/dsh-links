package dev.dsh.mobile.web
import dev.dsh.mobile.R
import dev.dsh.mobile.core.Host
import dev.dsh.mobile.core.DeviceName
import dev.dsh.mobile.core.HostStore
import dev.dsh.mobile.core.PairClient
import dev.dsh.mobile.devices.ScanActivity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: HostAdapter
    private val executor = Executors.newSingleThreadExecutor()
    private val statuses = mutableMapOf<String, Pair<Boolean, Long?>>()
    private var firstLoadDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏沉浸：内容延伸到状态栏下方，内边距由布局的 fitsSystemWindows 处理
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        adapter = HostAdapter(
            onOpen = { host -> openWeb(host) },
            onDelete = { host -> confirmRemove(host) },
            onAdd = { showPairPanel() },
            onRetry = { refresh() },
        )

        // Material 窗口分级：< 600dp 紧凑单列（手机），>= 600dp 双列（平板/折叠屏/横屏）
        val spanCount = if (resources.configuration.screenWidthDp >= 600) 2 else 1
        adapter.gridSpan = spanCount

        findViewById<RecyclerView>(R.id.host_list).apply {
            layoutManager = GridLayoutManager(this@MainActivity, spanCount).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int = this@MainActivity.adapter.spanFor(position)
                }
            }
            adapter = this@MainActivity.adapter
        }

        findViewById<ImageButton>(R.id.menu_more).setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menu.add(getString(R.string.menu_manual_add))
                setOnMenuItemClickListener { item ->
                    if (item.title == getString(R.string.menu_manual_add)) showManualAddDialog()
                    true
                }
            }.show()
        }

        findViewById<View>(R.id.method_scan).setOnClickListener {
            hidePairPanel()
            startActivity(Intent(this, ScanActivity::class.java))
        }
        findViewById<View>(R.id.method_manual).setOnClickListener {
            hidePairPanel()
            showManualAddDialog()
        }
        findViewById<View>(R.id.pair_backdrop).setOnClickListener { hidePairPanel() }
        findViewById<ImageButton>(R.id.pair_close).setOnClickListener { hidePairPanel() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val hosts = HostStore.load(this)
        adapter.submit(hosts)
        adapter.setError(null)
        findViewById<TextView>(R.id.device_count).text = getString(R.string.device_count, hosts.size)

        if (hosts.isEmpty()) {
            setLoading(false)
            firstLoadDone = true
            return
        }

        if (!firstLoadDone) setLoading(true)

        val pending = AtomicInteger(hosts.size)
        for (h in hosts) {
            executor.execute {
                val ms = PairClient.health(h.baseUrl)
                runOnUiThread {
                    statuses[h.name] = if (ms != null) true to ms else false to null
                    adapter.setStatus(statuses.toMap())
                    if (pending.decrementAndGet() == 0) {
                        setLoading(false)
                        firstLoadDone = true
                        val allOffline = hosts.all { statuses[it.name]?.first == false }
                        adapter.setError(if (allOffline) getString(R.string.error_all_offline) else null)
                    }
                }
            }
        }
    }

    private fun setLoading(show: Boolean) {
        val loading = findViewById<View>(R.id.loading)
        val spinner = findViewById<ImageView>(R.id.loading_spinner)
        loading.visibility = if (show) View.VISIBLE else View.GONE
        spinner.clearAnimation()
        if (show) spinner.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate))
    }

    private fun confirmRemove(host: Host) {
        AlertDialog.Builder(this)
            .setTitle("移除设备")
            .setMessage("确认移除“" + host.name + "”？这不会影响电脑上的 DeepSeek Harness。")
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ ->
                HostStore.remove(this, host.name)
                statuses.remove(host.name)
                refresh()
                Toast.makeText(this, "已移除 " + host.name, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun openWeb(host: Host) {
        startActivity(
            Intent(this, WebActivity::class.java).apply {
                putExtra("name", host.name)
                putExtra("baseUrl", host.baseUrl)
                putExtra("token", host.token)
            },
        )
    }

    private fun showPairPanel() {
        val backdrop = findViewById<View>(R.id.pair_backdrop)
        val panel = findViewById<View>(R.id.pair_panel)
        backdrop.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        panel.post {
            panel.translationY = panel.height.toFloat()
            backdrop.alpha = 0f
            panel.animate().translationY(0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
            backdrop.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun hidePairPanel() {
        val backdrop = findViewById<View>(R.id.pair_backdrop)
        val panel = findViewById<View>(R.id.pair_panel)
        panel.animate().translationY(panel.height.toFloat()).setDuration(200)
            .withEndAction {
                panel.visibility = View.GONE
                panel.translationY = 0f
            }.start()
        backdrop.animate().alpha(0f).setDuration(200)
            .withEndAction { backdrop.visibility = View.GONE }.start()
    }

    private fun showManualAddDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val nameInput = EditText(this).apply { hint = "名称（如 腾讯云）" }
        val urlInput = EditText(this).apply { hint = "地址（如 http://1.2.3.4:18640）" }
        val codeInput = EditText(this).apply { hint = "配对码（电脑网页面板上显示）" }
        box.addView(nameInput)
        box.addView(urlInput)
        box.addView(codeInput)

        AlertDialog.Builder(this)
            .setTitle("手动添加 dsh")
            .setView(box)
            .setPositiveButton("连接") { _, _ ->
                val baseUrl = urlInput.text.toString().trim()
                val code = codeInput.text.toString().trim()
                val name = nameInput.text.toString().trim().ifEmpty { "dsh" }
                if (baseUrl.isEmpty() || code.isEmpty()) {
                    Toast.makeText(this, "地址和配对码都要填", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                executor.execute {
                    try {
                        val r = PairClient.pair(baseUrl, code, DeviceName.of(this))
                        runOnUiThread {
                            HostStore.upsert(this, Host(name, r.baseUrl, r.token))
                            refresh()
                            Toast.makeText(this, "已连接 $name", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "连接失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
