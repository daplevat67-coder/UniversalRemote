package com.example.universalremote.protocol;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Response authentication v1: separate key, exact body/status, and unique request binding. */
public final class ResponseAuthentication {
    public static final String HEADER = "X-UR-Response-Signature";
    private ResponseAuthentication() {}

    public static byte[] responseKey(byte[] sessionKey) {
        return hmac(sessionKey, "UniversalRemote Companion response key v1");
    }

    public static byte[] sign(byte[] responseKey, String binding, int status, String body) {
        return hmac(responseKey, "response-v1\n" + binding + "\n" + status + "\n" + body);
    }

    public static boolean verify(byte[] responseKey, String binding, int status, String body, byte[] signature) {
        return signature != null && signature.length == 32 &&
                MessageDigest.isEqual(sign(responseKey, binding, status, body), signature);
    }

    private static byte[] hmac(byte[] key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
