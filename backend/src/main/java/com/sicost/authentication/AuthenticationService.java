package com.sicost.authentication;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
public class AuthenticationService {
    public record LoginResult(String status,String session,String challenge,Instant expiresAt) {
        @Override public String toString() { return "LoginResult[redacted]"; }
    }
    private record Delivery(Long tokenId,Long outboxId,String type,String destination,String proof,LocalDateTime expiresAt) {
        @Override public String toString() { return "Delivery[redacted]"; }
    }
    private final AuthenticationMapper mapper;
    private final SessionService sessions;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ObjectProvider<JavaMailSender> sender;
    private final Environment environment;
    public AuthenticationService(AuthenticationMapper mapper,SessionService sessions,Clock clock,PlatformTransactionManager manager,
                                 ObjectProvider<JavaMailSender> sender,Environment environment) {
        this.mapper=mapper;this.sessions=sessions;this.clock=clock;this.tx=new TransactionTemplate(manager);this.sender=sender;this.environment=environment;
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(SessionService.SEOUL)); }
    private static Long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private boolean active(Map<String,Object> account) {
        if(account==null || !"ACTIVE".equals(account.get("status"))) return false;
        Object resignation=account.get("resignationDate");LocalDate date=resignation instanceof java.sql.Date d?d.toLocalDate():(LocalDate)resignation;
        return date==null || now().toLocalDate().isBefore(date);
    }
    private LocalDateTime timestamp(Object value) { return value instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)value; }
    private void rate(String loginId,String ip,String action) {
        tx.executeWithoutResult(status->{
            String key=AuthenticationProof.digest(action+"|"+ip+"|"+loginId);mapper.rateLock(key);
            if(mapper.attempts(key)>=10) throw new ApiException(429,"RATE_LIMITED","잠시 후 다시 시도하세요.",null);
            mapper.attempt(Map.of("key",key,"now",now()));
        });
    }
    public LoginResult login(String loginId,String password,String previous,String ip) {
        if(loginId==null || password==null || loginId.trim().length()>50) throw failed();
        rate(loginId.trim(),ip,"LOGIN");
        return tx.execute(status->{
            var account=mapper.login(loginId.trim());
            if(!active(account) || !AuthenticationProof.matches(password,(String)account.get("passwordHash"))) {
                if(account!=null && active(account)) mapper.failed(number(account,"userId"));
                return null;
            }
            Long id=number(account,"userId");
            if(Boolean.TRUE.equals(account.get("mfa"))) {
                mapper.revokeProofs(id);String challenge=AuthenticationProof.randomId(),otp=AuthenticationProof.otp();
                var delivery=reserve(account,"MFA",otp,AuthenticationProof.digest(challenge));
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { deliver(delivery); }
                });
                return new LoginResult("MFA_REQUIRED",null,challenge,delivery.expiresAt().atZone(SessionService.SEOUL).toInstant());
            }
            mapper.success(id);String session=sessions.issue(id,previous);
            return new LoginResult(Boolean.TRUE.equals(account.get("restricted"))?"PASSWORD_CHANGE_REQUIRED":"AUTHENTICATED",session,null,null);
        });
    }
    private Delivery reserve(Map<String,Object> account,String type,String proof,String challengeHash) {
        String destination=(String)account.get("email");
        if(destination==null || destination.isBlank()) throw mailUnavailable();
        Long userId=number(account,"userId");var w=new HashMap<String,Object>();w.put("userId",userId);w.put("type",type);
        w.put("hash",type.equals("MFA")?AuthenticationProof.hash(proof):AuthenticationProof.digest(proof));w.put("challengeHash",challengeHash);
        w.put("now",now());w.put("expiresAt",now().plusMinutes(type.equals("MFA")?5:15));
        Long token=mapper.token(w);w.put("tokenId",token);w.put("generation",0L);Long outbox=mapper.outbox(w);
        return new Delivery(token,outbox,type,destination,proof,(LocalDateTime)w.get("expiresAt"));
    }
    /** Called inside the account creation/reset transaction; proof exists only in this process until delivery. */
    public void reserveReset(Long id) {
        var account=mapper.account(id);if(account==null) throw ApiException.notFound("계정이 없습니다.");mapper.revokeProofs(id);
        var delivery=reserve(account,"RESET",AuthenticationProof.randomId(),null);
        if(!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("Reset requires a transaction");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { deliver(delivery); }
        });
    }
    public void forgot(String loginId,String ip) {
        if(loginId==null || loginId.length()>50) throw ApiException.badRequest("아이디를 확인하세요.","loginId");rate(loginId.trim(),ip,"RESET");
        try { tx.executeWithoutResult(status->{var account=mapper.login(loginId.trim());if(account!=null && account.get("email")!=null) reserveReset(number(account,"userId"));}); }
        catch(ApiException ex) { if(ex.status()!=503) throw ex; }
    }
    private void deliver(Delivery delivery) {
        try {
            JavaMailSender mail=sender.getIfAvailable();String from=environment.getProperty("sicost.mail.from");
            if(mail==null || from==null || from.isBlank()) throw mailUnavailable();
            var message=new SimpleMailMessage();message.setFrom(from);message.setTo(delivery.destination());
            message.setSubject(delivery.type().equals("MFA")?"로그인 인증 코드":"비밀번호 재설정");
            if(delivery.type().equals("MFA")) message.setText("인증 코드: "+delivery.proof()+"\n5분 이내에 입력하세요.");
            else {
                String origin=environment.getProperty("sicost.public-origin");
                if(origin==null || !origin.matches("https://[^/\\s?#]+")) throw mailUnavailable();
                message.setText("15분 이내에 아래 일회 링크로 비밀번호를 설정하세요.\n"+origin+"/UI-22#reset="+delivery.proof());
            }
            mail.send(message);
            tx.executeWithoutResult(status->{mapper.delivered(delivery.outboxId());mapper.tokenDelivered(delivery.tokenId());});
        } catch(RuntimeException ex) {
            tx.executeWithoutResult(status->mapper.deliveryFailed(delivery.outboxId()));
            throw mailUnavailable();
        }
    }
    private void valid(Map<String,Object> proof) {
        if(proof==null || proof.get("usedAt")!=null || proof.get("revokedAt")!=null || !now().isBefore(timestamp(proof.get("expiresAt")))) throw failed();
    }
    public LoginResult verify(String challenge,String code,String previous) {
        if(challenge==null || code==null || !code.matches("[0-9]{6}")) throw failed();
        LoginResult result=tx.execute(status->{
            var proof=mapper.challenge(AuthenticationProof.digest(challenge));valid(proof);Long userId=number(proof,"userId");var account=mapper.account(userId);
            if(!active(account) || proof.get("lastSentAt")==null) throw failed();
            if(!AuthenticationProof.matches(code,(String)proof.get("hash"))) {
                mapper.failedOtp(number(proof,"tokenId"));if(number(proof,"attempts")>=4) { for(int i=0;i<5;i++) mapper.failed(userId);sessions.revokeUser(userId); }return null;
            }
            mapper.consume(number(proof,"tokenId"));mapper.success(userId);String session=sessions.issue(userId,previous);
            return new LoginResult(Boolean.TRUE.equals(account.get("restricted"))?"PASSWORD_CHANGE_REQUIRED":"AUTHENTICATED",session,null,null);
        });
        if(result==null) throw failed();return result;
    }
    public Instant resend(String challenge) {
        if(challenge==null) throw failed();
        return tx.execute(status->{
            var proof=mapper.challenge(AuthenticationProof.digest(challenge));valid(proof);
            if(proof.get("lastSentAt")!=null && now().isBefore(timestamp(proof.get("lastSentAt")).plusSeconds(60))) throw new ApiException(429,"RATE_LIMITED","잠시 후 다시 시도하세요.",null);
            var account=mapper.account(number(proof,"userId"));if(!active(account)) throw failed();String otp=AuthenticationProof.otp();
            var w=new HashMap<>(proof);w.put("hash",AuthenticationProof.hash(otp));w.put("now",now());w.put("expiresAt",now().plusMinutes(5));mapper.rotate(w);
            w.put("generation",number(proof,"generation")+1);w.put("type","MFA");Long outbox=mapper.outbox(w);
            var delivery=new Delivery(number(proof,"tokenId"),outbox,"MFA",(String)account.get("email"),otp,(LocalDateTime)w.get("expiresAt"));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { deliver(delivery); } });
            return delivery.expiresAt().atZone(SessionService.SEOUL).toInstant();
        });
    }
    public void change(Long id,String current,String password,String confirmation) {
        PasswordPolicy.validate(password);if(!Objects.equals(password,confirmation)) throw ApiException.badRequest("비밀번호 확인이 일치하지 않습니다.","confirmPassword");
        tx.executeWithoutResult(status->{var account=mapper.account(id);if(!active(account) || !AuthenticationProof.matches(current,(String)account.get("passwordHash"))) throw failed();replacePassword(id,password);});
    }
    public void reset(String proof,String password,String confirmation) {
        PasswordPolicy.validate(password);if(proof==null || !Objects.equals(password,confirmation)) throw ApiException.badRequest("입력값을 확인하세요.","confirmPassword");
        tx.executeWithoutResult(status->{var token=mapper.reset(AuthenticationProof.digest(proof));valid(token);if(token.get("lastSentAt")==null) throw failed();Long id=number(token,"userId");mapper.account(id);mapper.consume(number(token,"tokenId"));replacePassword(id,password);});
    }
    private void replacePassword(Long id,String password) {
        mapper.password(Map.of("id",id,"hash",AuthenticationProof.hash(password),"now",now()));mapper.revokeProofs(id);sessions.revokeUser(id);
    }
    public static ApiException failed() { return new ApiException(401,"LOGIN_FAILED","아이디 또는 비밀번호를 확인하세요.",null); }
    private static ApiException mailUnavailable() { return new ApiException(503,"MAIL_UNAVAILABLE","인증 메일을 보낼 수 없습니다.",null); }
}
