package com.sicost.common.ai;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class AiOwnerSession {
    private final com.sicost.common.security.CurrentUser user;
    public AiOwnerSession(Clock clock) { this(clock,new com.sicost.common.security.CurrentUser()); }
    @org.springframework.beans.factory.annotation.Autowired
    public AiOwnerSession(Clock clock,com.sicost.common.security.CurrentUser user) { this.user=user; }
    public String ownerHash(HttpServletRequest request) {
        return com.sicost.common.security.AuthenticationProofs.hash("authenticated-user:"+user.userId());
    }
    public void requireOwner(HttpServletRequest request, String storedHash) {
        assertCurrent(request,storedHash);
    }
    public void assertCurrent(HttpServletRequest request, String expectedHash) {
        String current=ownerHash(request);
        if(expectedHash==null || !expectedHash.matches("[0-9a-f]{64}") ||
            !MessageDigest.isEqual(current.getBytes(StandardCharsets.US_ASCII),expectedHash.getBytes(StandardCharsets.US_ASCII))) {
            throw com.sicost.common.api.ApiException.notFound("요청한 이력이 없습니다.");
        }
    }
    public Instant expiresAt(HttpServletRequest request) {
        return user.requireBusinessAccount().expiresAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
    }
}
