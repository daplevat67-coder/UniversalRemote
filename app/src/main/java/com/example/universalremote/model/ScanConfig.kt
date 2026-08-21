package com.example.universalremote.model

data class ScanConfig(
    val range: String = "auto",
    val connectTimeoutMs: Int = 220,
    val bannerTimeoutMs: Int = 450,
    val parallelism: Int = 32,
    val ports: List<Int> = DEFAULT_PORTS,
    val scanPorts: Boolean = true
) {
    companion object {
        val DEFAULT_PORTS = listOf(21, 22, 23, 53, 80, 139, 443, 445, 554, 631, 1883, 3389, 3000, 3001, 4352, 5000, 5001, 6466, 6467, 8001, 8002, 8008, 8009, 8060, 8080, 8443, 8883, 9100, 45123, 55443, 62078)
    }
}
