package com.example.universalremote.model

data class NearbyDevice(
    val id: String,
    val name: String,
    val kind: String,
    val protocol: String,
    val address: String,
    val controllable: Boolean = false,
    val signalDbm: Int? = null,
    val distanceMeters: Double? = null,
    val brand: String? = null,
    val capabilities: Set<ControlCapability> = emptySet(),
    val descriptionUrl: String? = null,
    val verified: Boolean = false
)
