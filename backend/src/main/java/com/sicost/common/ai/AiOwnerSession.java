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
    private static final String ATTRIBUTE = AiOwnerSession.class.getName() + ".owner";
    private static final Duration IDLE = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Clock clock;
    public AiOwnerSession(Clock clock) { this.clock = clock; }

    private static final class Owner implements Serializable {
        private static final long serialVersionUID = 1L;
        private final byte[] value;
        private Instant lastAccess;
        private Owner(Instant now) {
            value = new byte[32]; RANDOM.nextBytes(value); lastAccess = now;
        }
        @Override public String toString() { return "AiOwner[redacted]"; }
    }
    /** Call only after environment, purpose and input checks permit an AI access. Persist only the returned digest. */
    public String ownerHash(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        synchronized (session) {
            Instant now = clock.instant();
            Owner owner = live(session, now);
            if (owner == null) { owner = new Owner(now); session.setAttribute(ATTRIBUTE, owner); }
            owner.lastAccess = now;
            return digest(owner);
        }
    }
    public void requireOwner(HttpServletRequest request, String storedHash) {
        HttpSession session = request.getSession(false);
        if (session == null) throw AiAccessGuard.forbidden();
        synchronized (session) {
            Owner owner = matching(session, storedHash);
            owner.lastAccess = clock.instant();
        }
    }
    /** Recheck after model waits and immediately before commit; it does not extend access. */
    public void assertCurrent(HttpServletRequest request, String expectedHash) {
        HttpSession session = request.getSession(false);
        if (session == null) throw AiAccessGuard.forbidden();
        synchronized (session) { matching(session, expectedHash); }
    }
    public Instant expiresAt(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) throw AiAccessGuard.forbidden();
        synchronized (session) {
            Owner owner = live(session, clock.instant());
            if (owner == null) throw AiAccessGuard.forbidden();
            return owner.lastAccess.plus(IDLE);
        }
    }
    private Owner matching(HttpSession session, String storedHash) {
        Owner owner = live(session, clock.instant());
        if (owner == null || storedHash == null || !storedHash.matches("[0-9a-f]{64}") ||
                !MessageDigest.isEqual(digest(owner).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII))) {
            throw AiAccessGuard.forbidden();
        }
        return owner;
    }
    private Owner live(HttpSession session, Instant now) {
        Object value = session.getAttribute(ATTRIBUTE);
        if (value instanceof Owner owner && now.isBefore(owner.lastAccess.plus(IDLE))) return owner;
        session.removeAttribute(ATTRIBUTE);
        return null;
    }
    private String digest(Owner owner) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(owner.value)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
