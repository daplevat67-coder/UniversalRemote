package com.example.universalremote.protocol;

import java.util.Arrays;
import java.util.Base64;

/** Dependency-free regression checks, also run by the Android JUnit suite. */
public final class ProtocolSecurityChecks {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        byte[] session = new byte[32];
        for (int i = 0; i < session.length; i++) session[i] = (byte) i;
        byte[] key = ResponseAuthentication.responseKey(session);
        String binding = "request:known-request";
        String body = "{\"ok\":true,\"message\":\"Готово\"}";
        byte[] signature = ResponseAuthentication.sign(key, binding, 200, body);
        check(Base64.getEncoder().encodeToString(key).equals("58K4jB9mbUwdCgnaX7cbcbh6ysRpyQEm2E/8C27mMkA="), "response key vector");
        check(Base64.getEncoder().encodeToString(signature).equals("fXUuCrQSr+xYyq9NlgWtNRSTd2L9/lfz+jwdtaN/SdQ="), "UTF-8 response vector shared with Swift");
        check(ResponseAuthentication.verify(key, binding, 200, body, signature), "valid response rejected");
        check(!ResponseAuthentication.verify(key, binding, 401, body, signature), "forged status accepted");
        check(!ResponseAuthentication.verify(key, binding, 200, body + " ", signature), "modified body accepted");
        check(!ResponseAuthentication.verify(key, "request:other-request", 200, body, signature), "replayed response accepted");
        check(!ResponseAuthentication.verify(key, "pair:known-request", 200, body, signature), "cross-endpoint response accepted");
        check(!ResponseAuthentication.verify(new byte[32], binding, 200, body, signature), "wrong key accepted");
        check(!ResponseAuthentication.verify(key, binding, 200, body, null), "unsigned response accepted");
        check(!ResponseAuthentication.verify(key, binding, 200, body, Arrays.copyOf(signature, 31)), "truncated signature accepted");
        signature[0] ^= 1;
        check(!ResponseAuthentication.verify(key, binding, 200, body, signature), "modified signature accepted");
        for (String host : new String[]{"192.168.1.1", "10.0.0.1", "172.16.0.1", "169.254.1.1"}) {
            check(LiteralIpv4.parse(host) != null, "valid IPv4 rejected: " + host);
        }
        for (String host : new String[]{"tv.local", "attacker.example", "::1", "::ffff:192.168.1.1", "192.168.1", "3232235777", "0xc0a80101", "192.168.001.1", "192.168.1.256", " 192.168.1.1", "192.168.1.1.", "192.168.1.-1", "192.168.1.+1", "192.168.1.１"}) {
            check(LiteralIpv4.parse(host) == null, "non-canonical/DNS host accepted: " + host);
        }
        System.out.println("Protocol security checks passed");
    }
}
