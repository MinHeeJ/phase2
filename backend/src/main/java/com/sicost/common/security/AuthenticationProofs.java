package com.sicost.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class AuthenticationProofs {
    private static final SecureRandom RANDOM=new SecureRandom();
    private AuthenticationProofs() {}
    public static String randomSessionId() {
        byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String randomOtp() { return String.format(java.util.Locale.ROOT,"%06d",RANDOM.nextInt(1_000_000)); }
    public static String hash(String proof) {
        if(proof==null || proof.isEmpty()) throw new IllegalArgumentException("Missing authentication proof");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(proof.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException("Authentication digest unavailable"); }
    }
    public static boolean matches(String proof,String hash) {
        if(proof==null || hash==null || !hash.matches("[0-9a-f]{64}")) return false;
        return MessageDigest.isEqual(hash(proof).getBytes(StandardCharsets.US_ASCII),hash.getBytes(StandardCharsets.US_ASCII));
    }
}
