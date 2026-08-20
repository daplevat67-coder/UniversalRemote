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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.universalremote.control.PjLinkController
import com.example.universalremote.control.UpnpController
import com.example.universalremote.control.WakeOnLanController
import com.example.universalremote.discovery.BleDiscovery
import com.example.universalremote.discovery.LanDiscovery
import com.example.universalremote.discovery.NsdDiscovery
import com.example.universalremote.discovery.PjLinkDiscovery
import com.example.universalremote.discovery.SsdpDiscovery
import com.example.universalremote.model.ControlCapability
import com.example.universalremote.model.NearbyDevice
import com.example.universalremote.model.ScanConfig
import com.example.universalremote.network.DeviceAnalyzer
import com.example.universalremote.network.ServiceHealthProbe
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
    private val analyzer = DeviceAnalyzer()
    private val wol = WakeOnLanController()
    private val healthProbe = ServiceHealthProbe()
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
        lan = LanDiscovery(this, ::addDevice) { status -> runOnUiThread { hint.text = status } }
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
        root.addView(text("UNIVERSAL REMOTE • NETWORK LAB", 12f, c("#65E6C4"), true).apply { letterSpacing = .12f })
        root.addView(text("Устройства рядом", 30f, Color.WHITE, true).apply { setPadding(0, dp(5), 0, dp(4)) })
        root.addView(text("BLE + mDNS + SSDP + LAN + TCP services + PJLink", 13f, c("#AABBD4"), false))

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
            text = "НАЙТИ ВСЕ УСТРОЙСТВА"
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
        tools.addView(smallButton("НАСТРОЙКИ СКАНЕРА") { showSettings() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        root.addView(tools)

        search = EditText(this).apply {
            hint = "Поиск: имя, IP, MAC, порт, бренд…"
            setHintTextColor(c("#7185A3"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
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
        hint.text = "сканируем эфир, LAN и службы"
        scanButton.text = "ПОИСК ИДЁТ…"
        acquireMulticast()
        if (prefs.getBoolean("ble", true)) runCatching { ble.start() }
        if (prefs.getBoolean("mdns", true)) runCatching { nsd.start() }
        if (prefs.getBoolean("ssdp", true)) runCatching { ssdp.start() }
        if (prefs.getBoolean("lan", true)) runCatching { lan.start(loadScanConfig()) }
        if (prefs.getBoolean("pjlink", true)) runCatching { pjDiscovery.start() }
        val duration = prefs.getInt("scan_duration", 35).coerceIn(10, 120)
        handler.postDelayed(finishScan, duration * 1000L)
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
        val host = hostOf(incoming.address) ?: incoming.ipAddress
        if (host != null && incoming.protocol.startsWith("LAN")) {
            val richer = devices.values.filter { it.id != incoming.id && !it.protocol.startsWith("LAN") && (it.ipAddress == host || hostOf(it.address) == host) }
            if (richer.isNotEmpty()) {
                richer.forEach { existing -> devices[existing.id] = mergeNetwork(existing, incoming) }
                count.text = devices.size.toString(); hint.text = plural(devices.size); scheduleRender(); return@runOnUiThread
            }
        }

        var candidate = incoming
        if (host != null && !incoming.protocol.startsWith("LAN")) {
            val lanId = "lan:$host"
            devices.remove(lanId)?.let { candidate = mergeNetwork(candidate, it) }
        }
        devices[incoming.id]?.let { candidate = mergeDevice(it, candidate) }
        devices[incoming.id] = candidate
        count.text = devices.size.toString()
        hint.text = plural(devices.size)
        scanButton.text = "↻  ИСКАТЬ ЕЩЁ"
        scheduleRender()
    }

    private fun mergeDevice(old: NearbyDevice, fresh: NearbyDevice): NearbyDevice = fresh.copy(
        verified = old.verified || fresh.verified,
        ipAddress = fresh.ipAddress ?: old.ipAddress,
        macAddress = fresh.macAddress ?: old.macAddress,
        hardwareVendor = fresh.hardwareVendor ?: old.hardwareVendor,
        openPorts = if (fresh.openPorts.isNotEmpty()) fresh.openPorts else old.openPorts,
        osHint = fresh.osHint ?: old.osHint,
        hostname = fresh.hostname ?: old.hostname,
        analysisNote = fresh.analysisNote ?: old.analysisNote,
        securityFindings = if (fresh.securityFindings.isNotEmpty()) fresh.securityFindings else old.securityFindings
    )

    private fun mergeNetwork(primary: NearbyDevice, network: NearbyDevice): NearbyDevice = primary.copy(
        ipAddress = primary.ipAddress ?: network.ipAddress ?: hostOf(network.address),
        macAddress = primary.macAddress ?: network.macAddress,
        hardwareVendor = primary.hardwareVendor ?: network.hardwareVendor,
        brand = primary.brand ?: network.hardwareVendor ?: network.brand,
        openPorts = if (network.openPorts.isNotEmpty()) network.openPorts else primary.openPorts,
        osHint = primary.osHint ?: network.osHint,
        hostname = primary.hostname ?: network.hostname,
        analysisNote = network.analysisNote ?: primary.analysisNote,
        securityFindings = if (network.securityFindings.isNotEmpty()) network.securityFindings else primary.securityFindings
    )

    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        handler.postDelayed({
            renderPending = false
            val q = query.trim().lowercase()
            val filtered = devices.values.asSequence()
                .filter { d -> selectedKinds.isEmpty() || selectedKinds.any { matchesKind(d, it) } }
                .filter { d -> !onlyControllable || d.controllable }
                .filter { d ->
                    if (q.isBlank()) true else listOf(
                        d.name, d.kind, d.brand.orEmpty(), d.hardwareVendor.orEmpty(), d.protocol, d.address,
                        d.ipAddress.orEmpty(), d.macAddress.orEmpty(), d.osHint.orEmpty(),
                        d.openPorts.joinToString { "${it.port} ${it.service} ${it.banner.orEmpty()}" }
                    ).any { q in it.lowercase() }
                }
                .sortedWith(compareBy<NearbyDevice> { it.distanceMeters == null }.thenBy { it.distanceMeters ?: Double.MAX_VALUE }.thenBy { it.name.lowercase() })
                .toList()
            adapter.submitList(filtered)
        }, 140L)
    }

    private fun showFilters() {
        val labels = arrayOf("Телевизоры", "Колонки / аудио", "Свет", "Компьютеры", "Проекторы", "Телефоны", "Принтеры", "Другие")
        val checked = labels.map { it in selectedKinds }.toBooleanArray()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
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
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), dp(8)) }
        scroll.addView(box)
        val sources = linkedMapOf(
            "ble" to "Bluetooth LE",
            "mdns" to "mDNS / Bonjour",
            "ssdp" to "SSDP / UPnP",
            "lan" to "LAN IPv4 + TCP",
            "pjlink" to "PJLink-проекторы"
        )
        val sourceChecks = sources.mapValues { (key, label) -> CheckBox(this).apply { text = label; isChecked = prefs.getBoolean(key, true); box.addView(this) } }
        val portScan = CheckBox(this).apply { text = "Сканировать TCP-порты найденных хостов"; isChecked = prefs.getBoolean("scan_ports", true); box.addView(this) }

        val range = settingField(box, "Диапазон IPv4", prefs.getString("ip_range", "auto") ?: "auto", "auto, 192.168.1.0/24 или 192.168.1.10-192.168.1.200")
        val timeout = settingField(box, "TCP timeout, мс", prefs.getInt("connect_timeout", 220).toString(), "80–2000")
        val bannerTimeout = settingField(box, "Banner timeout, мс", prefs.getInt("banner_timeout", 450).toString(), "100–3000")
        val parallel = settingField(box, "Параллельных задач", prefs.getInt("parallelism", 32).toString(), "4–64")
        val duration = settingField(box, "Длительность общего поиска, сек", prefs.getInt("scan_duration", 35).toString(), "10–120")
        val ports = settingField(box, "TCP-порты (до 64)", prefs.getString("ports", ScanConfig.DEFAULT_PORTS.joinToString(",")) ?: "", "Напр.: 22,80,443,445,3389,4352,9100")

        AlertDialog.Builder(this)
            .setTitle("Настройки сканера")
            .setMessage("LAN-скан ограничен private/link-local IPv4 и максимум 1024 адресами. Это сохраняет поиск быстрым даже при 100+ устройствах.")
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val parsedPorts = parsePorts(ports.text.toString())
                if (parsedPorts.isEmpty()) { toast("Список портов пуст — оставлены стандартные") }
                prefs.edit().apply {
                    sourceChecks.forEach { (key, check) -> putBoolean(key, check.isChecked) }
                    putBoolean("scan_ports", portScan.isChecked)
                    putString("ip_range", range.text.toString().trim().ifBlank { "auto" })
                    putInt("connect_timeout", timeout.text.toString().toIntOrNull()?.coerceIn(80, 2000) ?: 220)
                    putInt("banner_timeout", bannerTimeout.text.toString().toIntOrNull()?.coerceIn(100, 3000) ?: 450)
                    putInt("parallelism", parallel.text.toString().toIntOrNull()?.coerceIn(4, 64) ?: 32)
                    putInt("scan_duration", duration.text.toString().toIntOrNull()?.coerceIn(10, 120) ?: 35)
                    putString("ports", (parsedPorts.ifEmpty { ScanConfig.DEFAULT_PORTS }).joinToString(","))
                }.apply()
                toast("Настройки сохранены")
            }.show()
    }

    private fun settingField(box: LinearLayout, label: String, value: String, hintValue: String): EditText {
        box.addView(text(label, 12f, c("#AABBD4"), true).apply { setPadding(0, dp(8), 0, dp(3)) })
        return EditText(this).apply {
            setText(value); hint = hintValue; setTextColor(Color.WHITE); setHintTextColor(c("#7185A3")); setSingleLine(true)
            background = rounded(c("#101F36"), 10, c("#294466")); setPadding(dp(12), 0, dp(12), 0)
            box.addView(this, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(4) })
        }
    }

    private fun loadScanConfig(): ScanConfig = ScanConfig(
        range = prefs.getString("ip_range", "auto") ?: "auto",
        connectTimeoutMs = prefs.getInt("connect_timeout", 220).coerceIn(80, 2000),
        bannerTimeoutMs = prefs.getInt("banner_timeout", 450).coerceIn(100, 3000),
        parallelism = prefs.getInt("parallelism", 32).coerceIn(4, 64),
        ports = parsePorts(prefs.getString("ports", ScanConfig.DEFAULT_PORTS.joinToString(",")) ?: "").ifEmpty { ScanConfig.DEFAULT_PORTS },
        scanPorts = prefs.getBoolean("scan_ports", true)
    )

    private fun parsePorts(value: String): List<Int> = value.split(',', ';', ' ', '\n')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 1..65535 }.distinct().take(64)

    private fun showDevice(initial: NearbyDevice) {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(8)) }
        scroll.addView(layout)
        val details = text("", 13f, Color.WHITE, false).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.15f) }
        layout.addView(details)
        val analyzeButton = controlRow("↻ Повторить сетевой анализ") { analyzeDevice(currentDevice(initial.id), details) }
        layout.addView(analyzeButton)
        val wolButton = controlRow("⚡ Wake-on-LAN") { sendWake(currentDevice(initial.id)) }
        layout.addView(wolButton)
        val healthButton = controlRow("🧪 Проверить стабильность службы") { runHealthProbe(currentDevice(initial.id)) }
        layout.addView(healthButton)

        val d0 = currentDevice(initial.id)
        val upnpReady = d0.descriptionUrl != null && d0.protocol.contains("UPnP MediaRenderer")
        val projectorHost = hostOf(d0.address)
        when {
            upnpReady -> layout.addView(controlRow("🎛 UPnP-пульт") { showUpnpRemote(currentDevice(initial.id)) })
            d0.kind.contains("Проектор") && projectorHost != null -> layout.addView(controlRow("🎛 PJLink-пульт") { showPjLinkRemote(currentDevice(initial.id), projectorHost) })
            d0.controllable -> layout.addView(controlRow("Как подключить управление") { showPairingInfo() })
        }

        details.text = deviceDetailsText(d0) + if (hostOf(d0.address) != null || d0.ipAddress != null) "\n\nСетевой анализ запускается…" else ""
        val dialog = AlertDialog.Builder(this).setTitle(d0.name).setView(scroll).setNegativeButton("Закрыть", null).create()
        dialog.setOnShowListener {
            if (hostOf(d0.address) != null || d0.ipAddress != null) analyzeDevice(d0, details)
            else analyzeButton.isEnabled = false
            wolButton.isEnabled = hostOf(d0.address) != null || d0.ipAddress != null
            healthButton.isEnabled = hostOf(d0.address) != null || d0.ipAddress != null
        }
        dialog.show()
    }

    private fun currentDevice(id: String): NearbyDevice = devices[id] ?: NearbyDevice(id, "Устройство", "Неизвестно", "", "")

    private fun analyzeDevice(device: NearbyDevice, details: TextView) {
        val host = device.ipAddress ?: hostOf(device.address)
        if (host == null) { details.text = deviceDetailsText(device) + "\n\nIPv4-адрес для TCP-анализа не определён."; return }
        details.text = deviceDetailsText(device) + "\n\n⏳ Анализ $host: порты, баннеры, MAC, hostname…"
        analyzer.analyze(device.copy(ipAddress = host), loadScanConfig()) { analyzed ->
            runOnUiThread {
                val id = device.id
                val old = devices[id]
                if (old != null) devices[id] = mergeDevice(old, analyzed) else devices[id] = analyzed
                scheduleRender()
                details.text = deviceDetailsText(devices[id] ?: analyzed)
            }
        }
    }

    private fun deviceDetailsText(d: NearbyDevice): String {
        val range = d.distanceMeters?.let { DeviceAdapter.formatDistance(it) + " (${d.signalDbm ?: "?"} dBm)" } ?: "локальная сеть / не применимо"
        val caps = if (d.capabilities.isEmpty()) "не определены" else d.capabilities.joinToString { capabilityName(it) }
        val ports = if (d.openPorts.isEmpty()) "не обнаружены / ещё не проверены" else d.openPorts.joinToString("\n") { p ->
            "• ${p.port}/tcp ${p.service}" + (p.banner?.let { " — ${it.take(180)}" } ?: "")
        }
        return buildString {
            appendLine("Тип: ${d.kind}")
            appendLine("Бренд/протокол: ${d.brand ?: "не определён"}")
            appendLine("Производитель MAC: ${d.hardwareVendor ?: "не определён"}")
            appendLine("Протокол обнаружения: ${d.protocol}")
            appendLine("IPv4: ${d.ipAddress ?: hostOf(d.address) ?: "—"}")
            appendLine("MAC: ${d.macAddress ?: "—"}")
            appendLine("Hostname: ${d.hostname ?: "—"}")
            appendLine("ОС: ${d.osHint ?: "не определена"}")
            appendLine("Расстояние: $range")
            appendLine("Адрес: ${d.address}")
            appendLine("Возможности: $caps")
            appendLine("Статус анализа: ${d.analysisNote ?: "—"}")
            appendLine()
            appendLine("Открытые TCP-службы:")
            append(ports)
            appendLine()
            appendLine()
            appendLine("Наблюдения безопасности:")
            if (d.securityFindings.isEmpty()) append("• явных проблем по выбранным безопасным пробам не выявлено")
            else d.securityFindings.forEach { appendLine("• $it") }
            if (d.verified) append("\n✓ Управление уже подтверждалось в этой сессии")
        }.trim()
    }

    private fun runHealthProbe(d: NearbyDevice) {
        val host = d.ipAddress ?: hostOf(d.address) ?: return toast("IPv4 не определён")
        val port = d.openPorts.firstOrNull()?.port ?: return toast("Сначала дождитесь анализа открытых портов")
        healthProbe.check(host, port, loadScanConfig().connectTimeoutMs) { result ->
            runOnUiThread { toast(result.summary) }
        }
    }

    private fun sendWake(d: NearbyDevice) {
        val mac = d.macAddress
        if (mac != null) {
            wol.wake(mac) { _, message -> runOnUiThread { toast(message) } }
            return
        }
        val field = EditText(this).apply { hint = "AA:BB:CC:DD:EE:FF"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Wake-on-LAN").setMessage("MAC не удалось получить автоматически. Введите MAC сетевой карты устройства.")
            .setView(field).setNegativeButton("Отмена", null).setPositiveButton("Отправить") { _, _ ->
                wol.wake(field.text.toString()) { _, message -> runOnUiThread { toast(message) } }
            }.show()
    }

    private fun showPairingInfo() {
        AlertDialog.Builder(this).setTitle("Нужна штатная авторизация")
            .setMessage("Устройство публикует функции управления, но протокол требует PIN, сертификат, pairing-токен или приложение производителя. Поддерживаемые стандартные адаптеры работают только через штатную авторизацию.")
            .setPositiveButton("Понятно", null).show()
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
        val password = EditText(this).apply { hint = "Пароль PJLink (если требуется)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(password)
        layout.addView(controlRow("Включить проектор") { sendPjLink(d, password, host) { pw, cb -> pjlink.power(host, true, pw, cb) } })
        layout.addView(controlRow("Выключить проектор") { sendPjLink(d, password, host) { pw, cb -> pjlink.power(host, false, pw, cb) } })
        layout.addView(controlRow("Громкость −") { sendPjLink(d, password, host) { pw, cb -> pjlink.volume(host, false, pw, cb) } })
        layout.addView(controlRow("Громкость +") { sendPjLink(d, password, host) { pw, cb -> pjlink.volume(host, true, pw, cb) } })
        layout.addView(controlRow("Mute") { sendPjLink(d, password, host) { pw, cb -> pjlink.mute(host, true, pw, cb) } })
        layout.addView(controlRow("Unmute") { sendPjLink(d, password, host) { pw, cb -> pjlink.mute(host, false, pw, cb) } })
        AlertDialog.Builder(this).setTitle("PJLink • $host").setMessage("Пароль используется только для текущей команды и не сохраняется на диск.")
            .setView(layout).setNegativeButton("Закрыть", null).show()
    }

    private fun sendPjLink(d: NearbyDevice, passwordField: EditText, host: String, action: (CharArray?, (PjLinkController.Result) -> Unit) -> Unit) {
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
        "Компьютеры" -> d.kind.contains("Компьютер") || d.kind.contains("сервер")
        "Проекторы" -> d.kind.contains("Проектор")
        "Телефоны" -> d.kind.contains("Телефон")
        "Принтеры" -> d.kind.contains("Принтер")
        "Другие" -> listOf("ТВ", "Телевизор", "Колонка", "Аудио", "Наушники", "Лампа", "свет", "Компьютер", "сервер", "Проектор", "Телефон", "Принтер").none { d.kind.contains(it) }
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

    private fun controlRow(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
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
        stopScan(); upnp.close(); pjlink.close(); analyzer.close(); wol.close(); healthProbe.close()
        super.onDestroy()
    }
}
