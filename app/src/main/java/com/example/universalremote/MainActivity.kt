package com.example.universalremote

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.universalremote.control.PjLinkController
import com.example.universalremote.control.UpnpController
import com.example.universalremote.discovery.BleDiscovery
import com.example.universalremote.discovery.LanDiscovery
import com.example.universalremote.discovery.NsdDiscovery
import com.example.universalremote.discovery.PjLinkDiscovery
import com.example.universalremote.discovery.SsdpDiscovery
import com.example.universalremote.model.ControlCapability
import com.example.universalremote.model.NearbyDevice
import com.example.universalremote.ui.DeviceAdapter
import java.net.URI

class MainActivity : AppCompatActivity() {
    private val devices = linkedMapOf<String, NearbyDevice>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var count: TextView
    private lateinit var hint: TextView
    private lateinit var scanButton: Button
    private lateinit var adapter: DeviceAdapter
    private lateinit var search: EditText
    private lateinit var ble: BleDiscovery
    private lateinit var nsd: NsdDiscovery
    private lateinit var ssdp: SsdpDiscovery
    private lateinit var lan: LanDiscovery
    private lateinit var pjDiscovery: PjLinkDiscovery
    private val upnp = UpnpController()
    private val pjlink = PjLinkController()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var renderPending = false
    private var query = ""
    private var selectedKinds = mutableSetOf<String>()
    private var onlyControllable = false

    private val prefs by lazy { getSharedPreferences("scan_settings", MODE_PRIVATE) }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { !it }) toast("Без разрешения Bluetooth часть устройств не будет видна")
        startScan()
    }

    private val finishScan = Runnable {
        stopSources()
        hint.text = plural(devices.size) + " • поиск завершён"
        scanButton.text = "↻  ИСКАТЬ ЕЩЁ"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ble = BleDiscovery(this, ::addDevice)
        nsd = NsdDiscovery(this, ::addDevice)
        ssdp = SsdpDiscovery(::addDevice)
        lan = LanDiscovery(this, ::addDevice)
        pjDiscovery = PjLinkDiscovery(this, ::addDevice)
        adapter = DeviceAdapter(::showDevice)
        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(26), dp(18), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c("#07111F"), c("#101B35"), c("#132842")))
        }
        root.addView(text("UNIVERSAL REMOTE", 12f, c("#65E6C4"), true).apply { letterSpacing = .18f })
        root.addView(text("Устройства рядом", 30f, Color.WHITE, true).apply { setPadding(0, dp(5), 0, dp(4)) })
        root.addView(text("Bluetooth + Wi‑Fi + mDNS + SSDP + локальная сеть", 13f, c("#AABBD4"), false))

        val radar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(c("#172946"), 22, c("#294466"))
        }
        count = text("0", 42f, c("#72F1CE"), true)
        hint = text("готово к поиску", 13f, c("#AABBD4"), false).apply { setPadding(dp(14), 0, 0, 0) }
        radar.addView(count)
        radar.addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(radar, LinearLayout.LayoutParams(-1, dp(86)).apply { setMargins(0, dp(16), 0, dp(10)) })

        scanButton = Button(this).apply {
            text = "НАЙТИ УСТРОЙСТВА"
            textSize = 14f
            setTextColor(c("#07111F"))
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(c("#72F1CE"), 17)
            setOnClickListener { requestAndScan() }
        }
        root.addView(scanButton, LinearLayout.LayoutParams(-1, dp(54)))

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, dp(8)) }
        tools.addView(smallButton("ФИЛЬТРЫ") { showFilters() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        tools.addView(smallButton("НАСТРОЙКИ") { showSettings() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        root.addView(tools)

        search = EditText(this).apply {
            hint = "Поиск по имени, бренду, типу…"
            setHintTextColor(c("#7185A3"))
            setTextColor(Color.WHITE)
            textSize = 14f
            singleLine = true
            setPadding(dp(15), 0, dp(15), 0)
            background = rounded(c("#101F36"), 14, c("#294466"))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString().orEmpty(); scheduleRender() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(8)) })

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true)
            itemAnimator = null
            setPadding(0, dp(2), 0, dp(4))
            clipToPadding = false
        }
        root.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun requestAndScan() {
        val needed = when {
            Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startScan() else permissions.launch(needed)
    }

    private fun startScan() {
        stopScan()
        devices.clear()
        adapter.submitList(emptyList())
        count.text = "…"
        hint.text = "сканируем эфир и локальную сеть"
        scanButton.text = "ПОИСК ИДЁТ…"
        acquireMulticast()
        if (prefs.getBoolean("ble", true)) runCatching { ble.start() }
        if (prefs.getBoolean("mdns", true)) runCatching { nsd.start() }
        if (prefs.getBoolean("ssdp", true)) runCatching { ssdp.start() }
        if (prefs.getBoolean("lan", true)) runCatching { lan.start() }
        if (prefs.getBoolean("pjlink", true)) runCatching { pjDiscovery.start() }
        handler.postDelayed(finishScan, 22_000L)
    }

    private fun stopScan() {
        handler.removeCallbacks(finishScan)
        stopSources()
    }

    private fun stopSources() {
        runCatching { ble.stop() }
        runCatching { nsd.stop() }
        runCatching { ssdp.stop() }
        runCatching { lan.stop() }
        runCatching { pjDiscovery.stop() }
        releaseMulticast()
    }

    private fun addDevice(incoming: NearbyDevice) = runOnUiThread {
        val host = hostOf(incoming.address)
        if (incoming.protocol == "LAN reachability" && host != null && devices.values.any { it.protocol != "LAN reachability" && hostOf(it.address) == host }) return@runOnUiThread
        if (incoming.protocol != "LAN reachability" && host != null) devices.remove("lan:$host")
        val old = devices[incoming.id]
        devices[incoming.id] = incoming.copy(verified = old?.verified ?: false)
        count.text = devices.size.toString()
        hint.text = plural(devices.size)
        scanButton.text = "↻  ИСКАТЬ ЕЩЁ"
        scheduleRender()
    }

    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        handler.postDelayed({
            renderPending = false
            val q = query.trim().lowercase()
            val filtered = devices.values.asSequence()
                .filter { d -> selectedKinds.isEmpty() || selectedKinds.any { matchesKind(d, it) } }
                .filter { d -> !onlyControllable || d.controllable }
                .filter { d -> q.isBlank() || listOf(d.name, d.kind, d.brand.orEmpty(), d.protocol, d.address).any { q in it.lowercase() } }
                .sortedWith(compareBy<NearbyDevice> { it.distanceMeters == null }.thenBy { it.distanceMeters ?: Double.MAX_VALUE }.thenBy { it.name.lowercase() })
                .toList()
            adapter.submitList(filtered)
        }, 140L)
    }

    private fun showFilters() {
        val labels = arrayOf("Телевизоры", "Колонки / аудио", "Свет", "Компьютеры", "Проекторы", "Телефоны", "Принтеры", "Другие")
        val checked = labels.map { it in selectedKinds }.toBooleanArray()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        val controllable = CheckBox(this).apply { text = "Только с доступным управлением"; isChecked = onlyControllable }
        box.addView(controllable)
        AlertDialog.Builder(this)
            .setTitle("Фильтры")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setView(box)
            .setNegativeButton("Сбросить") { _, _ -> selectedKinds.clear(); onlyControllable = false; scheduleRender() }
            .setPositiveButton("Применить") { _, _ ->
                selectedKinds = labels.filterIndexed { i, _ -> checked[i] }.toMutableSet()
                onlyControllable = controllable.isChecked
                scheduleRender()
            }.show()
    }

    private fun showSettings() {
        val labels = arrayOf("Bluetooth LE", "mDNS / Bonjour", "SSDP / UPnP", "Активные хосты локальной сети", "PJLink-проекторы")
        val keys = arrayOf("ble", "mdns", "ssdp", "lan", "pjlink")
        val checked = keys.map { prefs.getBoolean(it, true) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Источники поиска")
            .setMessage("Для 100+ устройств список обновляется пакетно. LAN-поиск ограничен текущей подсетью и не сканирует порты.")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit().apply { keys.forEachIndexed { i, key -> putBoolean(key, checked[i]) } }.apply()
                toast("Настройки сохранены")
            }.show()
    }

    private fun showDevice(d: NearbyDevice) {
        val range = d.distanceMeters?.let { DeviceAdapter.formatDistance(it) + " (${d.signalDbm ?: "?"} dBm)" } ?: "локальная сеть"
        val caps = if (d.capabilities.isEmpty()) "не определены" else d.capabilities.joinToString { capabilityName(it) }
        val message = "Тип: ${d.kind}\nБренд: ${d.brand ?: "не определён"}\nПротокол: ${d.protocol}\nРасстояние: $range\nАдрес: ${d.address}\nВозможности: $caps" +
            if (d.verified) "\n\n✓ Управление уже подтверждалось в этой сессии" else ""

        val builder = AlertDialog.Builder(this).setTitle(d.name).setMessage(message).setNegativeButton("Закрыть", null)
        val upnpReady = d.descriptionUrl != null && d.protocol.contains("UPnP MediaRenderer")
        val projectorHost = hostOf(d.address)
        if (upnpReady) {
            builder.setPositiveButton("ПУЛЬТ") { _, _ -> showUpnpRemote(d) }
        } else if (d.kind.contains("Проектор") && projectorHost != null) {
            builder.setPositiveButton("PJLINK") { _, _ -> showPjLinkRemote(d, projectorHost) }
        } else if (d.controllable) {
            builder.setPositiveButton("КАК ПОДКЛЮЧИТЬ") { _, _ ->
                AlertDialog.Builder(this).setTitle("Нужна штатная авторизация")
                    .setMessage("Устройство найдено и публикует функции управления, но этот протокол требует отдельного безопасного pairing-адаптера (PIN, сертификат, токен или приложение производителя). Обход защиты не используется.")
                    .setPositiveButton("Понятно", null).show()
            }
        }
        builder.show()
    }

    private fun showUpnpRemote(d: NearbyDevice) {
        val url = d.descriptionUrl ?: return
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), 0) }
        layout.addView(controlRow("Громкость −") { runUpnp(d) { cb -> upnp.adjustVolume(url, -5, cb) } })
        layout.addView(controlRow("Громкость +") { runUpnp(d) { cb -> upnp.adjustVolume(url, 5, cb) } })
        layout.addView(controlRow("Mute") { runUpnp(d) { cb -> upnp.setMute(url, true, cb) } })
        layout.addView(controlRow("Unmute") { runUpnp(d) { cb -> upnp.setMute(url, false, cb) } })
        layout.addView(controlRow("▶ Play") { runUpnp(d) { cb -> upnp.media(url, "Play", cb) } })
        layout.addView(controlRow("Ⅱ Pause") { runUpnp(d) { cb -> upnp.media(url, "Pause", cb) } })
        layout.addView(controlRow("■ Stop") { runUpnp(d) { cb -> upnp.media(url, "Stop", cb) } })
        AlertDialog.Builder(this).setTitle("UPnP-пульт • ${d.name}").setView(layout).setNegativeButton("Закрыть", null).show()
    }

    private fun runUpnp(d: NearbyDevice, action: (((UpnpController.Result) -> Unit)) -> Unit) {
        action { result -> runOnUiThread { toast(result.message); if (result.ok) markVerified(d.id) } }
    }

    private fun showPjLinkRemote(d: NearbyDevice, host: String) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        val password = EditText(this).apply {
            hint = "Пароль PJLink (если требуется)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(password)
        layout.addView(controlRow("Включить проектор") { sendPjLink(d, password, host) { pw, cb -> pjlink.power(host, true, pw, cb) } })
        layout.addView(controlRow("Выключить проектор") { sendPjLink(d, password, host) { pw, cb -> pjlink.power(host, false, pw, cb) } })
        layout.addView(controlRow("Громкость −") { sendPjLink(d, password, host) { pw, cb -> pjlink.volume(host, false, pw, cb) } })
        layout.addView(controlRow("Громкость +") { sendPjLink(d, password, host) { pw, cb -> pjlink.volume(host, true, pw, cb) } })
        layout.addView(controlRow("Mute") { sendPjLink(d, password, host) { pw, cb -> pjlink.mute(host, true, pw, cb) } })
        layout.addView(controlRow("Unmute") { sendPjLink(d, password, host) { pw, cb -> pjlink.mute(host, false, pw, cb) } })
        AlertDialog.Builder(this).setTitle("PJLink • $host").setMessage("Поддерживаются PJLink 2.10 (SHA-256) и старый challenge-response. Пароль используется только для текущей команды и не сохраняется на диск.")
            .setView(layout).setNegativeButton("Закрыть", null).show()
    }

    private fun sendPjLink(
        d: NearbyDevice,
        passwordField: EditText,
        host: String,
        action: (CharArray?, (PjLinkController.Result) -> Unit) -> Unit
    ) {
        val raw = passwordField.text?.toString().orEmpty()
        val password = raw.takeIf { it.isNotBlank() }?.toCharArray()
        passwordField.text?.clear()
        action(password) { result -> runOnUiThread { toast(result.message); if (result.ok) markVerified(d.id) } }
    }

    private fun markVerified(id: String) {
        val existing = devices[id] ?: return
        devices[id] = existing.copy(verified = true)
        scheduleRender()
    }

    private fun matchesKind(d: NearbyDevice, label: String): Boolean = when (label) {
        "Телевизоры" -> d.kind.contains("ТВ") || d.kind.contains("Телевизор")
        "Колонки / аудио" -> d.kind.contains("Колонка") || d.kind.contains("Аудио") || d.kind.contains("Наушники") || d.kind.contains("медиаплеер")
        "Свет" -> d.kind.contains("Лампа") || d.kind.contains("свет") || d.kind.contains("дом")
        "Компьютеры" -> d.kind.contains("Компьютер")
        "Проекторы" -> d.kind.contains("Проектор")
        "Телефоны" -> d.kind.contains("Телефон")
        "Принтеры" -> d.kind.contains("Принтер")
        "Другие" -> listOf("ТВ", "Телевизор", "Колонка", "Аудио", "Наушники", "Лампа", "свет", "Компьютер", "Проектор", "Телефон", "Принтер").none { d.kind.contains(it) }
        else -> true
    }

    private fun capabilityName(cap: ControlCapability) = when (cap) {
        ControlCapability.POWER -> "питание"
        ControlCapability.VOLUME -> "громкость"
        ControlCapability.MUTE -> "mute"
        ControlCapability.MEDIA -> "медиа"
        ControlCapability.LIGHT_POWER -> "свет"
        ControlCapability.BRIGHTNESS -> "яркость"
        ControlCapability.COLOR -> "цвет"
    }

    private fun hostOf(address: String): String? = runCatching {
        when {
            address.startsWith("http://") || address.startsWith("https://") -> URI(address).host
            address.startsWith("[") -> address.substringAfter('[').substringBefore(']')
            else -> address.substringBefore(':').takeIf { it.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")) }
        }
    }.getOrNull()

    private fun acquireMulticast() {
        runCatching {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            multicastLock = wifi.createMulticastLock("universal-remote-discovery").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseMulticast() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private fun controlRow(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 12f; setTextColor(Color.WHITE); isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD; background = rounded(c("#172946"), 14, c("#294466")); setOnClickListener { action() }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun plural(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "$n устройство найдено"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "$n устройства найдено"
        else -> "$n устройств найдено"
    }
    private fun c(value: String) = Color.parseColor(value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopScan(); upnp.close(); pjlink.close()
        super.onDestroy()
    }
}
