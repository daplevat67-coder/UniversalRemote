package com.example.universalremote.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
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

class CompanionMainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var code: TextView
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() {
            code.text = CompanionService.currentPairCode ?: "———— ———— ————"
            status.text = (CompanionService.currentStatus ?: "Служба не запущена") + if (CompanionAccessibilityService.isEnabled()) "\nAccessibility: ✓" else "\nAccessibility: выключен"
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 64, 32, 32)
            setBackgroundColor(Color.rgb(7, 17, 31))
        }
        root.addView(label("UNIVERSALREMOTE COMPANION • v0.8.0", 13f, Color.rgb(101, 230, 196), true))
        root.addView(label("Этот телефон можно управлять только после явного сопряжения.", 20f, Color.WHITE, true).apply { setPadding(0, 22, 0, 18) })
        root.addView(label("Одноразовый код", 13f, Color.LTGRAY, false))
        code = label("———— ———— ————", 30f, Color.rgb(114, 241, 206), true).apply { setPadding(0, 8, 0, 18) }
        root.addView(code)
        status = label("Служба не запущена", 14f, Color.LTGRAY, false).apply { setPadding(0, 0, 0, 18) }
        root.addView(status)
        root.addView(button("Запустить Companion") { startCompanion() })
        root.addView(button("Новый pairing-код") { startService(Intent(this, CompanionService::class.java).setAction(CompanionService.ACTION_ROTATE)) })
        root.addView(button("Разрешить Home / Back / Recents") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        root.addView(button("Остановить Companion") { startService(Intent(this, CompanionService::class.java).setAction(CompanionService.ACTION_STOP)) })
        root.addView(label("Без Accessibility доступны громкость и media-кнопки. Companion не читает экран, не обходит PIN/lockscreen и принимает команды только от сопряжённого UniversalRemote в локальной сети.", 13f, Color.LTGRAY, false).apply { setPadding(0, 22, 0, 0) })
        setContentView(root)
        requestNotificationIfNeeded()
    }

    override fun onStart() { super.onStart(); handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }

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
