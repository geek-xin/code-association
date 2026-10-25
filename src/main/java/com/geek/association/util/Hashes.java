package com.geek.association.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Hashes {

    private Hashes() {
    }

    public static String sha256(String value) {
        return digest("SHA-256", value == null ? "" : value);
    }

    public static String sha1(String value) {
        return digest("SHA-1", value == null ? "" : value);
    }

    public static String shortHash(String value) {
        String h = sha256(value);
        return h.length() > 12 ? h.substring(0, 12) : h;
    }

    private static String digest(String algorithm, String value) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] bytes = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
