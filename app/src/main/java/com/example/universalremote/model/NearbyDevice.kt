package com.example.universalremote.model

data class NearbyDevice(
    val id: String,
    val name: String,
    val kind: String,
    val protocol: String,
    val address: String,
    val controllable: Boolean = false
)
