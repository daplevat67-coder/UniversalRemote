package com.example.universalremote.model

data class PortService(
    val port: Int,
    val service: String,
    val banner: String? = null
)
