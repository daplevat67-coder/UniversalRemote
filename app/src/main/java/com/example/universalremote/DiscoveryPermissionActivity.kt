package com.example.universalremote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * First-run permission gate for discovery.
 *
 * UniversalRemote targets API 36. On Android 16 local-network protection can gate raw LAN sockets,
 * mDNS and SSDP behind the Nearby devices permission. Wi-Fi scan results still need precise
 * location on supported Android releases, and Bluetooth discovery needs its own runtime grants.
 *
 * The user sees the reason before Android's permission dialog. No permission is silently assumed.
 */
class DiscoveryPermissionActivity : AppCompatActivity() {
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasEssentialDiscoveryPermissions()) {
            openMain()
        } else {
            showDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        if (hasEssentialDiscoveryPermissions()) {
            openMain()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(44), dp(26), dp(28))
            setBackgroundColor(Color.rgb(7, 17, 31))
        }

        root.addView(label("UNIVERSAL REMOTE • v${BuildConfig.VERSION_NAME}", 13f, Color.rgb(101, 230, 196), true))
        root.addView(label("Разрешить поиск устройств", 28f, Color.WHITE, true).apply {
            setPadding(0, dp(18), 0, dp(12))
        })
        root.addView(label(
            "Для поиска телевизоров, телефонов, колонок, света и других устройств в вашей локальной сети UniversalRemote использует Nearby devices, Wi‑Fi/LAN discovery и Bluetooth.\n\n" +
                "• Nearby devices нужен для локальной сети и Wi‑Fi discovery.\n" +
                "• Точная геолокация нужна Android для списка Wi‑Fi точек доступа.\n" +
                "• Bluetooth Scan/Connect нужны только для Bluetooth-устройств.\n\n" +
                "UniversalRemote не использует эти разрешения для фонового отслеживания местоположения.",
            15f,
            Color.LTGRAY,
            false
        ))

        root.addView(button("РАЗРЕШИТЬ ПОИСК УСТРОЙСТВ") {
            requestPermissions.launch(requiredPermissions())
        }.apply { setPadding(dp(12), 0, dp(12), 0) }, LinearLayout.LayoutParams(-1, dp(56)).apply {
            topMargin = dp(24)
        })

        root.addView(button("Открыть разрешения приложения") { openAppSettings() }, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(10)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (hasEssentialDiscoveryPermissions()) openMain()
    }

    private fun requiredPermissions(): Array<String> = buildList {
        // Wi-Fi scan results require precise location on current supported Android releases.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()

    private fun hasEssentialDiscoveryPermissions(): Boolean {
        val required = requiredPermissions()
        return required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun showDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Поиск ограничен разрешениями")
            .setMessage(
                "Android не выдал все разрешения, необходимые для полного поиска. На Android 16 без Nearby devices LAN/mDNS/SSDP могут возвращать 0 устройств. Разрешите доступ в настройках приложения и повторите поиск."
            )
            .setNegativeButton("Остаться здесь", null)
            .setPositiveButton("Открыть настройки") { _, _ -> openAppSettings() }
            .show()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    private fun openMain() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
