package com.sicost.common.ai;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import java.time.Clock;

import java.time.Instant;

import org.springframework.stereotype.Component;

@Component
public class AiOwnerSession {

    private final Clock clock;
    public AiOwnerSession(Clock clock) { this.clock = clock; }


    /** Call only after environment, purpose and input checks permit an AI access. Persist only the returned digest. */
    public String ownerHash(HttpServletRequest request) {
        var user=new com.sicost.common.security.CurrentUser().principal();
        return com.sicost.common.security.AuthenticationProof.digest("authenticated-user:"+user.userId());
    }
    public void requireOwner(HttpServletRequest request, String storedHash) {
        assertCurrent(request,storedHash);
    }
    /** Recheck after model waits and immediately before commit; it does not extend access. */
    public void assertCurrent(HttpServletRequest request, String expectedHash) {
        Object deadline=request.getAttribute(com.sicost.common.security.SessionService.class.getName()+".expiresAt");
        if(deadline instanceof Instant expiry && !clock.instant().isBefore(expiry))
            throw new com.sicost.common.api.ApiException(401,"UNAUTHENTICATED","로그인이 필요합니다.",null);
        String actual=ownerHash(request);
        if(expectedHash==null || !expectedHash.matches("[0-9a-f]{64}") ||
            !MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),expectedHash.getBytes(StandardCharsets.US_ASCII)))
            throw com.sicost.common.api.ApiException.notFound("요청한 항목이 없습니다.");
    }
    public Instant expiresAt(HttpServletRequest request) {
        new com.sicost.common.security.CurrentUser().principal();
        Object deadline=request.getAttribute(com.sicost.common.security.SessionService.class.getName()+".expiresAt");
        if(deadline instanceof Instant value) return value;
        throw new com.sicost.common.api.ApiException(401,"UNAUTHENTICATED","로그인이 필요합니다.",null);
    }

}
