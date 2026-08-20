package com.example.universalremote.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.universalremote.R
import com.example.universalremote.model.NearbyDevice

class DeviceAdapter(
    private val onClick: (NearbyDevice) -> Unit
) : ListAdapter<NearbyDevice, DeviceAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(parent.context)

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(context: Context) : RecyclerView.ViewHolder(
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14), dp(context, 10), dp(context, 12), dp(context, 10))
            background = rounded(context, Color.parseColor("#172946"), 18, Color.parseColor("#294466"))
        }
    ) {
        private val context = itemView.context
        private val row = itemView as LinearLayout
        private val icon = ImageView(context).apply {
            setPadding(dp(context, 13), dp(context, 13), dp(context, 13), dp(context, 13))
            background = rounded(context, Color.parseColor("#274164"), 14)
            setColorFilter(Color.WHITE)
        }
        private val name = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        private val details = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#91A5C2"))
            maxLines = 2
            setPadding(0, dp(context, 3), 0, 0)
        }
        private val state = TextView(context).apply {
            textSize = 18f
            gravity = Gravity.CENTER
        }

        init {
            row.addView(icon, LinearLayout.LayoutParams(dp(context, 54), dp(context, 54)))
            val copy = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 14), 0, dp(context, 6), 0)
                addView(name)
                addView(details)
            }
            row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(state, LinearLayout.LayoutParams(dp(context, 34), dp(context, 54)))
        }

        fun bind(device: NearbyDevice) {
            name.text = device.name.take(48)
            icon.setImageResource(iconFor(device.kind))
            val proximity = when {
                device.distanceMeters != null -> formatDistance(device.distanceMeters)
                device.protocol.startsWith("Wi‑Fi эфир") && device.signalDbm != null -> "${device.signalDbm} dBm"
                else -> "LAN"
            }
            val ip = device.ipAddress?.let { "IP $it" }
            val ports = device.openPorts.takeIf { it.isNotEmpty() }?.let { "${it.size} TCP" }
            val line = listOfNotNull(device.hardwareVendor ?: device.brand, device.protocol, ip, ports, proximity).joinToString(" • ")
            details.text = line
            state.text = if (device.verified) "✓" else if (device.controllable) "›" else "·"
            state.setTextColor(
                if (device.verified || device.controllable) Color.parseColor("#72F1CE")
                else Color.parseColor("#647995")
            )
            row.setOnClickListener { onClick(device) }
        }
    }

    companion object {
        private val Diff = object : DiffUtil.ItemCallback<NearbyDevice>() {
            override fun areItemsTheSame(oldItem: NearbyDevice, newItem: NearbyDevice) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: NearbyDevice, newItem: NearbyDevice) = oldItem == newItem
        }

        fun formatDistance(meters: Double): String = when {
            meters < 1.0 -> "≈ %.1f м".format(meters)
            meters < 100.0 -> "≈ %.0f м".format(meters)
            meters < 1000.0 -> "100+ м (очень примерно)"
            else -> "далеко / RSSI неточен"
        }

        fun iconFor(kind: String): Int = when {
            kind.contains("Телевизор") || kind.contains("ТВ") -> R.drawable.ic_device_tv
            kind.contains("Лампа") || kind.contains("свет") || kind.contains("дом") -> R.drawable.ic_device_light
            kind.contains("Проектор") -> R.drawable.ic_device_projector
            kind.contains("Компьютер") -> R.drawable.ic_device_computer
            kind.contains("Телефон") -> R.drawable.ic_device_phone
            kind.contains("Колонка") || kind.contains("Аудио") || kind.contains("Наушники") -> R.drawable.ic_device_speaker
            kind.contains("Принтер") -> R.drawable.ic_device_printer
            kind.contains("Часы") -> R.drawable.ic_device_watch
            kind.contains("Аксессуар") -> R.drawable.ic_device_gamepad
            kind.contains("Bluetooth") -> R.drawable.ic_device_bluetooth
            else -> R.drawable.ic_device_other
        }

        private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
        private fun rounded(context: Context, fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(context, radius).toFloat()
            if (stroke != null) setStroke(dp(context, 1), stroke)
        }
    }
}
