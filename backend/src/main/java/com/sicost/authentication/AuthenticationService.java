package com.sicost.authentication;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.sicost.authentication.AuthenticationModels.*;

@Service
public class AuthenticationService {
    private final AuthenticationMapper mapper;
    private final CurrentUser user;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final AuthenticationMailDelivery mail;
    public AuthenticationService(AuthenticationMapper mapper,CurrentUser user,PasswordEncoder encoder,Clock clock,
                                 PlatformTransactionManager transactions,AuthenticationMailDelivery mail) {
        this.mapper=mapper;this.user=user;this.encoder=encoder;this.clock=clock;this.tx=new TransactionTemplate(transactions);this.mail=mail;
    }
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    private boolean active(Credentials c) { return c!=null && "ACTIVE".equals(c.status()) && (c.resignDate()==null || c.resignDate().isAfter(now().toLocalDate())); }
    private static ApiException invalid() { return new ApiException(401,"INVALID_CREDENTIALS","아이디 또는 비밀번호를 확인하세요. 계정 보안 절차는 관리자에게 문의하세요.",null); }
    private static ApiException invalidProof() { return ApiException.badRequest("인증이 만료되었거나 유효하지 않습니다.",null); }
    private void audit(Long id,String type,String result) { mapper.audit(new Audit(id,type,result,"UI-22",null,now())); }
    public void rate(String type,String id,String ip) {
        // Direct peer only: forwarded IP headers are not trusted authentication input.
        boolean limited=Boolean.TRUE.equals(tx.execute(s->{
            int pair=mapper.rate(AuthenticationProofs.hash(type+":"+id+":"+ip),now());
            int peer=mapper.rate(AuthenticationProofs.hash(type+":peer:"+ip),now());
            return pair>20 || peer>100;
        }));
        if(limited) throw new ApiException(429,"RATE_LIMITED","잠시 후 다시 시도하세요.",null);
    }
    public AuthResult login(Login input,String previousSession) {
        if(input==null || input.loginId()==null || input.loginId().trim().isEmpty() || input.loginId().trim().length()>50 || input.password()==null || input.password().length()>200) throw invalid();
        Credentials candidate=mapper.loginAccount(input.loginId().trim());
        Credentials authenticated=tx.execute(s->{
            Credentials c=candidate==null?null:mapper.lockAccount(candidate.userId());
            boolean matches=c!=null && encoder.matches(input.password(),c.passwordHash());
            if(!active(c) || !matches) {
                if(active(c)) mapper.failedPassword(c.userId());
                audit(c==null?null:c.userId(),"LOGIN_FAIL","FAILURE");return null;
            }
            if(previousSession!=null) mapper.logout(AuthenticationProofs.hash(previousSession),now());
            return c;
        });
        if(authenticated==null) throw invalid();
        if(authenticated.mfa()) return challenge(authenticated);
        return tx.execute(s->{
            Credentials c=mapper.lockAccount(authenticated.userId());if(!active(c)) throw invalid();
            return complete(c);
        });
    }
    private AuthResult complete(Credentials c) {
        mapper.completeLogin(c.userId(),now());mapper.revokeProofs(c.userId());
        String raw=AuthenticationProofs.randomSessionId();LocalDateTime expiry=now().plusMinutes(30);
        mapper.insertSession(new SessionWrite(c.userId(),AuthenticationProofs.hash(raw),now(),expiry));audit(c.userId(),"LOGIN","SUCCESS");
        boolean warning=c.passwordChangedAt()!=null && !c.passwordChangedAt().plusDays(90).isAfter(now());
        return new AuthResult(new LoginResult(c.passwordChangeRequired()?"PASSWORD_CHANGE_REQUIRED":"AUTHENTICATED",c.passwordChangeRequired(),warning,expiry),raw,null);
    }
    private AuthResult challenge(Credentials c) {
        String binding=AuthenticationProofs.randomSessionId();String otp=AuthenticationProofs.randomOtp();
        Challenge challenge=new Challenge(otp,AuthenticationProofs.hash(otp),"MFA",c.userId(),now(),now().plusMinutes(5));
        issue(c,challenge,AuthenticationProofs.hash(binding));
        return new AuthResult(new LoginResult("MFA_REQUIRED",false,false,challenge.expiresAt()),null,binding);
    }
    private void issue(Credentials c,Challenge challenge,String binding) {
        Long outbox=tx.execute(s->{
            Credentials locked=mapper.lockAccount(c.userId());
            if("MFA".equals(challenge.type()) && (!active(locked) || locked.otpFailures()>=5)) throw invalidProof();
            if("DISABLED".equals(locked.status()) || (locked.resignDate()!=null && !locked.resignDate().isAfter(now().toLocalDate()))) throw invalidProof();
            mapper.revokeProofs(c.userId());
            Long proofId=mapper.createProof(challenge,binding);
            return mapper.outbox(proofId,c.userId(),challenge.type(),now());
        });
        try {
            // Hash and expiry are committed before SMTP; no DB lock is held during transport.
            mail.send(c.email(),challenge.type(),challenge.raw());
            tx.executeWithoutResult(s->mapper.mailSent(outbox,now()));
        } catch(RuntimeException ex) {
            tx.executeWithoutResult(s->{mapper.mailFailed(outbox);mapper.revokeProofs(c.userId());});throw ex;
        }
    }
    public AuthResult verify(String binding,String code) {
        if(binding==null || !binding.matches("[A-Za-z0-9_-]{43}")) throw invalidProof();
        AuthResult result=tx.execute(s->{
            Proof before=mapper.preauth(AuthenticationProofs.hash(binding),now());if(before==null) return null;
            Credentials c=mapper.lockAccount(before.userId());
            Proof proof=mapper.preauth(AuthenticationProofs.hash(binding),now());
            if(proof==null || !active(c) || c.otpFailures()>=5) return null;
            if(code==null || !code.matches("[0-9]{6}") || !AuthenticationProofs.matches(code,proof.hash())) {
                mapper.failedOtp(c.userId());audit(c.userId(),"MFA_FAIL","FAILURE");
                if(c.otpFailures()>=4) mapper.revokeProofs(c.userId());return null;
            }
            if(mapper.consumeProof(proof.id(),now())!=1) return null;
            return complete(c);
        });
        if(result==null) throw invalidProof();return result;
    }
    public AuthResult resend(String binding) {
        Proof proof=binding==null?null:mapper.preauth(AuthenticationProofs.hash(binding),now());
        if(proof==null) throw invalidProof();
        rate("MFA_RESEND",proof.userId().toString(),"account");
        if(proof.expiresAt().minusMinutes(5).plusSeconds(60).isAfter(now())) throw new ApiException(429,"RATE_LIMITED","잠시 후 재전송하세요.",null);
        return challenge(mapper.lockAccount(proof.userId()));
    }
    public void cancel(String binding) {
        if(binding==null) return;
        tx.executeWithoutResult(s->{
            Proof proof=mapper.preauth(AuthenticationProofs.hash(binding),now());
            if(proof!=null) {mapper.lockAccount(proof.userId());mapper.revokeProofs(proof.userId());}
        });
    }
    public void forgot(String loginId) {
        if(loginId==null || loginId.trim().isEmpty() || loginId.trim().length()>50) throw ApiException.badRequest("아이디를 확인하세요.","loginId");
        Credentials c=mapper.loginAccount(loginId.trim());
        if(c==null || "DISABLED".equals(c.status())) return;
        try { sendReset(c.userId()); }
        catch(ApiException ex) { if(ex.status()!=503) throw ex; }
    }
    public void sendReset(Long id) {
        Credentials c=tx.execute(s->mapper.lockAccount(id));if(c==null) throw ApiException.notFound("사용자가 없습니다.");
        String raw=AuthenticationProofs.randomSessionId();
        issue(c,new Challenge(raw,AuthenticationProofs.hash(raw),"RESET",c.userId(),now(),now().plusMinutes(15)),null);
    }
    public void reset(Reset input) {
        PasswordRules.validate(input.newPassword(),input.confirmPassword());
        if(input.token()==null || !input.token().matches("[A-Za-z0-9_-]{43}")) throw invalidProof();
        tx.executeWithoutResult(s->{
            Proof p=mapper.resetProof(AuthenticationProofs.hash(input.token()),now());if(p==null) throw invalidProof();
            Credentials c=mapper.lockAccount(p.userId());
            if(c==null || "DISABLED".equals(c.status()) || (c.resignDate()!=null && !c.resignDate().isAfter(now().toLocalDate())) || mapper.consumeProof(p.id(),now())!=1) throw invalidProof();
            mapper.changePassword(new PasswordWrite(c.userId(),encoder.encode(input.newPassword()),now()));
            mapper.revokeProofs(c.userId());mapper.revokeSessions(c.userId(),now());audit(c.userId(),"PW_RESET","SUCCESS");
        });
    }
    public void change(PasswordChange input) {
        PasswordRules.validate(input.newPassword(),input.confirmPassword());Long id=user.account().userId();
        tx.executeWithoutResult(s->{
            Credentials c=mapper.lockAccount(id);
            if(input.currentPassword()==null || !encoder.matches(input.currentPassword(),c.passwordHash())) throw invalid();
            mapper.changePassword(new PasswordWrite(id,encoder.encode(input.newPassword()),now()));mapper.revokeProofs(id);mapper.revokeSessions(id,now());audit(id,"PW_CHANGE","SUCCESS");
        });
    }
    public void logout() {
        var principal=user.principal();tx.executeWithoutResult(s->{mapper.logout(principal.sessionIdHash(),now());audit(principal.userId(),"LOGOUT","SUCCESS");});
    }
    public void activity(String type,String screenId,PermissionService permissions) {
        if(type==null || !Set.of("KEY","CLICK","NAVIGATION").contains(type) || screenId==null || !screenId.matches("UI-[0-9]{2}")) throw ApiException.badRequest("활동을 확인하세요.","activityType");
        if(!"UI-34".equals(screenId)) permissions.can(screenId+".read");
        if(mapper.activity(user.principal().sessionIdHash(),now())!=1) throw CurrentUser.unauthenticated();
    }
    public Map<String,Object> me() {
        var account=user.requireBusinessAccount();var roles=user.roles();
        return Map.of("userId",account.userId(),"name",account.name(),"roles",roles.stream().map(AccessPolicyModels.Role::name).toList(),"roleIds",roles.stream().map(AccessPolicyModels.Role::roleId).toList(),"passwordAgeWarning",ageWarning(account.userId()));
    }
    private boolean ageWarning(Long id) { Credentials c=tx.execute(s->mapper.lockAccount(id));return c.passwordChangedAt()!=null && !c.passwordChangedAt().plusDays(90).isAfter(now()); }
}
