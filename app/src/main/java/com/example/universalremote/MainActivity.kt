package com.example.universalremote

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.universalremote.discovery.BleDiscovery
import com.example.universalremote.discovery.NsdDiscovery
import com.example.universalremote.discovery.SsdpDiscovery
import com.example.universalremote.model.NearbyDevice

class MainActivity : AppCompatActivity() {
    private val devices = linkedMapOf<String, NearbyDevice>()
    private lateinit var count: TextView
    private lateinit var hint: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var ble: BleDiscovery
    private lateinit var nsd: NsdDiscovery
    private lateinit var ssdp: SsdpDiscovery
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ble = BleDiscovery(this, ::addDevice)
        nsd = NsdDiscovery(this, ::addDevice)
        ssdp = SsdpDiscovery(::addDevice)
        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(32), dp(22), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c("#07111F"), c("#101B35"), c("#132842")))
        }
        root.addView(text("NEARBY", 13f, c("#65E6C4"), true).apply { letterSpacing = .22f })
        root.addView(text("Ваши устройства\nв одном месте", 31f, Color.WHITE, true).apply { setPadding(0, dp(8), 0, dp(8)) })
        root.addView(text("Безопасный поиск по Bluetooth и вашей Wi‑Fi-сети", 14f, c("#AABBD4"), false))

        val radar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = rounded(c("#172946"), 24, c("#294466"))
        }
        count = text("0", 46f, c("#72F1CE"), true).apply { gravity = Gravity.CENTER }
        hint = text("готово к поиску", 13f, c("#AABBD4"), false).apply { gravity = Gravity.CENTER }
        radar.addView(count); radar.addView(hint)
        root.addView(radar, LinearLayout.LayoutParams(-1, dp(126)).apply { setMargins(0, dp(22), 0, dp(14)) })

        scanButton = Button(this).apply {
            text = "◉  НАЙТИ УСТРОЙСТВА"; textSize = 14f
            setTextColor(c("#07111F")); isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD; background = rounded(c("#72F1CE"), 18)
            setOnClickListener { requestAndScan() }
        }
        root.addView(scanButton, LinearLayout.LayoutParams(-1, dp(58)))
        root.addView(text("РЯДОМ", 12f, c("#8194B2"), true).apply { letterSpacing = .16f; setPadding(0, dp(24), 0, dp(10)) })

        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        deviceList.addView(emptyState())
        root.addView(ScrollView(this).apply { addView(deviceList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun emptyState() = text("Здесь появятся телевизоры, лампы,\nпроекторы, телефоны и другая техника", 14f, c("#7185A3"), false).apply {
        gravity = Gravity.CENTER; setPadding(0, dp(28), 0, dp(28))
    }

    private fun requestAndScan() {
        val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startScan() else permissions.launch(needed)
    }

    private fun startScan() {
        devices.clear(); deviceList.removeAllViews(); count.text = "…"; hint.text = "сканируем эфир и сеть"; scanButton.text = "ПОИСК ИДЁТ…"
        runCatching { ble.start() }; runCatching { nsd.stop() }; runCatching { nsd.start() }
        ssdp.stop(); ssdp.start()
    }

    private fun addDevice(device: NearbyDevice) = runOnUiThread {
        devices[device.id] = device
        renderDevices()
        count.text = devices.size.toString(); hint.text = plural(devices.size); scanButton.text = "↻  ИСКАТЬ ЕЩЁ"
    }

    private fun renderDevices() {
        deviceList.removeAllViews()
        devices.values.sortedWith(compareBy<NearbyDevice> { it.distanceMeters == null }.thenBy { it.distanceMeters ?: Double.MAX_VALUE })
            .forEach { deviceList.addView(deviceCard(it), LinearLayout.LayoutParams(-1, dp(92)).apply { setMargins(0, 0, 0, dp(10)) }) }
    }

    private fun deviceCard(d: NearbyDevice): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12)); background = rounded(c("#172946"), 18, c("#294466"))
            setOnClickListener { showDevice(d) }
        }
        val icon = text(iconFor(d.kind), 25f, Color.WHITE, false).apply { gravity = Gravity.CENTER; background = rounded(c("#274164"), 14) }
        row.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)))
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        copy.addView(text(d.name.take(32), 16f, Color.WHITE, true))
        val proximity = d.distanceMeters?.let { "≈ %.1f м".format(it) } ?: "в локальной сети"
        val details = listOfNotNull(d.brand, d.protocol, proximity).joinToString("  •  ")
        copy.addView(text(details, 12f, c("#91A5C2"), false).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(text("›", 30f, c("#72F1CE"), false))
        return row
    }

    private fun showDevice(d: NearbyDevice) {
        val state = if (d.controllable) "Доступно после безопасного сопряжения" else "Нужен модуль производителя"
        val range = d.distanceMeters?.let { "≈ %.1f м (оценка по сигналу ${d.signalDbm} dBm)".format(it) } ?: "Устройство обнаружено в локальной сети"
        AlertDialog.Builder(this).setTitle("${iconFor(d.kind)}  ${d.name}")
            .setMessage("Тип: ${d.kind}\nБренд: ${d.brand ?: "не определён"}\nПротокол: ${d.protocol}\nРасстояние: $range\nАдрес: ${d.address}\n\n$state")
            .setPositiveButton("Понятно", null).show()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun iconFor(kind: String) = when {
        kind.contains("Телевизор") || kind.contains("ТВ") -> "▣"
        kind.contains("Лампа") -> "☼"
        kind.contains("Проектор") -> "◉"
        kind.contains("Телефон") -> "▯"
        kind.contains("Аудио") -> "♫"
        kind.contains("Часы") -> "◷"
        kind.contains("Аксессуар") -> "✣"
        kind.contains("дом") -> "⌂"
        kind.contains("Bluetooth") -> "ᛒ"
        kind.contains("Принтер") -> "▤"
        else -> "◆"
    }
    private fun plural(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "устройство найдено"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "устройства найдено"
        else -> "устройств найдено"
    }
    private fun c(value: String) = Color.parseColor(value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { ble.stop(); nsd.stop(); ssdp.stop(); super.onDestroy() }
}
