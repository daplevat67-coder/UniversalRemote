package com.example.universalremote.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.max

class CompanionMainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var code: TextView
    private lateinit var status: TextView
    private lateinit var pairedBox: LinearLayout
    private var lastPairedSignature = ""

    private val refresh = object : Runnable {
        override fun run() {
            code.text = CompanionService.currentPairCode ?: "———— ———— ————"
            val seconds = max(0L, (CompanionService.currentPairCodeExpiresAt - System.currentTimeMillis()) / 1000L)
            status.text = buildString {
                append(CompanionService.currentStatus ?: "Служба не запущена")
                append("\nPairing-код: ")
                append(if (seconds > 0) "ещё ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "истёк")
                append(if (CompanionAccessibilityService.isEnabled()) "\nAccessibility: ✓" else "\nAccessibility: выключен")
            }
            refreshPairedControllers()
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 56, 32, 32)
            setBackgroundColor(Color.rgb(7, 17, 31))
        }
        root.addView(label("UNIVERSALREMOTE COMPANION • v0.9.0", 13f, Color.rgb(101, 230, 196), true))
        root.addView(label("Этот телефон можно управлять только после явного сопряжения.", 20f, Color.WHITE, true).apply { setPadding(0, 22, 0, 18) })
        root.addView(label("Одноразовый код • действует 5 минут", 13f, Color.LTGRAY, false))
        code = label("———— ———— ————", 30f, Color.rgb(114, 241, 206), true).apply { setPadding(0, 8, 0, 12) }
        root.addView(code)
        status = label("Служба не запущена", 14f, Color.LTGRAY, false).apply { setPadding(0, 0, 0, 18) }
        root.addView(status)
        root.addView(button("Запустить Companion") { startCompanion() })
        root.addView(button("Новый pairing-код") { startService(Intent(this, CompanionService::class.java).setAction(CompanionService.ACTION_ROTATE)) })
        root.addView(button("Разрешить Home / Back / Recents") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })

        root.addView(label("СОПРЯЖЁННЫЕ ПУЛЬТЫ", 13f, Color.rgb(101, 230, 196), true).apply { setPadding(0, 24, 0, 8) })
        pairedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pairedBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(button("Отозвать все пульты") {
            startService(Intent(this, CompanionService::class.java).setAction(CompanionService.ACTION_REVOKE_ALL))
        })
        root.addView(button("Остановить Companion") { startService(Intent(this, CompanionService::class.java).setAction(CompanionService.ACTION_STOP)) })
        root.addView(label("Команды v2 шифруются AES-GCM поверх pairing session key. Companion слушает только текущий Wi-Fi интерфейс; сессии истекают и могут быть отозваны здесь. Без Accessibility доступны громкость и media-кнопки.", 13f, Color.LTGRAY, false).apply { setPadding(0, 22, 0, 0) })
        setContentView(root)
        requestNotificationIfNeeded()
    }

    override fun onStart() { super.onStart(); handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }

    private fun refreshPairedControllers() {
        val snapshot = CompanionService.pairedControllers
        val signature = snapshot.joinToString("|") { "${it.clientId}:${it.name}:${it.expiresAt}" }
        if (signature == lastPairedSignature) return
        lastPairedSignature = signature
        pairedBox.removeAllViews()
        if (snapshot.isEmpty()) {
            pairedBox.addView(label("Нет доверенных пультов", 13f, Color.LTGRAY, false))
            return
        }
        snapshot.forEach { controller ->
            val days = max(0L, (controller.expiresAt - System.currentTimeMillis()) / (24L * 60L * 60L * 1000L))
            pairedBox.addView(label("${controller.name} • сессия ≤ ${days + 1} дн.", 13f, Color.WHITE, false))
            pairedBox.addView(button("Отозвать ${controller.name}") {
                startService(Intent(this, CompanionService::class.java)
                    .setAction(CompanionService.ACTION_REVOKE)
                    .putExtra(CompanionService.EXTRA_CLIENT_ID, controller.clientId))
            })
        }
    }

    private fun startCompanion() {
        ContextCompat.startForegroundService(this, Intent(this, CompanionService::class.java))
    }

    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 80)
        }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { action() }
    }
}
