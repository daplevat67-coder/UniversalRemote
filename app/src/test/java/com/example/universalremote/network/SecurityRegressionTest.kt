package com.example.universalremote.network

import com.example.universalremote.protocol.ProtocolSecurityChecks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityRegressionTest {
    @Test fun responseAuthenticationAndLiteralAddresses() = ProtocolSecurityChecks.main(emptyArray())

    @Test fun advertisedEndpointsCannotEscapeTheirSource() {
        val source = "192.168.1.20"
        val schemes = setOf("http", "https")
        assertTrue(LocalEndpointPolicy.samePrivateHost(source, "http://$source:8000/control", schemes))
        for (url in listOf(
            "http://192.168.1.21/control", "http://8.8.8.8/control",
            "http://tv.local/control", "http://attacker.example/control",
            "http://[::1]/control", "http://[::ffff:192.168.1.20]/control",
            "http://192.168.001.20/control", "http://user@$source/control",
            "http://$source:0/control", "http://$source:65536/control",
            "http://$source/control#fragment", "ftp://$source/control"
        )) assertFalse(url, LocalEndpointPolicy.samePrivateHost(source, url, schemes))
        assertFalse(LocalEndpointPolicy.samePrivateHost("tv.local", "http://$source/control", schemes))
    }
}
