package com.example.universalremote.protocol;

/** Canonical dotted IPv4 only. Never consult DNS or accept alternative numeric forms. */
public final class LiteralIpv4 {
    private LiteralIpv4() {}
    public static byte[] parse(String host) {
        if (host == null) return null;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) return null;
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') return null;
                value = value * 10 + c - '0';
            }
            if (value > 255) return null;
            result[i] = (byte) value;
        }
        return result;
    }
}
