package com.example.universalremote.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEndpointPolicyTest {
    @Test fun privateAddressesMustBeNumericLiterals() {
        assertTrue(LocalEndpointPolicy.isPrivateIpv4("192.168.1.10"))
        assertTrue(LocalEndpointPolicy.isPrivateIpv4("10.0.0.2"))
        assertFalse(LocalEndpointPolicy.isPrivateIpv4("8.8.8.8"))
        assertFalse(LocalEndpointPolicy.isPrivateIpv4("127.0.0.1"))
        assertFalse(LocalEndpointPolicy.isPrivateIpv4("localhost"))
        assertFalse(LocalEndpointPolicy.isPrivateIpv4("192.168.001.010"))
        assertFalse(LocalEndpointPolicy.isPrivateIpv4("192.168.1.999"))
        assertFalse(LocalEndpointPolicy.isPrivateIpv4("192.168.1.10.evil.test"))
    }

    @Test fun discoveredUrlsMustMatchAdvertisingHost() {
        val allowed = setOf("http", "https")
        assertTrue(LocalEndpointPolicy.samePrivateHost("192.168.1.10", "http://192.168.1.10:8000/desc.xml", allowed))
        assertTrue(LocalEndpointPolicy.samePrivateHost("192.168.1.10", "https://192.168.1.10/control", allowed))
        assertFalse(LocalEndpointPolicy.samePrivateHost("192.168.1.10", "http://192.168.1.11:8000/", allowed))
        assertFalse(LocalEndpointPolicy.samePrivateHost("192.168.1.10", "http://10.0.0.3/", allowed))
        assertFalse(LocalEndpointPolicy.samePrivateHost("192.168.1.10", "http://localhost/control", allowed))
        assertFalse(LocalEndpointPolicy.samePrivateHost("192.168.1.10", "http://192.168.1.10.evil.test/", allowed))
        assertFalse(LocalEndpointPolicy.samePrivateHost("192.168.1.10", "http://user@192.168.1.10/", allowed))
        assertFalse(LocalEndpointPolicy.samePrivateHost("192.168.1.10", "ftp://192.168.1.10/", allowed))
        assertFalse(LocalEndpointPolicy.samePrivateHost("192.168.1.10", "not a url", allowed))
    }
}
