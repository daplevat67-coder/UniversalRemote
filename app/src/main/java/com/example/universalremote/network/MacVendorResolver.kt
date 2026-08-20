package com.example.universalremote.network

object MacVendorResolver {
    private val common = mapOf(
        "001A11" to "Google",
        "F4F5D8" to "Google",
        "B827EB" to "Raspberry Pi Foundation",
        "DCA632" to "Raspberry Pi Trading",
        "3C5A37" to "Samsung",
        "001632" to "Samsung",
        "F0D1A9" to "Samsung",
        "001CB3" to "Apple",
        "3C0754" to "Apple",
        "F0B479" to "Apple",
        "001D73" to "Buffalo",
        "001E58" to "D-Link",
        "001F3F" to "AVM",
        "001788" to "Philips Lighting",
        "ECB5FA" to "Philips Hue",
        "B8E937" to "Sonos",
        "5CAAFD" to "Sonos",
        "001A7D" to "cyber-blue / audio device",
        "0015C1" to "Sony",
        "0025E5" to "LG Electronics",
        "0019E3" to "TP-Link",
        "50C7BF" to "TP-Link",
        "C46E1F" to "TP-Link",
        "7811DC" to "Xiaomi",
        "286C07" to "Xiaomi",
        "D4F547" to "Xiaomi",
        "E8ABFA" to "Huawei",
        "10C6FC" to "Garmin",
        "18B430" to "Nest / Google",
        "2C3F0B" to "Cisco",
        "000C29" to "VMware",
        "005056" to "VMware",
        "080027" to "VirtualBox"
    )

    fun resolve(mac: String?): String? {
        val normalized = mac?.replace(":", "")?.replace("-", "")?.uppercase() ?: return null
        if (normalized.length < 6) return null
        return common[normalized.take(6)]
    }
}
