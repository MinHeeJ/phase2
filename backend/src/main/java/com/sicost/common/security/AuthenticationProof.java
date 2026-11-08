package com.sicost.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class AuthenticationProof {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final BCryptPasswordEncoder PASSWORDS=new BCryptPasswordEncoder(12);
    private AuthenticationProof() {}
    public static String randomId() { byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String otp() { return String.format(java.util.Locale.ROOT,"%06d",RANDOM.nextInt(1_000_000)); }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static String hash(String proof) { return PASSWORDS.encode(proof); }
    public static boolean matches(String proof,String encoded) { return proof!=null && encoded!=null && PASSWORDS.matches(proof,encoded); }
}
