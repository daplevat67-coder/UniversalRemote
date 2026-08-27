package com.example.universalremote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import com.example.universalremote.control.AndroidTvController
import com.example.universalremote.control.BleGattController
import com.example.universalremote.control.CastV2Controller
import com.example.universalremote.control.CompanionController
import com.example.universalremote.control.HueController
import com.example.universalremote.control.LgWebOsController
import com.example.universalremote.control.PjLinkController
import com.example.universalremote.control.RokuController
import com.example.universalremote.control.SamsungTvController
import com.example.universalremote.control.WledController
import com.example.universalremote.control.YeelightController
import com.example.universalremote.control.UpnpController
import com.example.universalremote.control.WakeOnLanController
import com.example.universalremote.discovery.BleDiscovery
import com.example.universalremote.discovery.BluetoothClassicDiscovery
import com.example.universalremote.discovery.LanDiscovery
import com.example.universalremote.discovery.NsdDiscovery
import com.example.universalremote.discovery.PjLinkDiscovery
import com.example.universalremote.discovery.SsdpDiscovery
import com.example.universalremote.discovery.WifiDiscovery
import com.example.universalremote.discovery.YeelightDiscovery
import com.example.universalremote.model.ControlCapability
import com.example.universalremote.model.NearbyDevice
import com.example.universalremote.model.ScanConfig
import com.example.universalremote.network.DeviceAnalyzer
import com.example.universalremote.network.Ipv4Range
import com.example.universalremote.network.ServiceHealthProbe
import com.example.universalremote.network.WifiNetworkResolver
import com.example.universalremote.ui.DeviceAdapter
import java.net.Inet4Address
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
    private lateinit var classic: BluetoothClassicDiscovery
    private lateinit var nsd: NsdDiscovery
    private lateinit var ssdp: SsdpDiscovery
    private lateinit var lan: LanDiscovery
    private lateinit var pjDiscovery: PjLinkDiscovery
    private lateinit var wifiDiscovery: WifiDiscovery
    private lateinit var yeelightDiscovery: YeelightDiscovery
    private val upnp = UpnpController()
    private val pjlink = PjLinkController()
    private val roku = RokuController()
    private lateinit var samsung: SamsungTvController
    private val wled = WledController()
    private lateinit var cast: CastV2Controller
    private lateinit var companion: CompanionController
    private val yeelight = YeelightController()
    private lateinit var lgWebOs: LgWebOsController
    private lateinit var hue: HueController
    private lateinit var androidTv: AndroidTvController
    private lateinit var bleGatt: BleGattController
    private lateinit var analyzer: DeviceAnalyzer
    private val wol = WakeOnLanController()
    private lateinit var healthProbe: ServiceHealthProbe
    private var multicastLock: WifiManager.MulticastLock? = null
    private var renderPending = false
    private var query = ""
    private var selectedKinds = mutableSetOf<String>()
    private var onlyControllable = false
    private val sourceStatus = linkedMapOf<String, String>()

    private val prefs by lazy { getSharedPreferences("scan_settings", MODE_PRIVATE) }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { !it }) toast("Без Nearby devices/точной геолокации часть Bluetooth и Wi‑Fi эфира не будет видна")
        startScan()
    }

    private val stopLanAfterGrace = Runnable { runCatching { lan.stop() } }

    private val finishScan = Runnable {
        stopShortSources()
        handler.removeCallbacks(stopLanAfterGrace)
        handler.postDelayed(stopLanAfterGrace, 60_000L)
        hint.text = if (devices.isEmpty()) {
            val diag = sourceStatus.entries.joinToString(" • ") { "${it.key}: ${it.value}" }.take(180)
            if (diag.isBlank()) "0 устройств • LAN ещё может завершать neighbor-sweep" else "0 • $diag • LAN ещё проверяется"
        } else plural(devices.size) + " • быстрый поиск завершён, LAN может ещё добавлять устройства"
        scanButton.text = "↻  ИСКАТЬ ЕЩЁ"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ensureDiscoverySourcesEnabled()
        androidTv = AndroidTvController(this)
        bleGatt = BleGattController(this)
        samsung = SamsungTvController(this)
        lgWebOs = LgWebOsController(this)
        hue = HueController(this)
        cast = CastV2Controller(this)
        companion = CompanionController(this)
        analyzer = DeviceAnalyzer(this)
        healthProbe = ServiceHealthProbe(this)
        ble = BleDiscovery(this, ::addDevice) { updateSourceStatus("BLE", it) }
        classic = BluetoothClassicDiscovery(this, ::addDevice) { updateSourceStatus("BT", it) }
        nsd = NsdDiscovery(this, ::addDevice) { updateSourceStatus("mDNS", it) }
        ssdp = SsdpDiscovery(this, ::addDevice) { updateSourceStatus("SSDP", it) }
        lan = LanDiscovery(this, ::addDevice) { status -> updateSourceStatus("LAN", status) }
        pjDiscovery = PjLinkDiscovery(this, ::addDevice) { updateSourceStatus("PJLink", it) }
        wifiDiscovery = WifiDiscovery(this, ::addDevice) { updateSourceStatus("Wi‑Fi", it) }
        yeelightDiscovery = YeelightDiscovery(this, ::addDevice) { updateSourceStatus("Yeelight", it) }
        adapter = DeviceAdapter(::showDevice)
        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(26), dp(18), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c("#07111F"), c("#101B35"), c("#132842")))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(text("UNIVERSAL REMOTE • v${BuildConfig.VERSION_NAME}", 12f, c("#65E6C4"), true).apply { letterSpacing = .12f })
        titleBox.addView(text("Устройства рядом", 30f, Color.WHITE, true).apply { setPadding(0, dp(5), 0, dp(4)) })
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(gearButton { showSettingsHub() }, LinearLayout.LayoutParams(dp(56), dp(52)).apply { marginStart = dp(10) })
        root.addView(header)
        root.addView(text("Автоподключение по штатным LAN API • пароль/PIN используется только там, где протокол его реально поддерживает", 13f, c("#AABBD4"), false))

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
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
        if (needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startScan() else permissions.launch(needed)
    }

    private fun ensureDiscoverySourcesEnabled() {
        val keys = listOf("ble", "classic", "wifi_radio", "mdns", "ssdp", "lan", "pjlink", "yeelight")
        if (keys.all { prefs.contains(it) && !prefs.getBoolean(it, true) }) {
            prefs.edit().apply { keys.forEach { putBoolean(it, true) } }.apply()
        }
    }

    private fun startScan() {
        stopScan()
        devices.clear()
        sourceStatus.clear()
        val lanInfo = WifiNetworkResolver.lanInfo(this)
        sourceStatus["LAN"] = lanInfo?.label ?: "локальная IPv4 сеть не определена"
        adapter.submitList(emptyList())
        count.text = "…"
        hint.text = if (lanInfo != null) {
            "сканируем Bluetooth, Wi‑Fi эфир и LAN • ${lanInfo.label}"
        } else {
            "сканируем радио • LAN IPv4 не определён; откройте диагностику"
        }
        scanButton.text = "ПОИСК ИДЁТ…"
        acquireMulticast()
        if (prefs.getBoolean("ble", true)) runCatching { ble.start() }.onFailure { updateSourceStatus("BLE", it.javaClass.simpleName) }
        if (prefs.getBoolean("classic", true)) runCatching { classic.start() }.onFailure { updateSourceStatus("BT", it.javaClass.simpleName) }
        if (prefs.getBoolean("wifi_radio", true)) runCatching { wifiDiscovery.start() }.onFailure { updateSourceStatus("Wi‑Fi", it.javaClass.simpleName) }
        if (prefs.getBoolean("mdns", true)) runCatching { nsd.start() }
        if (prefs.getBoolean("ssdp", true)) runCatching { ssdp.start() }
        if (prefs.getBoolean("lan", true)) runCatching { lan.start(effectiveScanConfig()) }
        if (prefs.getBoolean("pjlink", true)) runCatching { pjDiscovery.start() }
        if (prefs.getBoolean("yeelight", true)) runCatching { yeelightDiscovery.start() }.onFailure { updateSourceStatus("Yeelight", it.javaClass.simpleName) }
        val duration = prefs.getInt("scan_duration", 35).coerceIn(10, 120)
        handler.postDelayed(finishScan, duration * 1000L)
    }

    private fun stopScan() {
        handler.removeCallbacks(finishScan)
        handler.removeCallbacks(stopLanAfterGrace)
        stopSources()
    }

    private fun stopShortSources() {
        runCatching { ble.stop() }
        runCatching { classic.stop() }
        runCatching { wifiDiscovery.stop() }
        runCatching { nsd.stop() }
        runCatching { ssdp.stop() }
        runCatching { pjDiscovery.stop() }
        runCatching { yeelightDiscovery.stop() }
        releaseMulticast()
    }

    private fun stopSources() {
        stopShortSources()
        runCatching { lan.stop() }
    }

    private fun updateSourceStatus(source: String, status: String) = runOnUiThread {
        sourceStatus[source] = status
        if (devices.isEmpty()) hint.text = "$source: $status"
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
            val sameHostIds = devices.values
                .filter { existing ->
                    existing.id != incoming.id &&
                        !existing.id.startsWith("wifi:") &&
                        !existing.id.startsWith("ble:") &&
                        !existing.id.startsWith("bt:") &&
                        (existing.ipAddress == host || hostOf(existing.address) == host)
                }
                .map { it.id }
            sameHostIds.forEach { id ->
                devices.remove(id)?.let { existing ->
                    candidate = if (existing.protocol.startsWith("LAN")) mergeNetwork(candidate, existing) else mergeDevice(existing, candidate)
                }
            }
        }
        devices[incoming.id]?.let { candidate = mergeDevice(it, candidate) }
        devices[incoming.id] = candidate
        count.text = devices.size.toString()
        hint.text = plural(devices.size)
        scanButton.text = "↻  ИСКАТЬ ЕЩЁ"
        scheduleRender()
    }

    private fun mergeDevice(old: NearbyDevice, fresh: NearbyDevice): NearbyDevice = fresh.copy(
        controllable = old.controllable || fresh.controllable,
        verified = old.verified || fresh.verified,
        brand = fresh.brand ?: old.brand,
        capabilities = old.capabilities + fresh.capabilities,
        descriptionUrl = fresh.descriptionUrl ?: old.descriptionUrl,
        ipAddress = fresh.ipAddress ?: old.ipAddress,
        macAddress = fresh.macAddress ?: old.macAddress,
        hardwareVendor = fresh.hardwareVendor ?: old.hardwareVendor,
        openPorts = if (fresh.openPorts.isNotEmpty()) fresh.openPorts else old.openPorts,
        osHint = fresh.osHint ?: old.osHint,
        hostname = fresh.hostname ?: old.hostname,
        analysisNote = fresh.analysisNote ?: old.analysisNote,
        securityFindings = if (fresh.securityFindings.isNotEmpty()) fresh.securityFindings else old.securityFindings,
        companionId = fresh.companionId ?: old.companionId
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
        securityFindings = if (network.securityFindings.isNotEmpty()) network.securityFindings else primary.securityFindings,
        companionId = primary.companionId ?: network.companionId
    )

    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        handler.postDelayed({
            renderPending = false
            val q = query.trim().lowercase()
            val filtered = devices.values.asSequence()
                .filter { d -> selectedKinds.isEmpty() || selectedKinds.any { matchesKind(d, it) } }
                .filter { d -> !onlyControllable || d.controllable || d.verified }
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

    private fun showSettingsHub() {
    val items = arrayOf(
        "🔎 Фильтры устройств",
        "📡 Настройки сканера",
        "🔌 Подключение по IP / пароль / pairing",
        "🧪 Диагностика поиска",
        "📱 Android Companion",
        "🍎 iPhone / iPad",
        "♻ Сбросить поиск и источники"
    )
    AlertDialog.Builder(this)
        .setTitle("⚙ Настройки")
        .setItems(items) { _, which ->
            when (which) {
                0 -> showFilters()
                1 -> showSettings()
                2 -> showManualControl()
                3 -> showDiscoveryDiagnostics()
                4 -> showCompanionHelp()
                5 -> showIosHelp()
                6 -> {
                    prefs.edit()
                        .putString("ip_range", "auto")
                        .putInt("connect_timeout", 300)
                        .putInt("parallelism", 32)
                        .putBoolean("ble", true)
                        .putBoolean("classic", true)
                        .putBoolean("wifi_radio", true)
                        .putBoolean("mdns", true)
                        .putBoolean("ssdp", true)
                        .putBoolean("lan", true)
                        .putBoolean("pjlink", true)
                        .putBoolean("yeelight", true)
                        .apply()
                    requestAndScan()
                }
            }
        }
        .setNegativeButton("Закрыть", null)
        .show()
}

    private fun showFilters() {
        val labels = arrayOf("Телевизоры", "Колонки / аудио", "Свет", "Компьютеры", "Проекторы", "Телефоны", "Принтеры", "Другие")
        val checked = labels.map { it in selectedKinds }.toBooleanArray()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        val controllable = CheckBox(this).apply { text = "Только кандидаты / API подтверждён"; isChecked = onlyControllable }
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
            "classic" to "Bluetooth Classic (только реально обнаруженные)",
            "wifi_radio" to "Wi‑Fi эфир / точки доступа (SSID/BSSID)",
            "mdns" to "mDNS / Bonjour",
            "ssdp" to "SSDP / UPnP",
            "lan" to "LAN IPv4 + TCP",
            "pjlink" to "PJLink-проекторы",
            "yeelight" to "Yeelight LAN discovery"
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
            .setMessage("Wi‑Fi эфир показывает точки доступа вокруг, а LAN — устройства в подключённой локальной сети. Сохранённые Bluetooth-устройства больше не считаются находящимися рядом.")
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

    private fun effectiveScanConfig(): ScanConfig {
        val cfg = loadScanConfig()
        val info = WifiNetworkResolver.lanInfo(this) ?: return cfg
        if (cfg.range.equals("auto", true) || cfg.range.isBlank()) return cfg
        val parsed = Ipv4Range.parse(cfg.range) ?: return cfg.copy(range = "auto")
        val own = Ipv4Range.ipv4ToInt(info.ipv4).toUInt()
        val inside = own >= parsed.first.toUInt() && own <= parsed.last.toUInt()
        if (inside) return cfg
        updateSourceStatus("LAN", "сохранённый диапазон ${cfg.range} не содержит ${info.ipv4.hostAddress}; временно auto")
        return cfg.copy(range = "auto")
    }

    private fun showDiscoveryDiagnostics() {
        val info = WifiNetworkResolver.lanInfo(this)
        val location = getSystemService(android.location.LocationManager::class.java)
        val locationOn = if (Build.VERSION.SDK_INT >= 28) location?.isLocationEnabled == true else true
        val lines = mutableListOf<String>()
        lines += "Версия: ${BuildConfig.VERSION_NAME}"
        lines += "LAN: ${info?.label ?: "НЕ НАЙДЕН"}"
        lines += "Gateway: ${info?.gateways?.joinToString { it.hostAddress ?: "?" }?.ifBlank { "—" } ?: "—"}"
        lines += "DNS: ${info?.dnsServers?.joinToString { it.hostAddress ?: "?" }?.ifBlank { "—" } ?: "—"}"
        lines += "Геолокация Android: ${if (locationOn) "ВКЛ" else "ВЫКЛ"}"
        lines += "Fine location: ${if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) "OK" else "НЕТ"}"
        if (Build.VERSION.SDK_INT >= 31) {
            lines += "Bluetooth scan: ${if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) "OK" else "НЕТ"}"
        }
        if (Build.VERSION.SDK_INT >= 33) {
            lines += "Nearby Wi-Fi: ${if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) "OK" else "НЕТ"}"
        }
        lines += "Диапазон: ${prefs.getString("ip_range", "auto") ?: "auto"}"
        lines += "Источники: BLE=${prefs.getBoolean("ble", true)}, BT=${prefs.getBoolean("classic", true)}, Wi-Fi=${prefs.getBoolean("wifi_radio", true)}, mDNS=${prefs.getBoolean("mdns", true)}, SSDP=${prefs.getBoolean("ssdp", true)}, LAN=${prefs.getBoolean("lan", true)}"
        AlertDialog.Builder(this)
            .setTitle("Диагностика поиска")
            .setMessage(lines.joinToString("\n"))
            .setNegativeButton("Закрыть", null)
            .setPositiveButton("Сбросить сканер") { _, _ ->
                prefs.edit()
                    .putString("ip_range", "auto")
                    .putInt("connect_timeout", 300)
                    .putInt("parallelism", 32)
                    .putBoolean("ble", true)
                    .putBoolean("classic", true)
                    .putBoolean("wifi_radio", true)
                    .putBoolean("mdns", true)
                    .putBoolean("ssdp", true)
                    .putBoolean("lan", true)
                    .putBoolean("pjlink", true)
                    .putBoolean("yeelight", true)
                    .apply()
                requestAndScan()
            }
            .show()
    }

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
        if (currentDevice(initial.id).macAddress != null) layout.addView(wolButton)
        val healthButton = controlRow("🧪 Проверить стабильность службы") { runHealthProbe(currentDevice(initial.id)) }
        layout.addView(healthButton)
        val currentBluetooth = currentDevice(initial.id)
        val bleCandidate = isBleDevice(currentBluetooth)
        val classicCandidate = isClassicBluetoothDevice(currentBluetooth)
        val connectLabel = when {
            bleCandidate -> "🟦 BLE GATT / СОПРЯЖЕНИЕ"
            classicCandidate -> "🔵 BLUETOOTH / СОПРЯЖЕНИЕ"
            else -> "⚡ БАЗОВОЕ УПРАВЛЕНИЕ / АВТО"
        }
        val controlTestButton = controlRow(connectLabel) {
            val current = currentDevice(initial.id)
            openBasicControl(current)
        }
        layout.addView(controlTestButton)

        val d0 = currentDevice(initial.id)
        when {
            d0.verified -> layout.addView(controlRow("🎛 ОТКРЫТЬ ПУЛЬТ • API ✓") { showRemote(currentDevice(initial.id)) })
            isRemoteCandidate(d0) -> layout.addView(controlRow("🔌 ВАРИАНТЫ ПОДКЛЮЧЕНИЯ") { showConnectionOptions(currentDevice(initial.id)) })
            else -> layout.addView(controlRow("Как подключить управление") { showPairingInfo(d0) })
        }
        if (isAppleMobile(d0)) {
            layout.addView(controlRow("🍎 iPhone / iPad: варианты управления") { showIosHelp(d0) })
        }

        details.text = deviceDetailsText(d0) + if (hostOf(d0.address) != null || d0.ipAddress != null) "\n\nСетевой анализ запускается…" else ""
        val dialog = AlertDialog.Builder(this).setTitle(d0.name).setView(scroll).setNegativeButton("Закрыть", null).create()
        dialog.setOnShowListener {
            if (hostOf(d0.address) != null || d0.ipAddress != null) analyzeDevice(d0, details)
            else analyzeButton.isEnabled = false
            wolButton.isEnabled = d0.macAddress != null
            healthButton.isEnabled = hostOf(d0.address) != null || d0.ipAddress != null
            controlTestButton.isEnabled = isBleDevice(d0) || isClassicBluetoothDevice(d0) || hostOf(d0.address) != null || d0.ipAddress != null
        }
        dialog.show()
    }

    private fun currentDevice(id: String): NearbyDevice = devices[id] ?: NearbyDevice(id, "Устройство", "Неизвестно", "", "")

    private fun isBleDevice(d: NearbyDevice): Boolean =
d.id.startsWith("ble:", true) || d.protocol.contains("Bluetooth LE", true)

    private fun isClassicBluetoothDevice(d: NearbyDevice): Boolean =
        d.id.startsWith("bt:", true) || d.protocol.contains("Bluetooth Classic", true)

private fun bleAddress(d: NearbyDevice): String? {
    val mac = Regex("(?i)^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    return listOf(d.macAddress, d.address, d.id.removePrefix("ble:"))
        .firstOrNull { value -> value != null && mac.matches(value) }
}

private fun showClassicBluetoothPanel(d: NearbyDevice) {
    val address = bleAddress(d) ?: return toast("Bluetooth MAC не определён")
    val scroll = ScrollView(this)
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(8), dp(18), dp(8))
    }
    val status = text(
        "Bluetooth Classic: $address\n\nCompanion на наушниках/колонке не нужен. Если устройство уже было сопряжено или поддерживает Just Works, Android может выполнить bonding без ручного PIN. Если устройство требует подтверждение/код или не находится в pairing mode, UniversalRemote не может обходить это требование. После сопряжения A2DP/HFP/HID подключает сама система.",
        13f, Color.WHITE, false
    ).apply { setTextIsSelectable(true) }
    layout.addView(status)
    layout.addView(controlRow("🔐 ПОДКЛЮЧИТЬ / СОПРЯЧЬ ЧЕРЕЗ ANDROID") {
        status.text = "⏳ Запускаю штатное Bluetooth-сопряжение для $address…"
        bleGatt.requestBond(address) { ok, message ->
            runOnUiThread {
                status.text = if (ok) "✓ $message" else message
                toast(message)
            }
        }
    })
    layout.addView(controlRow("🟦 ПРОВЕРИТЬ BLE GATT / DUAL-MODE") {
        status.text = "⏳ Проверяю, доступен ли этому адресу BLE GATT…"
        bleGatt.inspect(address) { result -> runOnUiThread { status.text = formatBleGattInspection(result) } }
    })
    layout.addView(controlRow("⚙ ОТКРЫТЬ НАСТРОЙКИ BLUETOOTH") {
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { toast("Не удалось открыть настройки Bluetooth") }
    })
    layout.addView(text(
        "Bluetooth Classic не имеет одного универсального протокола управления. Для JBL/наушников Android после pairing обычно сам подключает аудиопрофиль. BLE GATT проверяется отдельно, если устройство dual-mode или публикует LE-интерфейс.",
        12f, c("#AABBD4"), false
    ).apply { setPadding(0, dp(10), 0, 0) })
    scroll.addView(layout)
    AlertDialog.Builder(this).setTitle("Bluetooth • ${d.name}").setView(scroll).setNegativeButton("Закрыть", null).show()
}

private fun showBleGattPanel(d: NearbyDevice) {
    val address = bleAddress(d) ?: return toast("BLE MAC не определён")
    val scroll = ScrollView(this)
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(8), dp(18), dp(8))
    }
    val status = text(
        "BLE: $address\n\nGATT ещё не проверен. Сначала прочитайте сервисы. Если характеристика требует шифрование, используйте штатное Android Bluetooth pairing.",
        13f,
        Color.WHITE,
        false
    ).apply { setTextIsSelectable(true) }
    layout.addView(status)
    layout.addView(controlRow("🔎 ПОДКЛЮЧИТЬСЯ И ПРОЧИТАТЬ GATT") {
        status.text = "⏳ Подключение к $address и discoverServices()…"
        bleGatt.inspect(address) { result ->
            runOnUiThread {
                status.text = formatBleGattInspection(result)
                val current = devices[d.id]
                if (current != null) {
                    devices[d.id] = current.copy(analysisNote = result.message)
                    scheduleRender()
                }
            }
        }
    })
    layout.addView(controlRow("🔐 ШТАТНОЕ BLUETOOTH-СОПРЯЖЕНИЕ") {
        status.text = "⏳ Android запускает штатное Bluetooth pairing. Подтвердите PIN/код на системном экране и на устройстве, если он появится."
        bleGatt.requestBond(address) { ok, message ->
            runOnUiThread {
                toast(message)
                status.text = if (ok) {
                    "✓ $message\n\nТеперь нажмите «Подключиться и прочитать GATT» ещё раз: после bonding могут открыться защищённые сервисы."
                } else {
                    "Pairing: $message"
                }
            }
        }
    })
    layout.addView(text(
        "UniversalRemote не пишет случайные байты в неизвестные vendor-характеристики. Для реального пульта нужен известный формат команд конкретного BLE-протокола. Стандартные и vendor UUID теперь определяются автоматически, а pairing выполняет Android.",
        12f,
        c("#AABBD4"),
        false
    ).apply { setPadding(0, dp(10), 0, 0) })
    scroll.addView(layout)
    AlertDialog.Builder(this)
        .setTitle("BLE GATT • ${d.name}")
        .setView(scroll)
        .setNegativeButton("Закрыть", null)
        .show()
}

private fun formatBleGattInspection(result: BleGattController.InspectionResult): String = buildString {
    appendLine(if (result.ok) "✓ ${result.message}" else "✗ ${result.message}")
    appendLine("Адрес: ${result.address}")
    appendLine("Имя: ${result.deviceName ?: "—"}")
    appendLine("Pairing/bond: ${result.bondState}")
    if (!result.ok) return@buildString
    appendLine()
    appendLine("GATT-сервисы:")
    result.services.take(32).forEach { service ->
        appendLine()
        appendLine("${if (service.primary) "PRIMARY" else "SECONDARY"} • ${service.name}")
        appendLine(service.uuid)
        service.characteristics.take(32).forEach { ch ->
            append("  ↳ ${ch.name} • ${ch.properties.ifEmpty { listOf("без известных свойств") }.joinToString("/")}")
            appendLine("\n     ${ch.uuid}")
        }
    }
    if (result.services.size > 32) appendLine("\n… ещё ${result.services.size - 32} сервис(ов)")
    val knownControl = result.services.filter { it.name in setOf("Immediate Alert", "Human Interface Device", "Media Control", "Generic Media Control") }
    if (knownControl.isNotEmpty()) {
        appendLine()
        appendLine("Известные стандартные профили: ${knownControl.joinToString { it.name }}")
    }
}.trim()

    private fun analyzeDevice(device: NearbyDevice, details: TextView) {
        val host = device.ipAddress ?: hostOf(device.address)
        if (host == null) { details.text = deviceDetailsText(device) + "\n\nIPv4-адрес для TCP-анализа не определён."; return }
        details.text = deviceDetailsText(device) + "\n\n⏳ Анализ $host: порты, баннеры, MAC, hostname…"
        analyzer.analyze(device.copy(ipAddress = host), loadScanConfig()) { analyzed ->
            runOnUiThread {
                val id = device.id
                val enriched = enrichControlFromPorts(analyzed)
                val old = devices[id]
                if (old != null) devices[id] = mergeDevice(old, enriched) else devices[id] = enriched
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
            appendLine("Управление: ${when { d.verified -> "API подтверждён ✓"; d.controllable || isRemoteCandidate(d) -> "кандидат — требуется проверка API/pairing"; else -> "не подтверждено" }}")
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

    private fun enrichControlFromPorts(d: NearbyDevice): NearbyDevice {
        val ports = d.openPorts.map { it.port }.toSet()
        return when {
            45123 in ports -> d.copy(
                kind = "Телефон / планшет (Companion)",
                protocol = "UniversalRemote Companion v2 (кандидат)",
                brand = d.brand ?: "UniversalRemote Companion",
                controllable = true,
                capabilities = d.capabilities + setOf(ControlCapability.VOLUME, ControlCapability.MUTE, ControlCapability.MEDIA, ControlCapability.NAVIGATION)
            )
            6466 in ports || 6467 in ports -> d.copy(
                kind = if (d.kind.contains("Телевизор") || d.kind.contains("ТВ")) d.kind else "Телевизор / Android TV",
                protocol = "Android TV Remote Service v2 (кандидат до pairing)",
                brand = d.brand ?: "Android TV",
                controllable = true,
                capabilities = d.capabilities + setOf(
                    ControlCapability.POWER, ControlCapability.VOLUME, ControlCapability.MUTE,
                    ControlCapability.MEDIA, ControlCapability.NAVIGATION, ControlCapability.INPUT
                )
            )
            8060 in ports -> d.copy(
                kind = if (d.kind.contains("Телевизор") || d.kind.contains("ТВ")) d.kind else "Телевизор / медиаплеер",
                protocol = "Roku ECP (кандидат, проверяется перед командой)",
                brand = d.brand ?: "Roku",
                controllable = true,
                capabilities = d.capabilities + setOf(
                    ControlCapability.POWER, ControlCapability.VOLUME, ControlCapability.MUTE,
                    ControlCapability.MEDIA, ControlCapability.NAVIGATION, ControlCapability.CHANNEL, ControlCapability.INPUT
                )
            )
            8001 in ports || 8002 in ports -> d.copy(
                kind = if (d.kind.contains("Телевизор") || d.kind.contains("ТВ")) d.kind else "Телевизор / Samsung Tizen (кандидат)",
                protocol = "Samsung Tizen Remote API (кандидат, проверяется перед командой)",
                brand = d.brand ?: "Samsung",
                controllable = true,
                capabilities = d.capabilities + setOf(
                    ControlCapability.POWER, ControlCapability.VOLUME, ControlCapability.MUTE,
                    ControlCapability.MEDIA, ControlCapability.NAVIGATION, ControlCapability.CHANNEL, ControlCapability.INPUT
                )
            )
            3000 in ports || 3001 in ports -> d.copy(
                kind = if (d.kind.contains("Телевизор") || d.kind.contains("ТВ")) d.kind else "Телевизор / LG webOS (кандидат)",
                protocol = "LG webOS SSAP (кандидат, проверяется при подключении)",
                controllable = true,
                capabilities = d.capabilities + setOf(
                    ControlCapability.POWER, ControlCapability.VOLUME, ControlCapability.MUTE,
                    ControlCapability.MEDIA, ControlCapability.NAVIGATION, ControlCapability.INPUT
                )
            )
            8009 in ports -> d.copy(
                kind = if (d.kind.contains("ТВ") || d.kind.contains("медиаплеер", true)) d.kind else "ТВ / медиаплеер",
                protocol = "Google Cast v2 (кандидат, проверяется перед командой)",
                brand = d.brand ?: "Google Cast",
                controllable = true,
                capabilities = d.capabilities + setOf(ControlCapability.VOLUME, ControlCapability.MUTE, ControlCapability.MEDIA)
            )
            55443 in ports -> d.copy(
                kind = "Лампа / свет",
                protocol = "Yeelight LAN Control (кандидат)",
                brand = d.brand ?: "Yeelight",
                controllable = true,
                capabilities = d.capabilities + setOf(ControlCapability.LIGHT_POWER, ControlCapability.BRIGHTNESS, ControlCapability.COLOR)
            )
            4352 in ports -> d.copy(
                kind = "Проектор",
                protocol = "PJLink",
                controllable = true,
                capabilities = d.capabilities + setOf(ControlCapability.POWER, ControlCapability.VOLUME, ControlCapability.MUTE)
            )
            else -> d
        }
    }

    private fun isRemoteCandidate(d: NearbyDevice): Boolean = d.controllable ||
        d.kind.contains("Телевизор", true) || d.kind.contains("ТВ", true) ||
        d.kind.contains("Проектор", true) || d.kind.contains("Лампа", true) || d.kind.contains("свет", true) ||
        d.protocol.contains("Roku", true) || d.protocol.contains("Android TV", true) || d.protocol.contains("Samsung", true) ||
        d.protocol.contains("LG webOS", true) || d.protocol.contains("Google Cast", true) || d.protocol.contains("Hue", true) ||
        d.protocol.contains("Yeelight", true) || d.protocol.contains("UPnP MediaRenderer", true) || d.protocol.contains("PJLink", true) ||
        d.protocol.contains("UniversalRemote Companion", true) ||
        d.openPorts.any { it.port in setOf(45123, 3000, 3001, 4352, 6466, 6467, 8001, 8002, 8009, 8060, 55443) }

    private fun showRemote(d: NearbyDevice) {
        detectAndOpenRemote(d)
    }

    private fun openBasicControl(d: NearbyDevice) {
        if (isBleDevice(d)) return showBleGattPanel(d)
        if (isClassicBluetoothDevice(d)) return showClassicBluetoothPanel(d)

        val host = d.ipAddress ?: hostOf(d.address)
        val radioOnly = d.protocol.contains("Wi-Fi", true) || d.id.startsWith("wifi:")
        if (host != null) {
            toast("Проверяю базовые команды и штатные API…")
            return detectAndOpenRemote(d)
        }
        if (radioOnly) return showPairingInfo(d)

        if (d.macAddress != null) {
            AlertDialog.Builder(this)
                .setTitle("Базовое управление • ${d.name}")
                .setMessage(
                    "IPv4/API сейчас не подтверждён. Если устройство поддерживает Wake-on-LAN, можно попробовать включение по MAC. " +
                        "Выключение без штатного API невозможно: для него нужен поддерживаемый протокол самого устройства."
                )
                .setPositiveButton("⚡ Включить по Wake-on-LAN") { _, _ -> sendWake(d) }
                .setNeutralButton("Варианты подключения") { _, _ -> showPairingInfo(d) }
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }
        showPairingInfo(d)
    }

    private fun showConnectionOptions(d: NearbyDevice) {
        if (isBleDevice(d)) return showBleGattPanel(d)
        if (isClassicBluetoothDevice(d)) return showClassicBluetoothPanel(d)
        val host = d.ipAddress ?: hostOf(d.address) ?: return showPairingInfo(d)
        if (!isInCurrentWifiSubnet(host)) {
            toast("Подключение разрешено только к private IPv4 текущей Wi‑Fi подсети")
            return
        }
        val options = arrayOf(
            "⚡ Авто — без пароля, если API это разрешает",
            "🔐 Пароль / PIN / код / pairing — штатная авторизация",
            "🎥 PJLink — пароль проектора"
        )
        AlertDialog.Builder(this)
            .setTitle("Подключение • ${d.name}")
            .setMessage(
                "Если вы знаете пароль или код, UniversalRemote использует его только в штатном протоколе этого устройства. " +
                    "Один пароль не является универсальным ключом: разные устройства используют разные API и способы pairing. " +
                    "Подбор, обход PIN и отключение защиты не выполняются."
            )
            .setItems(options) { _, which ->
                when (which) {
                    0 -> detectAndOpenRemote(d)
                    1 -> openOfficialAuthFlow(d, host)
                    2 -> pjlink.probe(host) { result -> runOnUiThread {
                        if (result.ok) showPjLinkRemote(d, host) else toast("PJLink: ${result.message}")
                    } }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun openOfficialAuthFlow(d: NearbyDevice, host: String) {
        val ports = d.openPorts.map { it.port }.toSet()
        val idText = listOf(d.name, d.kind, d.brand.orEmpty(), d.protocol, d.hardwareVendor.orEmpty()).joinToString(" ").lowercase()
        when {
            d.protocol.contains("UniversalRemote Companion", true) || CompanionController.DEFAULT_PORT in ports -> detectAndOpenRemote(d)
            "android tv" in idText || "google tv" in idText || 6466 in ports || 6467 in ports -> showAndroidTvRemote(d, host)
            "webos" in idText || ("lg" in idText && (3000 in ports || 3001 in ports)) -> showLgWebOsRemote(d, host)
            "samsung" in idText || 8001 in ports || 8002 in ports -> showSamsungRemote(d, host)
            "philips hue" in idText || "hue bridge" in idText -> showHueRemote(d, host)
            d.protocol.contains("PJLink", true) || 4352 in ports -> showPjLinkRemote(d, host)
            else -> detectAndOpenRemote(d)
        }
    }

    private fun detectAndOpenRemote(d: NearbyDevice) {
        val host = d.ipAddress ?: hostOf(d.address) ?: return showPairingInfo(d)
        val route = WifiNetworkResolver.bindProcessToWifi(this)
        if (!route.bound) {
            toast("LAN-маршрут: ${route.message}")
        } else {
            toast("Проверяю API на $host через Wi-Fi…")
        }
        val controlPorts = listOf(80, 443, 3000, 3001, 4352, 6466, 6467, 8001, 8002, 8008, 8009, 8060, 55443, 62078, CompanionController.DEFAULT_PORT)
        val cfg = loadScanConfig().copy(
            ports = (controlPorts + d.openPorts.map { it.port }).distinct(),
            connectTimeoutMs = loadScanConfig().connectTimeoutMs.coerceAtLeast(350),
            bannerTimeoutMs = loadScanConfig().bannerTimeoutMs.coerceAtLeast(350),
            parallelism = 16,
            scanPorts = true
        )
        analyzer.analyze(d.copy(ipAddress = host), cfg) { analyzed ->
            val enriched = enrichControlFromPorts(analyzed)
            if (devices.containsKey(d.id)) {
                val old = devices[d.id]
                if (old != null) devices[d.id] = mergeDevice(old, enriched)
                runOnUiThread { scheduleRender() }
            }
            autoDetectRemote(enriched, host)
        }
    }

    private fun autoDetectRemote(d: NearbyDevice, host: String) {
        val ports = d.openPorts.map { it.port }.toSet()
        val idText = listOf(d.name, d.kind, d.brand.orEmpty(), d.protocol, d.hardwareVendor.orEmpty()).joinToString(" ").lowercase()

        if (d.protocol.contains("UniversalRemote Companion", true) || CompanionController.DEFAULT_PORT in ports) {
            val port = companionPort(d)
            return companion.probe(host, port) { result -> runOnUiThread {
                val info = result.info
                if (result.ok && info != null) {
                    val current = devices[d.id]
                    if (current != null) {
                        val ios = info.platform == "ios"
                        val caps = buildSet {
                            addAll(current.capabilities)
                            if (ios && "identify" in info.actions) add(ControlCapability.FIND_DEVICE)
                            if (ios && info.actions.any { it.startsWith("brightness_") }) add(ControlCapability.BRIGHTNESS)
                            if (!ios) {
                                if (info.actions.any { it.startsWith("volume_") } || "mute" in info.actions) add(ControlCapability.VOLUME)
                                if (info.actions.any { it.startsWith("media_") }) add(ControlCapability.MEDIA)
                                if (info.actions.any { it in setOf("home", "back", "recents") }) add(ControlCapability.NAVIGATION)
                            }
                        }
                        devices[d.id] = current.copy(
                            controllable = true, verified = companion.isPaired(info.deviceId), companionId = info.deviceId,
                            kind = if (ios) "iPhone / iPad (iOS Companion)" else "Телефон / планшет (Android Companion)",
                            brand = if (ios) "Apple / UniversalRemote iOS Companion" else current.brand,
                            protocol = if (ios) "UniversalRemote Companion v2 • iOS/iPadOS" else "UniversalRemote Companion v2 • Android",
                            capabilities = caps
                        )
                    }
                    scheduleRender()
                    showCompanionRemote(d.copy(companionId = info.deviceId, verified = companion.isPaired(info.deviceId)), host, port, info)
                } else showNoControlFound(d, host, listOf(result.message))
            } }
        }
        if ("android tv" in idText || "google tv" in idText || 6466 in ports || 6467 in ports) {
            return runOnUiThread { showAndroidTvRemote(d, host) }
        }
        if ("webos" in idText || ("lg" in idText && (3000 in ports || 3001 in ports))) {
            if (!lgWebOs.isTlsTrusted(host)) return runOnUiThread { showLgWebOsRemote(d, host) }
            return lgWebOs.probe(host) { result -> runOnUiThread {
                if (result.ok) { markVerified(d.id); showLgWebOsRemote(d, host) } else showNoControlFound(d, host, listOf(result.message))
            } }
        }
        if ("philips hue" in idText || "hue bridge" in idText) {
            return hue.probe(host) { result -> runOnUiThread { if (result.ok) { markVerified(d.id); showHueRemote(d, host) } else showNoControlFound(d, host, listOf(result.message)) } }
        }
        if (d.protocol.contains("PJLink", true) || 4352 in ports) {
            return pjlink.probe(host) { result -> runOnUiThread { if (result.ok) { markVerified(d.id); showPjLinkRemote(d, host) } else showNoControlFound(d, host, listOf(result.message)) } }
        }
        if (d.descriptionUrl != null && d.protocol.contains("UPnP MediaRenderer", true)) {
            return upnp.probe(host, d.descriptionUrl) { result -> runOnUiThread {
                if (result.ok) { markVerified(d.id); showUpnpRemote(d) } else showNoControlFound(d, host, listOf(result.message))
            } }
        }

        val errors = mutableListOf<String>()
        roku.probe(host) { rokuResult, _ ->
            if (rokuResult.ok) {
                runOnUiThread { markVerified(d.id); showRokuRemote(d, host) }
            } else {
                errors += "Roku: ${rokuResult.message}"
                samsung.probe(host) { samsungResult ->
                    if (samsungResult.ok) {
                        runOnUiThread { markVerified(d.id); showSamsungRemote(d, host) }
                    } else {
                        errors += "Samsung: ${samsungResult.message}"
                        hue.probe(host) { hueResult ->
                            if (hueResult.ok) {
                                runOnUiThread { markVerified(d.id); showHueRemote(d, host) }
                            } else {
                                errors += "Hue: ${hueResult.message}"
                                wled.probe(host) { wledResult ->
                                    if (wledResult.ok) {
                                        runOnUiThread { markVerified(d.id); showWledRemote(d, host, wledResult.message) }
                                    } else {
                                        errors += "WLED: ${wledResult.message}"
                                        yeelight.probe(host, yeelightPort(d)) { yeelightResult ->
                                            if (yeelightResult.ok) {
                                                runOnUiThread { markVerified(d.id); showYeelightRemote(d, host, yeelightPort(d)) }
                                            } else {
                                                errors += "Yeelight: ${yeelightResult.message}"
                                                if (8009 !in ports) {
                                                    errors += "Cast: mDNS-кандидат отклонён — TCP 8009 не отвечает"
                                                    runOnUiThread { showNoControlFound(d, host, errors) }
                                                } else {
                                                    runOnUiThread {
                                                        probeAndShowCast(d, host) { message ->
                                                            errors += "Cast: $message"
                                                            showNoControlFound(d, host, errors)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showNoControlFound(d: NearbyDevice, host: String, errors: List<String>) {
        val radioOnly = d.protocol.contains("Wi-Fi", true) || d.id.startsWith("wifi:")
        val msg = buildString {
            append("IP: $host\n\n")
            if (radioOnly) append("Эта запись пришла из Wi‑Fi эфира. Точка доступа может быть видна по радио, но это не означает, что телефон имеет доступ к её локальному API.\n\n")
            append("Поддерживаемый API управления не подтвердился. Приложение не будет отправлять команды наугад.\n\n")
            append(errors.take(6).joinToString("\n") { "• $it" })
            append("\n\nЕсли это ваше устройство, можно открыть варианты штатного подключения: без авторизации, PIN/pairing или PJLink-пароль — в зависимости от протокола.")
        }
        AlertDialog.Builder(this)
            .setTitle("Управление не подтверждено • ${d.name}")
            .setMessage(msg)
            .setNegativeButton("Закрыть", null)
            .setPositiveButton("Варианты подключения") { _, _ -> showConnectionOptions(d) }
            .show()
    }

    private fun showCompanionHelp() {
        AlertDialog.Builder(this)
            .setTitle("Android Companion • v${BuildConfig.VERSION_NAME}")
            .setMessage("Для управления вторым Android-телефоном установите Companion той же версии. На втором телефоне откройте Companion → Запустить Companion. Затем здесь запустите поиск, откройте карточку телефона и введите одноразовый 12-символьный код.\n\nГромкость и media работают после pairing. Home/Back/Recents требуют вручную включить Accessibility на управляемом телефоне. PIN/пароль блокировки не используется и не обходится.")
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun showIosHelp(device: NearbyDevice? = null) {
        val detected = device?.let {
            "\n\nНайдено сейчас: ${it.name}\n${it.protocol}\n${it.ipAddress ?: hostOf(it.address) ?: "IPv4 не подтверждён"}"
        }.orEmpty()
        AlertDialog.Builder(this)
            .setTitle("iPhone / iPad • Companion v${BuildConfig.VERSION_NAME}")
            .setMessage(
                "БЕЗ ПРИЛОЖЕНИЯ НА iOS:\n" +
                    "• UniversalRemote ищет Apple Bonjour/Mobile Device признаки и показывает iPhone/iPad как отдельный класс устройств.\n" +
                    "• Если iPad штатно enrolled в MDM, административные команды должны идти через ваш MDM-сервер; PIN экрана не является сетевым паролем.\n" +
                    "• Обычная iPadOS не предоставляет стороннему Android-приложению API для Home/Back/касания по экрану.\n\n" +
                    "С IOS COMPANION:\n" +
                    "• pairing совместим с Companion v2, код живёт ограниченное время, сессии можно отзывать;\n" +
                    "• доступны функции, которые сама iOS разрешает Companion;\n" +
                    "• iOS может приостанавливать локальный listener в фоне.\n\n" +
                    "Для установки iOS Companion на физический iPhone/iPad нужна подпись Apple Developer/вашего Team." + detected
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun isAppleMobile(d: NearbyDevice): Boolean {
        val text = listOf(d.name, d.kind, d.brand.orEmpty(), d.protocol, d.hardwareVendor.orEmpty(), d.hostname.orEmpty()).joinToString(" ").lowercase()
        return d.brand?.contains("Apple", true) == true || d.hardwareVendor?.contains("Apple", true) == true ||
            listOf("iphone", "ipad", "companion link", "apple-mobdev2", "apple mobile", "ios", "ipados").any { it in text }
    }

    private fun showManualControl() {
        val input = EditText(this).apply {
            hint = "IPv4 устройства, например 192.168.1.25"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this)
            .setTitle("Подключиться по IP")
            .setMessage("Введите IP вашего устройства в текущей локальной сети. После безопасного определения протокола приложение предложит либо прямое подключение без пароля, либо штатный PIN/pairing/пароль, если устройство его требует.")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить") { _, _ ->
                val host = input.text.toString().trim()
                if (!host.matches(Regex("(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}"))) {
                    toast("Введите корректный IPv4")
                } else if (!isInCurrentWifiSubnet(host)) {
                    toast("Разрешены только private IPv4 текущей Wi‑Fi подсети")
                } else {
                    val d = NearbyDevice(
                        id = "manual:$host",
                        name = "Устройство $host",
                        kind = "Сетевое устройство",
                        protocol = "Ручная проверка",
                        address = host,
                        ipAddress = host
                    )
                    showConnectionOptions(d)
                }
            }.show()
    }

    private fun companionPort(d: NearbyDevice): Int = d.address.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 }
        ?: d.openPorts.firstOrNull { it.port == CompanionController.DEFAULT_PORT }?.port
        ?: CompanionController.DEFAULT_PORT

    private fun showCompanionRemote(d: NearbyDevice, host: String, port: Int, info: CompanionController.Info) {
        val ios = info.platform == "ios"
        val scroll = ScrollView(this)
        val layout = remoteLayout(); scroll.addView(layout)
        val status = text(
            if (companion.isPaired(info.deviceId)) "✓ Companion сопряжён • ${info.name}" else "Нужно один раз ввести код с экрана Companion на управляемом устройстве.",
            13f, if (companion.isPaired(info.deviceId)) c("#72F1CE") else c("#AABBD4"), false
        ).apply { setPadding(0, 0, 0, dp(8)) }
        layout.addView(status)
        val code = EditText(this).apply {
            hint = "12-символьный код Companion"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(c("#7185A3"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        layout.addView(code)
        layout.addView(controlRow("🔐 Сопрячь это устройство") {
            val editable = code.text
            val useful = editable.count { !it.isWhitespace() }
            val chars = CharArray(useful)
            var out = 0
            for (i in 0 until editable.length) if (!editable[i].isWhitespace()) chars[out++] = editable[i].uppercaseChar()
            editable.clear()
            status.text = "Проверяю одноразовый pairing-код…"
            companion.pair(host, port, info.advertisementId, chars) { result -> runOnUiThread {
                chars.fill('\u0000')
                status.text = result.message
                toast(result.message)
                if (result.ok) markVerified(d.id)
            } }
        })

        if (ios) {
            layout.addView(sectionTitle("IOS / IPADOS COMPANION"))
            layout.addView(text(
                "iOS разрешает Companion только собственные функции приложения. Он не может эмулировать Home/Back/касания во всей iPadOS и не принимает PIN экрана как remote credential. Локальный listener может быть приостановлен системой, когда приложение уходит в фон.",
                12f, c("#AABBD4"), false
            ))
            addRemoteRow(layout,
                "✓ Ping" to { runCompanion(d, host, port, info.deviceId, "ping") },
                "🔔 Найти" to { runCompanion(d, host, port, info.deviceId, "identify") }
            )
            if (info.actions.any { it.startsWith("brightness_") }) {
                layout.addView(sectionTitle("ЯРКОСТЬ ЭКРАНА"))
                addRemoteRow(layout,
                    "☀ −" to { runCompanion(d, host, port, info.deviceId, "brightness_down") },
                    "50%" to { runCompanion(d, host, port, info.deviceId, "brightness_50") },
                    "☀ +" to { runCompanion(d, host, port, info.deviceId, "brightness_up") }
                )
            }
            layout.addView(controlRow("🍎 Что доступно без iOS Companion") { showIosHelp(d) })
        } else {
            val actions = info.actions
            val has = { action: String -> actions.isEmpty() || action in actions }
            layout.addView(sectionTitle("ГРОМКОСТЬ И МЕДИА"))
            addRemoteRow(layout,
                "Vol −" to { if (has("volume_down")) runCompanion(d, host, port, info.deviceId, "volume_down") else toast("Companion не объявил эту команду") },
                "Mute" to { if (has("mute")) runCompanion(d, host, port, info.deviceId, "mute") else toast("Companion не объявил эту команду") },
                "Vol +" to { if (has("volume_up")) runCompanion(d, host, port, info.deviceId, "volume_up") else toast("Companion не объявил эту команду") }
            )
            addRemoteRow(layout,
                "⏮" to { if (has("media_previous")) runCompanion(d, host, port, info.deviceId, "media_previous") else toast("Команда недоступна") },
                "▶/Ⅱ" to { if (has("media_play_pause")) runCompanion(d, host, port, info.deviceId, "media_play_pause") else toast("Команда недоступна") },
                "⏭" to { if (has("media_next")) runCompanion(d, host, port, info.deviceId, "media_next") else toast("Команда недоступна") }
            )
            layout.addView(sectionTitle("СИСТЕМНАЯ НАВИГАЦИЯ"))
            layout.addView(text(
                if (info.accessibility) "Accessibility Companion включён: Home/Back/Recents доступны." else "Home/Back/Recents требуют вручную включить Accessibility на управляемом Android. Companion не читает экран.",
                12f, if (info.accessibility) c("#72F1CE") else c("#AABBD4"), false
            ))
            addRemoteRow(layout,
                "⌂ Home" to { if (has("home")) runCompanion(d, host, port, info.deviceId, "home") else toast("Команда недоступна") },
                "↩ Back" to { if (has("back")) runCompanion(d, host, port, info.deviceId, "back") else toast("Команда недоступна") },
                "▣ Recents" to { if (has("recents")) runCompanion(d, host, port, info.deviceId, "recents") else toast("Команда недоступна") }
            )
        }
        if (companion.isPaired(info.deviceId)) layout.addView(controlRow("Отозвать pairing этого устройства") {
            status.text = "Отзываю pairing на управляемом устройстве…"
            companion.forget(host, port, info.deviceId) { result -> runOnUiThread {
                status.text = result.message
                toast(result.message)
            } }
        })
        val title = if (ios) "iOS/iPadOS Companion • ${info.name}" else "Android Companion • ${info.name}"
        AlertDialog.Builder(this).setTitle(title).setView(scroll).setNegativeButton("Закрыть", null).show()
    }

    private fun runCompanion(d: NearbyDevice, host: String, port: Int, deviceId: String, action: String) {
        companion.command(host, port, deviceId, action) { result -> runOnUiThread { toast(result.message); if (result.ok) markVerified(d.id) } }
    }

    private fun showAndroidTvRemote(d: NearbyDevice, host: String) {
        val scroll = ScrollView(this)
        val layout = remoteLayout()
        scroll.addView(layout)
        val status = text(
            if (androidTv.isPaired(host)) "✓ Сопряжение сохранено криптографически. PIN не хранится." else "Нужно один раз подтвердить 6-значный код с экрана TV.",
            13f, if (androidTv.isPaired(host)) c("#72F1CE") else c("#AABBD4"), false
        ).apply { setPadding(0, 0, 0, dp(8)) }
        layout.addView(status)

        val pin = EditText(this).apply {
            hint = "Код с TV: например A1B2C3"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(c("#7185A3"))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        layout.addView(controlRow("🔐 Начать сопряжение") {
            status.text = "Подключаемся к pairing service…"
            androidTv.startPairing(host) { result -> runOnUiThread { status.text = result.message; toast(result.message) } }
        })
        layout.addView(pin)
        layout.addView(controlRow("✓ Подтвердить код") {
            val code = pin.text.toString(); pin.text.clear(); status.text = "Проверяем код…"
            androidTv.finishPairing(host, code) { result -> runOnUiThread {
                status.text = result.message
                toast(result.message)
                if (result.ok) markVerified(d.id)
            } }
        })

        layout.addView(powerOffRow("⏻ ПИТАНИЕ TV") { runAndroidTv(d, host, AndroidTvController.KEY_POWER) })
        layout.addView(text("Android TV использует системную кнопку POWER: на некоторых моделях это toggle, а не отдельная команда Off.", 11f, c("#AABBD4"), false))
        layout.addView(sectionTitle("НАВИГАЦИЯ"))
        addDpad(layout,
            { runAndroidTv(d, host, AndroidTvController.KEY_UP) },
            { runAndroidTv(d, host, AndroidTvController.KEY_LEFT) },
            { runAndroidTv(d, host, AndroidTvController.KEY_OK) },
            { runAndroidTv(d, host, AndroidTvController.KEY_RIGHT) },
            { runAndroidTv(d, host, AndroidTvController.KEY_DOWN) }
        )
        addRemoteRow(layout,
            "⌂ Home" to { runAndroidTv(d, host, AndroidTvController.KEY_HOME) },
            "↩ Back" to { runAndroidTv(d, host, AndroidTvController.KEY_BACK) },
            "⏻ Power" to { runAndroidTv(d, host, AndroidTvController.KEY_POWER) }
        )
        addRemoteRow(layout,
            "Vol −" to { runAndroidTv(d, host, AndroidTvController.KEY_VOL_DOWN) },
            "Mute" to { runAndroidTv(d, host, AndroidTvController.KEY_VOLUME_MUTE) },
            "Vol +" to { runAndroidTv(d, host, AndroidTvController.KEY_VOL_UP) }
        )
        addRemoteRow(layout,
            "⏪" to { runAndroidTv(d, host, AndroidTvController.KEY_REWIND) },
            "▶/Ⅱ" to { runAndroidTv(d, host, AndroidTvController.KEY_PLAY_PAUSE) },
            "⏩" to { runAndroidTv(d, host, AndroidTvController.KEY_FAST_FORWARD) }
        )
        addRemoteRow(layout,
            "⚙ Settings" to { runAndroidTv(d, host, AndroidTvController.KEY_SETTINGS) },
            "Input" to { runAndroidTv(d, host, AndroidTvController.KEY_INPUT) }
        )
        layout.addView(controlRow("Забыть сопряжение этого TV") {
            androidTv.forget(host); status.text = "Сопряжение удалено. Для управления нужен новый код."; toast("Сопряжение удалено")
        })
        AlertDialog.Builder(this).setTitle("Android TV • ${d.name}").setView(scroll).setNegativeButton("Закрыть", null).show()
    }

    private fun runAndroidTv(d: NearbyDevice, host: String, key: Int) {
        androidTv.key(host, key) { result -> runOnUiThread { toast(result.message); if (result.ok) markVerified(d.id) } }
    }

    private fun showRokuRemote(d: NearbyDevice, host: String) {
        roku.probe(host) { probe, info -> runOnUiThread {
            if (!probe.ok) return@runOnUiThread toast(probe.message)
            val scroll = ScrollView(this)
            val layout = remoteLayout(); scroll.addView(layout)
            layout.addView(text(info?.let { "${it.name} • ${it.model}" } ?: "Roku ECP", 13f, c("#72F1CE"), false))
            layout.addView(powerOffRow("⏻ ВЫКЛЮЧИТЬ TV") { runRoku(d, host, "PowerOff") })
            layout.addView(sectionTitle("НАВИГАЦИЯ"))
            addDpad(layout,
                { runRoku(d, host, "Up") }, { runRoku(d, host, "Left") }, { runRoku(d, host, "Select") },
                { runRoku(d, host, "Right") }, { runRoku(d, host, "Down") }
            )
            addRemoteRow(layout,
                "⌂ Home" to { runRoku(d, host, "Home") },
                "↩ Back" to { runRoku(d, host, "Back") },
                "Info" to { runRoku(d, host, "Info") }
            )
            addRemoteRow(layout,
                "Vol −" to { runRoku(d, host, "VolumeDown") },
                "Mute" to { runRoku(d, host, "VolumeMute") },
                "Vol +" to { runRoku(d, host, "VolumeUp") }
            )
            addRemoteRow(layout,
                "⏪" to { runRoku(d, host, "Rev") },
                "▶/Ⅱ" to { runRoku(d, host, "Play") },
                "⏩" to { runRoku(d, host, "Fwd") }
            )
            addRemoteRow(layout,
                "⏻ On" to { runRoku(d, host, "PowerOn") },
                "⏻ Off" to { runRoku(d, host, "PowerOff") }
            )
            addRemoteRow(layout,
                "HDMI 1" to { runRoku(d, host, "InputHDMI1") },
                "HDMI 2" to { runRoku(d, host, "InputHDMI2") },
                "HDMI 3" to { runRoku(d, host, "InputHDMI3") }
            )
            AlertDialog.Builder(this).setTitle("Roku • ${d.name}").setView(scroll).setNegativeButton("Закрыть", null).show()
        } }
    }

    private fun runRoku(d: NearbyDevice, host: String, key: String) {
        roku.key(host, key) { result -> runOnUiThread { toast(result.message); if (result.ok) markVerified(d.id) } }
    }

    private fun confirmTlsFingerprint(title: String, host: String, fingerprint: String, approve: () -> Pair<Boolean, String>, after: () -> Unit) {
        val pretty = fingerprint.chunked(2).joinToString(":")
        AlertDialog.Builder(this)
            .setTitle("$title • первое TLS-доверие")
            .setMessage(
                "SHA-256 сертификата:\n$pretty\n\n" +
                    "Подтверждайте только своё устройство. Этот шаг исключает тихое автоматическое TOFU, но если производитель не показывает fingerprint в доверенном интерфейсе, первое наблюдение всё равно нельзя криптографически отличить от уже активного MITM."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Закрепить сертификат") { _, _ ->
                val result = approve()
                toast(result.second)
                if (result.first) after()
            }
            .show()
    }

    private fun ensureSamsungTls(host: String, after: () -> Unit) {
        if (samsung.isTlsTrusted(host)) return after()
        toast("Получаю TLS fingerprint Samsung без отправки token…")
        samsung.inspectTls(host) { result, fp -> runOnUiThread {
            if (!result.ok || fp == null) return@runOnUiThread toast(result.message)
            confirmTlsFingerprint("Samsung TV", host, fp, {
                val r = samsung.approveTls(host, fp); r.ok to r.message
            }, after)
        } }
    }

    private fun ensureLgTls(host: String, after: () -> Unit) {
        if (lgWebOs.isTlsTrusted(host)) return after()
        toast("Получаю TLS fingerprint LG без client-key…")
        lgWebOs.inspectTls(host) { result, fp -> runOnUiThread {
            if (!result.ok || fp == null) return@runOnUiThread toast(result.message)
            confirmTlsFingerprint("LG webOS", host, fp, {
                val r = lgWebOs.approveTls(host, fp); r.ok to r.message
            }, after)
        } }
    }

    private fun ensureHueTls(host: String, after: () -> Unit) {
        if (hue.isTlsTrusted(host)) return after()
        toast("Получаю TLS fingerprint Hue без application key…")
        hue.inspectTls(host) { result, fp -> runOnUiThread {
            if (!result.ok || fp == null) return@runOnUiThread toast(result.message)
            confirmTlsFingerprint("Philips Hue Bridge", host, fp, {
                val r = hue.approveTls(host, fp); r.ok to r.message
            }, after)
        } }
    }

    private fun ensureCastTls(host: String, after: () -> Unit) {
        if (cast.isTlsTrusted(host)) return after()
        toast("Получаю TLS fingerprint Cast…")
        cast.inspectTls(host) { result, fp -> runOnUiThread {
            if (!result.ok || fp == null) return@runOnUiThread toast(result.message)
            confirmTlsFingerprint("Google Cast", host, fp, {
                val r = cast.approveTls(host, fp); r.ok to r.message
            }, after)
        } }
    }

    private fun probeAndShowSamsung(d: NearbyDevice, host: String) {
        toast("Проверяю Samsung Tizen API…")
        samsung.probe(host) { result -> runOnUiThread {
            if (result.ok) { markVerified(d.id); showSamsungRemote(d, host) } else showPairingInfo(d)
        } }
    }

    private fun showSamsungRemote(d: NearbyDevice, host: String) {
        val scroll = ScrollView(this)
        val layout = remoteLayout(); scroll.addView(layout)
        layout.addView(text(when {
            samsung.isPaired(host) -> "✓ Samsung TV авторизован • TLS pin сохранён"
            !samsung.isTlsTrusted(host) -> "Сначала приложение покажет SHA-256 TLS fingerprint и попросит закрепить сертификат; только затем TV сможет выдать token."
            else -> "TLS pin подтверждён. При первом нажатии подтвердите «Разрешить» на телевизоре."
        }, 13f, if (samsung.isPaired(host)) c("#72F1CE") else c("#AABBD4"), false))
        layout.addView(powerOffRow("⏻ ВЫКЛЮЧИТЬ TV") { runSamsung(d, host, "KEY_POWEROFF") })
        layout.addView(sectionTitle("НАВИГАЦИЯ"))
        addDpad(layout,
            { runSamsung(d, host, "KEY_UP") }, { runSamsung(d, host, "KEY_LEFT") }, { runSamsung(d, host, "KEY_ENTER") },
            { runSamsung(d, host, "KEY_RIGHT") }, { runSamsung(d, host, "KEY_DOWN") }
        )
        addRemoteRow(layout,
            "⌂ Home" to { runSamsung(d, host, "KEY_HOME") },
            "↩ Back" to { runSamsung(d, host, "KEY_RETURN") },
            "⏻ Off" to { runSamsung(d, host, "KEY_POWEROFF") }
        )
        addRemoteRow(layout,
            "Vol −" to { runSamsung(d, host, "KEY_VOLDOWN") },
            "Mute" to { runSamsung(d, host, "KEY_MUTE") },
            "Vol +" to { runSamsung(d, host, "KEY_VOLUP") }
        )
        addRemoteRow(layout,
            "⏪" to { runSamsung(d, host, "KEY_REWIND") },
            "▶" to { runSamsung(d, host, "KEY_PLAY") },
            "Ⅱ" to { runSamsung(d, host, "KEY_PAUSE") }
        )
        addRemoteRow(layout,
            "Source" to { runSamsung(d, host, "KEY_SOURCE") },
            "Menu" to { runSamsung(d, host, "KEY_MENU") },
            "Info" to { runSamsung(d, host, "KEY_INFO") }
        )
        if (d.macAddress != null) layout.addView(controlRow("⚡ Включить через Wake-on-LAN") { sendWake(d) })
        if (samsung.isPaired(host)) layout.addView(controlRow("Забыть разрешение Samsung") { samsung.forget(host); toast("Токен Samsung удалён") })
        AlertDialog.Builder(this).setTitle("Samsung TV • ${d.name}").setView(scroll).setNegativeButton("Закрыть", null).show()
    }

    private fun runSamsung(d: NearbyDevice, host: String, key: String) {
        ensureSamsungTls(host) {
            samsung.key(host, key) { result -> runOnUiThread { toast(result.message); if (result.ok) markVerified(d.id) } }
        }
    }

    private fun showLgWebOsRemote(d: NearbyDevice, host: String) {
        val scroll = ScrollView(this)
        val layout = remoteLayout(); scroll.addView(layout)
        val status = text(
            when {
                lgWebOs.isPaired(host) -> "✓ LG webOS уже сопряжён • TLS pin сохранён"
                !lgWebOs.isTlsTrusted(host) -> "Сначала подтвердите SHA-256 TLS fingerprint, затем разрешите Universal Remote на экране LG TV."
                else -> "TLS pin подтверждён. Разрешите Universal Remote на экране LG TV."
            },
            13f, if (lgWebOs.isPaired(host)) c("#72F1CE") else c("#AABBD4"), false
        )
        layout.addView(status)
        layout.addView(controlRow("🔐 Проверить TLS / сопрячь TV") {
            ensureLgTls(host) {
                lgWebOs.probe(host) { result -> runOnUiThread {
                    status.text = result.message
                    toast(result.message)
                    if (result.ok) markVerified(d.id)
                } }
            }
        })
        layout.addView(powerOffRow("⏻ ВЫКЛЮЧИТЬ TV") { runLg(d, host) { cb -> lgWebOs.powerOff(host, cb) } })
        layout.addView(sectionTitle("НАВИГАЦИЯ"))
        addDpad(layout,
            { runLg(d, host) { cb -> lgWebOs.button(host, "UP", cb) } },
            { runLg(d, host) { cb -> lgWebOs.button(host, "LEFT", cb) } },
            { runLg(d, host) { cb -> lgWebOs.button(host, "ENTER", cb) } },
            { runLg(d, host) { cb -> lgWebOs.button(host, "RIGHT", cb) } },
            { runLg(d, host) { cb -> lgWebOs.button(host, "DOWN", cb) } }
        )
        addRemoteRow(layout,
            "⌂ Home" to { runLg(d, host) { cb -> lgWebOs.button(host, "HOME", cb) } },
            "↩ Back" to { runLg(d, host) { cb -> lgWebOs.button(host, "BACK", cb) } },
            "⏻ Off" to { runLg(d, host) { cb -> lgWebOs.powerOff(host, cb) } }
        )
        addRemoteRow(layout,
            "Vol −" to { runLg(d, host) { cb -> lgWebOs.volumeDown(host, cb) } },
            "Mute" to { runLg(d, host) { cb -> lgWebOs.mute(host, true, cb) } },
            "Vol +" to { runLg(d, host) { cb -> lgWebOs.volumeUp(host, cb) } }
        )
        addRemoteRow(layout,
            "⏪" to { runLg(d, host) { cb -> lgWebOs.rewind(host, cb) } },
            "▶" to { runLg(d, host) { cb -> lgWebOs.play(host, cb) } },
            "Ⅱ" to { runLg(d, host) { cb -> lgWebOs.pause(host, cb) } },
            "■" to { runLg(d, host) { cb -> lgWebOs.stop(host, cb) } },
            "⏩" to { runLg(d, host) { cb -> lgWebOs.fastForward(host, cb) } }
        )
        if (d.macAddress != null) layout.addView(controlRow("⚡ Включить TV через Wake-on-LAN") { sendWake(d) })
        layout.addView(controlRow("Забыть сопряжение LG TV") {
            lgWebOs.forget(host); status.text = "Ключ LG удалён. Следующее подключение снова попросит подтверждение на TV."
        })
        AlertDialog.Builder(this).setTitle("LG webOS • ${d.name}").setView(scroll).setNegativeButton("Закрыть", null).show()
    }

    private fun runLg(d: NearbyDevice, host: String, action: (((LgWebOsController.Result) -> Unit)) -> Unit) {
        ensureLgTls(host) {
            action { result -> runOnUiThread { toast(result.message); if (result.ok) markVerified(d.id) } }
        }
    }

    private fun probeAndShowCast(d: NearbyDevice, host: String, onFailure: (String) -> Unit = { toast(it) }) {
        if (cast.isTlsTrusted(host)) {
            toast("Проверяю Google Cast v2…")
            cast.probe(host) { result -> runOnUiThread {
                if (!result.ok) return@runOnUiThread onFailure(result.message)
                markVerified(d.id)
                showCastRemote(d, host)
            } }
            return
        }
        toast("Получаю TLS fingerprint Cast…")
        cast.inspectTls(host) { result, fp -> runOnUiThread {
            if (!result.ok || fp == null) return@runOnUiThread onFailure(result.message)
            confirmTlsFingerprint("Google Cast", host, fp, {
                val r = cast.approveTls(host, fp); r.ok to r.message
            }) {
                toast("Проверяю Google Cast v2…")
                cast.probe(host) { probe -> runOnUiThread {
                    if (!probe.ok) return@runOnUiThread onFailure(probe.message)
                    markVerified(d.id)
                    showCastRemote(d, host)
                } }
            }
        } }
    }

    private fun showCastRemote(d: NearbyDevice, host: String) {
        val layout = remoteLayout()
        layout.addView(text("Управление активным Cast-сеансом. Приложение не запускает произвольный медиапоток без Cast session.", 13f, c("#AABBD4"), false))
        layout.addView(sectionTitle("ГРОМКОСТЬ"))
        addRemoteRow(layout,
            "−5%" to { runCast(d) { cb -> cast.adjustVolume(host, -0.05, cb) } },
            "Mute" to { runCast(d) { cb -> cast.mute(host, true, cb) } },
            "Unmute" to { runCast(d) { cb -> cast.mute(host, false, cb) } },
            "+5%" to { runCast(d) { cb -> cast.adjustVolume(host, 0.05, cb) } }
        )
        layout.addView(sectionTitle("МЕДИА"))
        addRemoteRow(layout,
            "▶ Play" to { runCast(d) { cb -> cast.media(host, "PLAY", cb) } },
            "Ⅱ Pause" to { runCast(d) { cb -> cast.media(host, "PAUSE", cb) } },
            "■ Stop" to { runCast(d) { cb -> cast.media(host, "STOP", cb) } }
        )
        AlertDialog.Builder(this).setTitle("Google Cast • ${d.name}").setView(layout).setNegativeButton("Закрыть", null).show()
    }

    private fun runCast(d: NearbyDevice, action: (((CastV2Controller.Result) -> Unit)) -> Unit) {
        action { result -> runOnUiThread { toast(result.message); if (result.ok) markVerified(d.id) } }
    }

    private fun showHueRemote(d: NearbyDevice, host: String) {
        val layout = remoteLayout()
        val status = text(
            when {
                hue.isPaired(host) -> "✓ Hue Bridge авторизован • TLS pin сохранён"
                !hue.isTlsTrusted(host) -> "После нажатия кнопки Bridge сначала подтвердите SHA-256 TLS fingerprint, затем будет запрошен application key."
                else -> "TLS pin подтверждён. Нажмите физическую кнопку на Hue Bridge, затем авторизацию ниже."
            },
            13f, if (hue.isPaired(host)) c("#72F1CE") else c("#AABBD4"), false
        )
        layout.addView(status)
        layout.addView(controlRow("🔗 Я нажал кнопку Bridge — авторизовать") {
            ensureHueTls(host) {
                status.text = "Авторизация Hue…"
                hue.pair(host) { result -> runOnUiThread {
                    status.text = result.message
                    toast(result.message)
                    if (result.ok) markVerified(d.id)
                } }
            }
        })
        layout.addView(controlRow("💡 Показать лампы этого Bridge") { openHueLightList(d, host) })
        if (hue.isPaired(host)) layout.addView(controlRow("Забыть Hue Bridge") {
            hue.forget(host); status.text = "Авторизация Hue удалена"
        })
        AlertDialog.Builder(this).setTitle("Philips Hue • ${d.name}").setView(layout).setNegativeButton("Закрыть", null).show()
    }

    private fun openHueLightList(d: NearbyDevice, host: String) {
        toast("Загружаю лампы Hue…")
        hue.listLights(host) { result, lights -> runOnUiThread {
            if (!result.ok) return@runOnUiThread toast(result.message)
            if (lights.isEmpty()) return@runOnUiThread toast("Hue: лампы не найдены")
            val names = lights.map { it.name }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Лампы Hue")
                .setItems(names) { _, which -> showHueLightRemote(d, host, lights[which]) }
                .setNegativeButton("Закрыть", null).show()
        } }
    }

    private fun showHueLightRemote(d: NearbyDevice, host: String, light: HueController.Light) {
        val layout = remoteLayout()
        addRemoteRow(layout,
            "ВКЛ" to { runHue(d) { cb -> hue.power(host, light.id, true, cb) } },
            "ВЫКЛ" to { runHue(d) { cb -> hue.power(host, light.id, false, cb) } }
        )
        layout.addView(sectionTitle("ЯРКОСТЬ"))
        addRemoteRow(layout,
            "25%" to { runHue(d) { cb -> hue.brightness(host, light.id, 25, cb) } },
            "50%" to { runHue(d) { cb -> hue.brightness(host, light.id, 50, cb) } },
            "100%" to { runHue(d) { cb -> hue.brightness(host, light.id, 100, cb) } }
        )
        layout.addView(sectionTitle("ЦВЕТ"))
        addRemoteRow(layout,
            "Красный" to { runHue(d) { cb -> hue.color(host, light.id, 0.7006, 0.2993, cb) } },
            "Зелёный" to { runHue(d) { cb -> hue.color(host, light.id, 0.1724, 0.7468, cb) } },
            "Синий" to { runHue(d) { cb -> hue.color(host, light.id, 0.1355, 0.0399, cb) } },
            "Белый" to { runHue(d) { cb -> hue.color(host, light.id, 0.3227, 0.3290, cb) } }
        )
        AlertDialog.Builder(this).setTitle("Hue • ${light.name}").setView(layout).setNegativeButton("Закрыть", null).show()
    }

    private fun runHue(d: NearbyDevice, action: (((HueController.Result) -> Unit)) -> Unit) {
        action { result -> runOnUiThread { toast(result.message); if (result.ok) markVerified(d.id) } }
    }

    private fun yeelightPort(d: NearbyDevice): Int = d.address.substringAfter(':', "55443").toIntOrNull()?.takeIf { it in 1..65535 } ?: 55443

    private fun probeAndShowYeelight(d: NearbyDevice, host: String) {
        val port = yeelightPort(d)
        toast("Проверяю Yeelight LAN Control…")
        yeelight.probe(host, port) { result -> runOnUiThread {
            if (!result.ok) return@runOnUiThread toast(result.message)
            markVerified(d.id)
            showYeelightRemote(d, host, port)
        } }
    }

    private fun showYeelightRemote(d: NearbyDevice, host: String, port: Int = yeelightPort(d)) {
        val layout = remoteLayout()
        layout.addView(text("LAN Control • $host:$port. Для некоторых моделей его нужно один раз включить в официальном приложении Yeelight.", 13f, c("#AABBD4"), false))
        addRemoteRow(layout,
            "ВКЛ" to { runYeelight(d) { cb -> yeelight.power(host, true, port, cb) } },
            "ВЫКЛ" to { runYeelight(d) { cb -> yeelight.power(host, false, port, cb) } }
        )
        layout.addView(sectionTitle("ЯРКОСТЬ"))
        addRemoteRow(layout,
            "25%" to { runYeelight(d) { cb -> yeelight.brightness(host, 25, port, cb) } },
            "50%" to { runYeelight(d) { cb -> yeelight.brightness(host, 50, port, cb) } },
            "100%" to { runYeelight(d) { cb -> yeelight.brightness(host, 100, port, cb) } }
        )
        layout.addView(sectionTitle("ЦВЕТ"))
        addRemoteRow(layout,
            "Красный" to { runYeelight(d) { cb -> yeelight.color(host, 255, 0, 0, port, cb) } },
            "Зелёный" to { runYeelight(d) { cb -> yeelight.color(host, 0, 255, 0, port, cb) } },
            "Синий" to { runYeelight(d) { cb -> yeelight.color(host, 0, 0, 255, port, cb) } },
            "Белый" to { runYeelight(d) { cb -> yeelight.color(host, 255, 255, 255, port, cb) } }
        )
        AlertDialog.Builder(this).setTitle("Yeelight • ${d.name}").setView(layout).setNegativeButton("Закрыть", null).show()
    }

    private fun runYeelight(d: NearbyDevice, action: (((YeelightController.Result) -> Unit)) -> Unit) {
        action { result -> runOnUiThread { toast(result.message); if (result.ok) markVerified(d.id) } }
    }

    private fun probeAndShowWled(d: NearbyDevice, host: String) {
        toast("Проверяю WLED API…")
        wled.probe(host) { result -> runOnUiThread {
            if (!result.ok) {
                AlertDialog.Builder(this).setTitle("Свет • ${d.name}")
                    .setMessage("Устройство похоже на свет/умный дом, но поддерживаемый WLED API не найден. Matter, HomeKit, Tuya и Hue требуют отдельной штатной авторизации; приложение не посылает случайные команды.")
                    .setPositiveButton("Понятно", null).show()
                return@runOnUiThread
            }
            showWledRemote(d, host, result.message)
        } }
    }

    private fun showWledRemote(d: NearbyDevice, host: String, info: String) {
        val layout = remoteLayout()
        layout.addView(text(info, 13f, c("#72F1CE"), false))
        addRemoteRow(layout,
            "ВКЛ" to { runWled(d) { cb -> wled.power(host, true, cb) } },
            "ВЫКЛ" to { runWled(d) { cb -> wled.power(host, false, cb) } }
        )
        layout.addView(sectionTitle("ЯРКОСТЬ"))
        addRemoteRow(layout,
            "25%" to { runWled(d) { cb -> wled.brightness(host, 64, cb) } },
            "50%" to { runWled(d) { cb -> wled.brightness(host, 128, cb) } },
            "100%" to { runWled(d) { cb -> wled.brightness(host, 255, cb) } }
        )
        layout.addView(sectionTitle("ЦВЕТ"))
        addRemoteRow(layout,
            "Красный" to { runWled(d) { cb -> wled.color(host, 255, 0, 0, cb) } },
            "Зелёный" to { runWled(d) { cb -> wled.color(host, 0, 255, 0, cb) } },
            "Синий" to { runWled(d) { cb -> wled.color(host, 0, 0, 255, cb) } }
        )
        addRemoteRow(layout,
            "Белый" to { runWled(d) { cb -> wled.color(host, 255, 255, 255, cb) } },
            "Тёплый" to { runWled(d) { cb -> wled.color(host, 255, 147, 41, cb) } }
        )
        AlertDialog.Builder(this).setTitle("WLED • ${d.name}").setView(layout).setNegativeButton("Закрыть", null).show()
    }

    private fun runWled(d: NearbyDevice, action: (((WledController.Result) -> Unit)) -> Unit) {
        action { result -> runOnUiThread { toast(result.message); if (result.ok) markVerified(d.id) } }
    }

    private fun remoteLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(6), dp(18), dp(8))
    }

    private fun sectionTitle(value: String) = text(value, 11f, c("#65E6C4"), true).apply { setPadding(0, dp(12), 0, dp(5)); letterSpacing = .08f }

    private fun addDpad(layout: LinearLayout, up: () -> Unit, left: () -> Unit, ok: () -> Unit, right: () -> Unit, down: () -> Unit) {
        addRemoteRow(layout, "" to {}, "▲" to up, "" to {})
        addRemoteRow(layout, "◀" to left, "OK" to ok, "▶" to right)
        addRemoteRow(layout, "" to {}, "▼" to down, "" to {})
    }

    private fun addRemoteRow(layout: LinearLayout, vararg actions: Pair<String, () -> Unit>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.forEach { pair ->
            if (pair.first.isBlank()) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            } else {
                row.addView(remoteKey(pair.first, pair.second), LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
        }
        layout.addView(row)
    }

    private fun remoteKey(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
        background = rounded(c("#172946"), 12, c("#294466")); setOnClickListener { action() }
    }

    private fun showPairingInfo(d: NearbyDevice? = null) {
        val extra = when {
            d?.protocol?.contains("Matter", true) == true || d?.protocol?.contains("HomeKit", true) == true -> "\n\nMatter/HomeKit требуют штатного commissioning/pairing экосистемы; универсальный контроллер для них ещё не добавлен."
            isAppleMobile(d ?: NearbyDevice("", "", "", "", "")) -> "\n\niPhone/iPad: без Companion доступны только штатно опубликованные Apple/Bonjour/MDM-функции. PIN/код блокировки не является сетевым паролем. Для Companion используйте добровольное pairing."
            d?.brand?.contains("Tuya", true) == true || d?.protocol?.contains("Tuya", true) == true -> "\n\nTuya/Smart Life требует локальный ключ или облачную авторизацию; обход авторизации не выполняется."
            d?.protocol?.contains("Wi-Fi", true) == true || d?.id?.startsWith("wifi:") == true -> "\n\nЭто объект Wi‑Fi эфира (SSID/BSSID). Если вы знаете пароль этой Wi‑Fi сети, сначала подключите Android к ней через системные настройки, затем вернитесь в UniversalRemote и повторите поиск. Пароль Wi‑Fi даёт доступ к сети, но не является универсальным паролем управления устройством."
            else -> ""
        }
        val radioOnly = d?.protocol?.contains("Wi-Fi", true) == true || d?.id?.startsWith("wifi:") == true
        val bluetoothOnly = d?.let { isBleDevice(it) || isClassicBluetoothDevice(it) } == true
        val hasHost = d?.ipAddress != null || d?.let { hostOf(it.address) } != null
        val positiveLabel = when {
            radioOnly && !hasHost -> "Wi‑Fi / сканировать LAN"
            bluetoothOnly && !hasHost -> "Bluetooth pairing"
            else -> "Подключение / пароль"
        }
        AlertDialog.Builder(this).setTitle("Нужна штатная авторизация")
            .setMessage("Устройство найдено, но управление возможно только через поддерживаемый штатный протокол с PIN, подтверждением на экране, pairing-токеном или паролем конкретного API. Защиту устройства приложение не обходит.$extra")
            .setNegativeButton("Закрыть", null)
            .setPositiveButton(positiveLabel) { _, _ ->
        d?.let { device ->
            when {
                radioOnly && !hasHost -> AlertDialog.Builder(this)
                    .setTitle("Wi‑Fi • ${device.name}")
                    .setMessage(
                        "Пароль Wi‑Fi даёт доступ к сети, но не является паролем управления всеми устройствами. " +
                            "Если Android уже подключён к нужной сети, запустите LAN-сканирование — UniversalRemote найдёт реальные IP/API этой сети."
                    )
                    .setItems(arrayOf(
                        "✓ Я уже подключён — сканировать LAN",
                        "⚙ Открыть настройки Wi‑Fi"
                    )) { _, which ->
                        if (which == 0) {
                            toast("Сканирую текущую Wi‑Fi/LAN сеть…")
                            startScan()
                        } else {
                            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                                .onFailure { toast("Не удалось открыть настройки Wi‑Fi") }
                        }
                    }
                    .setNegativeButton("Закрыть", null)
                    .show()
                isBleDevice(device) -> showBleGattPanel(device)
                isClassicBluetoothDevice(device) -> showClassicBluetoothPanel(device)
                else -> showConnectionOptions(device)
            }
        }
    }
            .show()
    }

    private fun showUpnpRemote(d: NearbyDevice) {
        val url = d.descriptionUrl ?: return
        val host = d.ipAddress ?: hostOf(url) ?: return toast("UPnP: IP устройства не определён")
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), 0) }
        layout.addView(controlRow("Громкость −") { runUpnp(d) { cb -> upnp.adjustVolume(host, url, -5, cb) } })
        layout.addView(controlRow("Громкость +") { runUpnp(d) { cb -> upnp.adjustVolume(host, url, 5, cb) } })
        layout.addView(controlRow("Mute") { runUpnp(d) { cb -> upnp.setMute(host, url, true, cb) } })
        layout.addView(controlRow("Unmute") { runUpnp(d) { cb -> upnp.setMute(host, url, false, cb) } })
        layout.addView(controlRow("▶ Play") { runUpnp(d) { cb -> upnp.media(host, url, "Play", cb) } })
        layout.addView(controlRow("Ⅱ Pause") { runUpnp(d) { cb -> upnp.media(host, url, "Pause", cb) } })
        layout.addView(controlRow("■ Stop") { runUpnp(d) { cb -> upnp.media(host, url, "Stop", cb) } })
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
        layout.addView(powerOffRow("⏻ ВЫКЛЮЧИТЬ ПРОЕКТОР") { sendPjLink(d, password, host) { pw, cb -> pjlink.power(host, false, pw, cb) } })
        layout.addView(controlRow("Громкость −") { sendPjLink(d, password, host) { pw, cb -> pjlink.volume(host, false, pw, cb) } })
        layout.addView(controlRow("Громкость +") { sendPjLink(d, password, host) { pw, cb -> pjlink.volume(host, true, pw, cb) } })
        layout.addView(controlRow("Mute") { sendPjLink(d, password, host) { pw, cb -> pjlink.mute(host, true, pw, cb) } })
        layout.addView(controlRow("Unmute") { sendPjLink(d, password, host) { pw, cb -> pjlink.mute(host, false, pw, cb) } })
        AlertDialog.Builder(this).setTitle("PJLink • $host").setMessage("Если проектор не требует пароль — оставьте поле пустым. Если требует — пароль используется только для текущей команды и не сохраняется на диск.")
            .setView(layout).setNegativeButton("Закрыть", null).show()
    }

    private fun sendPjLink(d: NearbyDevice, passwordField: EditText, host: String, action: (CharArray?, (PjLinkController.Result) -> Unit) -> Unit) {
        val editable = passwordField.text
        val password = if (editable != null && editable.any { !it.isWhitespace() }) CharArray(editable.length) { i -> editable[i] } else null
        editable?.clear()
        action(password) { result -> runOnUiThread { toast(result.message); if (result.ok) markVerified(d.id) } }
    }

    private fun markVerified(id: String) {
        val existing = devices[id] ?: return
        devices[id] = existing.copy(controllable = true, verified = true)
        scheduleRender()
    }

    private fun matchesKind(d: NearbyDevice, label: String): Boolean = when (label) {
        "Телевизоры" -> d.kind.contains("ТВ") || d.kind.contains("Телевизор")
        "Колонки / аудио" -> d.kind.contains("Колонка") || d.kind.contains("Аудио") || d.kind.contains("Наушники") || d.kind.contains("медиаплеер")
        "Свет" -> d.kind.contains("Лампа") || d.kind.contains("свет") || d.kind.contains("дом")
        "Компьютеры" -> d.kind.contains("Компьютер") || d.kind.contains("сервер")
        "Проекторы" -> d.kind.contains("Проектор")
        "Телефоны" -> d.kind.contains("Телефон") || d.kind.contains("iPhone", true) || d.kind.contains("iPad", true)
        "Принтеры" -> d.kind.contains("Принтер")
        "Другие" -> listOf("ТВ", "Телевизор", "Колонка", "Аудио", "Наушники", "Лампа", "свет", "Компьютер", "сервер", "Проектор", "Телефон", "iPhone", "iPad", "Принтер").none { d.kind.contains(it) }
        else -> true
    }

    private fun capabilityName(cap: ControlCapability) = when (cap) {
        ControlCapability.POWER -> "питание"
        ControlCapability.VOLUME -> "громкость"
        ControlCapability.MUTE -> "mute"
        ControlCapability.MEDIA -> "медиа"
        ControlCapability.NAVIGATION -> "навигация"
        ControlCapability.CHANNEL -> "каналы"
        ControlCapability.INPUT -> "входы"
        ControlCapability.LIGHT_POWER -> "свет"
        ControlCapability.BRIGHTNESS -> "яркость"
        ControlCapability.COLOR -> "цвет"
        ControlCapability.FIND_DEVICE -> "найти устройство"
    }

    private fun isInCurrentWifiSubnet(host: String): Boolean {
        val target = Ipv4Range.parseIp(host) ?: return false
        if (!Ipv4Range.isPrivate(target)) return false
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return false
        val local = cm.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return false
        val own = Ipv4Range.ipv4ToInt(local.address as Inet4Address)
        val prefix = local.prefixLength.coerceIn(8, 30)
        val mask = -1 shl (32 - prefix)
        return (target and mask) == (own and mask)
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
    private fun powerOffRow(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        background = rounded(c("#5A1F2B"), 12, c("#B54A5D")); setOnClickListener { action() }
    }
    private fun gearButton(action: () -> Unit) = TextView(this).apply {
    text = "⚙"
    textSize = 28f
    gravity = Gravity.CENTER
    setTextColor(Color.WHITE)
    contentDescription = "Настройки"
    isClickable = true
    isFocusable = true
    background = rounded(c("#172946"), 14, c("#65E6C4"))
    setOnClickListener { action() }
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
        stopScan(); upnp.close(); pjlink.close(); roku.close(); samsung.close(); wled.close(); cast.close(); yeelight.close(); lgWebOs.close(); hue.close(); androidTv.close(); bleGatt.close(); analyzer.close(); wol.close(); healthProbe.close()
        WifiNetworkResolver.unbindProcess(this)
        super.onDestroy()
    }
}
