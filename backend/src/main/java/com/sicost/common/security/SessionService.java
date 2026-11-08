package com.sicost.common.security;

import com.sicost.common.api.ApiException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.common.security.SecurityModels.*;

@Service
@Transactional(readOnly=true)
public class SessionService {
    public static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private final SecurityStateMapper mapper;
    private final Clock clock;
    public SessionService(SecurityStateMapper mapper,Clock clock) { this.mapper=mapper;this.clock=clock; }
    public LocalDateTime now() { return LocalDateTime.now(clock.withZone(SEOUL)); }
    public static org.springframework.http.ResponseCookie cookie(String issuedId) {
        if(issuedId==null || !issuedId.matches("[A-Za-z0-9_-]{43}")) throw unauthenticated();
        return org.springframework.http.ResponseCookie.from("JSESSIONID",issuedId).httpOnly(true).secure(true).sameSite("Lax").path("/").build();
    }
    public java.time.Instant expiresAt(String hash) {
        var session=mapper.session(hash);
        if(session==null || session.revokedAt()!=null || !now().isBefore(session.expiresAt())) throw unauthenticated();
        return session.expiresAt().atZone(SEOUL).toInstant();
    }
    public SecurityPrincipal authenticate(String raw) {
        if(raw==null || !raw.matches("[A-Za-z0-9_-]{43}")) throw unauthenticated();
        var session=mapper.session(AuthenticationProof.digest(raw));
        var now=now();
        if(session==null || session.revokedAt()!=null || !now.isBefore(session.expiresAt()) ||
            !now.isBefore(session.lastActivityAt().plusMinutes(30))) throw unauthenticated();
        var account=mapper.account(session.userId());
        if(account==null || !"ACTIVE".equals(account.status()) ||
            (account.resignationDate()!=null && !now.toLocalDate().isBefore(account.resignationDate()))) throw unauthenticated();
        return new SecurityPrincipal(account.userId(),account.name(),mapper.roles(account.userId()),session.hash(),
            session.passwordChangeOnly() || account.passwordChangeRequired());
    }
    /** Only an authentication service that has completed required MFA may call this method. */
    @Transactional public String issue(Long userId,String previousRaw) {
        var account=mapper.account(userId);
        var now=now();
        if(account==null || !"ACTIVE".equals(account.status()) ||
            (account.resignationDate()!=null && !now.toLocalDate().isBefore(account.resignationDate()))) throw unauthenticated();
        if(previousRaw!=null && previousRaw.matches("[A-Za-z0-9_-]{43}")) mapper.revoke(AuthenticationProof.digest(previousRaw),now);
        String raw=AuthenticationProof.randomId();
        mapper.insertSession(new SessionWrite(AuthenticationProof.digest(raw),userId,now,now.plusMinutes(30),account.passwordChangeRequired()));
        return raw;
    }
    @Transactional public void activity(String raw) {
        var principal=authenticate(raw);
        if(principal.passwordChangeOnly()) throw AuthorizationService.forbidden();
        if(mapper.activity(principal.sessionHash(),now())!=1) throw unauthenticated();
    }
    @Transactional public void revoke(String raw) { if(raw!=null && raw.matches("[A-Za-z0-9_-]{43}")) mapper.revoke(AuthenticationProof.digest(raw),now()); }
    @Transactional public void revokeUser(Long userId) { mapper.revokeUser(userId,now()); }
    @Transactional public int cleanup() { return mapper.cleanup(now()); }
    private static ApiException unauthenticated() { return new ApiException(401,"UNAUTHENTICATED","로그인이 필요합니다.",null); }
}
