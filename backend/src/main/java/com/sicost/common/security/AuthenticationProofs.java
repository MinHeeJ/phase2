package com.sicost.common.security;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.HexFormat;

/** Raw proofs are ephemeral; persistence receives only hash() output. */
public final class AuthenticationProofs {
    private static final SecureRandom RANDOM = new SecureRandom();
    private AuthenticationProofs() {}
    public static String randomId() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String otp() { return String.format(java.util.Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000)); }
    public static String hash(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("인증 증명을 확인하세요.");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("인증 해시를 사용할 수 없습니다."); }
    }
    public static boolean matches(String raw, String storedHash) {
        if (raw == null || raw.isEmpty() || storedHash == null || !storedHash.matches("[0-9a-f]{64}")) return false;
        return MessageDigest.isEqual(hash(raw).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }
    public static String otpHash(String chainSecret, String code) {
        if (chainSecret == null || chainSecret.length() < 32 || code == null || !code.matches("[0-9]{6}"))
            throw new IllegalArgumentException("인증 증명을 확인하세요.");
        return hash(chainSecret + ":" + code);
    }
}
