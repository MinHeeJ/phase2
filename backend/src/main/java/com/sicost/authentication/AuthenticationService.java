package com.sicost.authentication;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthenticationService {
    private final AuthenticationMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ObjectProvider<JavaMailSender> mail;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    @Value("${sicost.security.secure-cookie:true}") private boolean secure;
    @Value("${sicost.security.mail-from:}") private String from;
    @Value("${sicost.security.public-origin:}") private String publicOrigin;
    public AuthenticationService(AuthenticationMapper mapper, PlatformTransactionManager manager, Clock clock, ObjectProvider<JavaMailSender> mail) {
        this.mapper=mapper;this.tx=new TransactionTemplate(manager);this.clock=clock;this.mail=mail;
    }
    public LocalDateTime now() { return SecurityTime.now(clock); }
    public static Map<String,Object> values(Object... pairs) {
        Map<String,Object> result=new HashMap<>();
        for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    public static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    public static LocalDateTime date(Object value) {
        if(value instanceof LocalDateTime date) return date;
        return ((java.sql.Timestamp)value).toLocalDateTime();
    }
    private boolean active(Map<String,Object> account) {
        if(account==null || !"ACTIVE".equals(account.get("status"))) return false;
        Object resignation=account.get("resignationDate");
        LocalDate day=resignation instanceof java.sql.Date d?d.toLocalDate():(LocalDate)resignation;
        return day==null || day.isAfter(now().toLocalDate());
    }
    public static String cookie(HttpServletRequest request,String name) {
        if(request.getCookies()!=null) for(Cookie c:request.getCookies()) if(c.getName().equals(name)) return c.getValue();
        return null;
    }
    private void cookie(HttpServletResponse response,String name,String value,boolean httpOnly,int seconds) {
        response.addHeader("Set-Cookie",ResponseCookie.from(name,value).path("/").httpOnly(httpOnly).secure(secure).sameSite("Lax").maxAge(seconds).build().toString());
    }
    public Map<String,Object> csrf(HttpServletRequest request,HttpServletResponse response) {
        String token=cookie(request,"XSRF-TOKEN");
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) token=AuthenticationProofs.randomId();
        cookie(response,"XSRF-TOKEN",token,false,-1);
        return Map.of("headerName","X-CSRF-TOKEN","csrfToken",token);
    }
    public Map<String,Object> session(HttpServletRequest request) {
        String raw=cookie(request,"JSESSIONID");
        if(raw==null || !raw.matches("[A-Za-z0-9_-]{43}")) throw ApiException.unauthorized();
        Map<String,Object> row=mapper.session(values("sessionHash",AuthenticationProofs.hash(raw),"now",now()));
        if(row==null) throw ApiException.unauthorized();
        return row;
    }
    public SecurityPrincipal principal(Map<String,Object> account) {
        long id=number(account,"id");
        return new SecurityPrincipal(id,(String)account.get("name"),mapper.roles(id),DataScope.SELF);
    }
    public void verifyCsrf(HttpServletRequest request,Map<String,Object> session) {
        String token=request.getHeader("X-CSRF-TOKEN"), cookie=cookie(request,"XSRF-TOKEN");
        if(token==null || cookie==null || !token.equals(cookie) || (session!=null && !AuthenticationProofs.matches(token,(String)session.get("csrfHash")))) throw ApiException.forbidden();
        String origin=request.getHeader("Origin");
        if(origin!=null) {
            try { if(!java.net.URI.create(origin).getRawAuthority().equals(request.getHeader("Host"))) throw ApiException.forbidden(); }
            catch(IllegalArgumentException ex) { throw ApiException.forbidden(); }
        }
    }
    public Map<String,Object> login(String loginId,String password,HttpServletRequest request,HttpServletResponse response) {
        if(loginId==null || password==null || loginId.trim().length()>50 || password.length()>72) throw invalid();
        rate(loginId.trim(),request);
        Map<String,Object> account=tx.execute(status -> {
            Map<String,Object> row=mapper.account(values("loginId",loginId.trim(),"lock",true));
            if(!active(row) || !encoder.matches(password,(String)row.get("passwordHash"))) {
                if(active(row)) mapper.failed(values("id",row.get("id"),"now",now()));
                audit(row==null?null:number(row,"id"),"LOGIN_FAIL",null,request);
                return null; // rejection occurs after commit, so failure counters cannot roll back
            }
            return row;
        });
        if(account==null) throw invalid();
        if(Boolean.TRUE.equals(account.get("mfaRequired"))) return sendChallenge(account,"MFA",null,request,response);
        return issue(account,request,response);
    }
    private ApiException invalid() { return new ApiException(401,"AUTHENTICATION_FAILED","아이디 또는 인증 정보를 확인하세요.",null); }
    private Map<String,Object> issue(Map<String,Object> account,HttpServletRequest request,HttpServletResponse response) {
        String session=AuthenticationProofs.randomId(),csrf=AuthenticationProofs.randomId();
        tx.executeWithoutResult(status -> {
            Map<String,Object> current=mapper.account(values("id",account.get("id"),"lock",true));
            if(!active(current)) throw invalid();
            String previous=cookie(request,"JSESSIONID");
            if(previous!=null && previous.matches("[A-Za-z0-9_-]{43}")) mapper.revoke(values("sessionHash",AuthenticationProofs.hash(previous),"now",now()));
            mapper.complete(values("id",account.get("id"),"now",now()));
            mapper.insertSession(values("id",account.get("id"),"sessionHash",AuthenticationProofs.hash(session),"csrfHash",AuthenticationProofs.hash(csrf),"passwordChangeRequired",account.get("passwordChangeRequired"),"now",now(),"expiresAt",now().plusMinutes(30)));
            audit(number(account,"id"),"LOGIN",null,request);
        });
        cookie(response,"JSESSIONID",session,true,-1);cookie(response,"XSRF-TOKEN",csrf,false,-1);cookie(response,"PREAUTH","",true,0);
        boolean change=Boolean.TRUE.equals(account.get("passwordChangeRequired"));
        return values("state",change?"PASSWORD_CHANGE_REQUIRED":"AUTHENTICATED","passwordChangeRequired",change,"passwordAgeWarning",ageWarning(account));
    }
    public boolean ageWarning(Map<String,Object> account) {
        return account.get("passwordChangedAt")!=null && !date(account.get("passwordChangedAt")).plusDays(90).isAfter(now());
    }
    private Map<String,Object> sendChallenge(Map<String,Object> account,String type,Map<String,Object> prior,HttpServletRequest request,HttpServletResponse response) {
        if(mail.getIfAvailable()==null || account.get("email")==null || from==null || from.isBlank()) throw ApiException.unavailable();
        String chain=prior==null?AuthenticationProofs.randomId():cookie(request,"PREAUTH");
        String proof=type.equals("MFA")?AuthenticationProofs.otp():AuthenticationProofs.randomId();
        LocalDateTime expiry=now().plusMinutes(type.equals("MFA")?5:15);
        Map<String,Object> write=values("id",account.get("id"),"type",type,"proofHash",type.equals("MFA")?AuthenticationProofs.otpHash(chain,proof):AuthenticationProofs.hash(proof),"chainHash",AuthenticationProofs.hash(chain),"failures",prior==null?0:prior.get("failures"),"now",now(),"expiresAt",expiry);
        tx.executeWithoutResult(status -> {
            mapper.account(values("id",account.get("id"),"lock",true));
            mapper.invalidateTokens(write);write.put("tokenId",mapper.insertChallenge(write));write.put("deliveryId",mapper.delivery(write));
        });
        SimpleMailMessage message=new SimpleMailMessage();message.setFrom(from);message.setTo((String)account.get("email"));
        message.setSubject(type.equals("MFA")?"로그인 인증":"비밀번호 재설정");
        // RESET proof remains only in memory and the recipient's mailbox; never in the outbox.
        if(!type.equals("MFA") && !validPublicOrigin()) throw ApiException.unavailable();
        message.setText(type.equals("MFA")?"인증 코드: "+proof:"비밀번호 재설정: "+publicOrigin+"/UI-22#reset="+proof);
        try { mail.getObject().send(message);write.put("deliveryStatus","SENT");write.put("errorCode",null); }
        catch(org.springframework.mail.MailException ex) { write.put("deliveryStatus","FAILED");write.put("errorCode","SMTP_FAILURE"); }
        tx.executeWithoutResult(status -> mapper.deliveryResult(write));
        if(!"SENT".equals(write.get("deliveryStatus"))) throw ApiException.unavailable();
        if(type.equals("MFA")) cookie(response,"PREAUTH",chain,true,300);
        return Map.of("state","MFA_REQUIRED","expiresAt",expiry,"expiresInSeconds",300);
    }
    public Map<String,Object> mfa(String code,HttpServletRequest request,HttpServletResponse response) {
        String chain=cookie(request,"PREAUTH");if(chain==null || !chain.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        Map<String,Object> account=tx.execute(status -> {
            var token=mapper.challenge(values("type","MFA","chainHash",AuthenticationProofs.hash(chain)));
            if(token==null || !date(token.get("expiresAt")).isAfter(now()) || number(token,"failures")>=5) return null;
            var write=values("tokenId",token.get("tokenId"),"now",now());
            if(code==null || !code.matches("[0-9]{6}") || !AuthenticationProofs.otpHash(chain,code).equals(token.get("proofHash"))) { mapper.failedOtp(write);return null; }
            if(mapper.consume(write)!=1) return null;
            return mapper.account(values("id",token.get("id"),"lock",true));
        });
        if(!active(account)) throw invalid();return issue(account,request,response);
    }
    public Map<String,Object> resend(HttpServletRequest request,HttpServletResponse response) {
        String chain=cookie(request,"PREAUTH");if(chain==null || !chain.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        var token=tx.execute(status -> mapper.challenge(values("type","MFA","chainHash",AuthenticationProofs.hash(chain))));
        if(token==null || number(token,"failures")>=5 || !date(token.get("expiresAt")).isAfter(now())) throw invalid();
        if(date(token.get("lastSentAt")).plusSeconds(60).isAfter(now())) throw new ApiException(429,"RATE_LIMITED","잠시 후 다시 시도하세요.",null);
        return sendChallenge(mapper.account(values("id",token.get("id"))),"MFA",token,request,response);
    }
    public void cancel(HttpServletRequest request,HttpServletResponse response) {
        String chain=cookie(request,"PREAUTH");
        if(chain!=null && chain.matches("[A-Za-z0-9_-]{43}")) tx.executeWithoutResult(status -> {
            var token=mapper.challenge(values("type","MFA","chainHash",AuthenticationProofs.hash(chain)));
            if(token!=null) mapper.invalidateTokens(values("id",token.get("id"),"type","MFA","now",now()));
        });
        cookie(response,"PREAUTH","",true,0);
    }
    public void forgot(String loginId,HttpServletRequest request,HttpServletResponse response) {
        if(loginId==null || loginId.trim().length()>50) throw ApiException.badRequest("아이디를 확인하세요.","loginId");
        rate(loginId.trim(),request);
        var account=mapper.account(values("loginId",loginId.trim()));
        if(account!=null && !"DISABLED".equals(account.get("status"))) {
            try { sendChallenge(account,"RESET",null,request,response); } catch(ApiException ex) { if(ex.status()!=503) throw ex; }
        }
    }
    public void requestReset(long id,HttpServletRequest request,HttpServletResponse response) {
        var account=mapper.account(values("id",id));if(account==null) throw ApiException.notFound("계정이 없습니다.");sendChallenge(account,"RESET",null,request,response);
    }
    public void password(AuthenticationController.Password input,boolean resetting,HttpServletRequest request,HttpServletResponse response) {
        String current=input.currentPassword(),replacement=input.newPassword(),confirmation=input.confirmPassword(),reset=resetting?input.token():null;
        if(resetting && reset==null) throw ApiException.badRequest("재설정 링크를 확인하세요.","token");
        validatePassword(replacement,confirmation);
        tx.executeWithoutResult(status -> {
            Map<String,Object> token=null,account;
            if(reset!=null) {
                if(!reset.matches("[A-Za-z0-9_-]{43}")) throw ApiException.badRequest("유효하지 않은 재설정 요청입니다.","token");
                token=mapper.challenge(values("type","RESET","proofHash",AuthenticationProofs.hash(reset)));
                if(token==null || mapper.consume(values("tokenId",token.get("tokenId"),"now",now()))!=1) throw ApiException.badRequest("유효하지 않은 재설정 요청입니다.","token");
                account=mapper.account(values("id",token.get("id"),"lock",true));
            } else {
                account=session(request);
                if(current==null || !encoder.matches(current,(String)account.get("passwordHash"))) throw invalid();
            }
            var write=values("id",account.get("id"),"passwordHash",encoder.encode(replacement),"now",now());
            if(mapper.password(write)!=1) throw invalid();
            mapper.revoke(write);mapper.invalidateTokens(write);audit(number(account,"id"),"PW_RESET",null,request);
        });
        cookie(response,"JSESSIONID","",true,0);
    }
    public static void validatePassword(String password,String confirmation) {
        if(password==null || password.length()<8 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 || !password.matches("(?s).*[A-Za-z].*") || !password.matches("(?s).*[0-9].*") || !password.matches("(?s).*[^A-Za-z0-9\\s].*") || !password.equals(confirmation)) throw ApiException.badRequest("8자 이상 영문·숫자·특수문자와 일치하는 확인값이 필요합니다.","newPassword");
    }
    public void logout(HttpServletRequest request,HttpServletResponse response) {
        var account=session(request);tx.executeWithoutResult(status -> {mapper.revoke(values("sessionHash",account.get("sessionHash"),"now",now()));audit(number(account,"id"),"LOGOUT",null,request);});cookie(response,"JSESSIONID","",true,0);
    }
    public Map<String,Object> me(HttpServletRequest request) {
        var row=session(request);return values("name",row.get("name"),"roles",mapper.roles(number(row,"id")).stream().map(String::valueOf).toList(),"passwordAgeWarning",ageWarning(row),"permissionVersion",now().toString());
    }
    public void activity(String activity,String screen,HttpServletRequest request) {
        if(activity==null || !Set.of("KEY","CLICK","NAVIGATION").contains(activity) || screen==null || !screen.matches("UI-[0-9]{2}")) throw ApiException.badRequest("활동을 확인하세요.","activityType");
        var account=session(request);mapper.activity(values("sessionHash",account.get("sessionHash"),"now",now(),"expiresAt",now().plusMinutes(30),"mergeBefore",now().minusSeconds(30)));
    }
    public void audit(Long id,String action,String target,HttpServletRequest request) {
        mapper.audit(values("id",id,"action",action,"screenId","UI-22","target",target,"detail",null,"ip",request==null?null:request.getRemoteAddr(),"now",now()));
    }
    private void rate(String loginId,HttpServletRequest request) {
        String target=AuthenticationProofs.hash("login:"+loginId),ip=request.getRemoteAddr();
        tx.executeWithoutResult(status -> {
            for(String key:new TreeSet<>(List.of(target,"ip:"+ip))) mapper.rateLock(key);
            if(mapper.attempts(values("target",target,"ip",ip,"since",now().minusMinutes(5)))>=5) throw new ApiException(429,"RATE_LIMITED","잠시 후 다시 시도하세요.",null);
            audit(null,"AUTH_ATTEMPT",target,request);
        });
    }
    private boolean validPublicOrigin() {
        try {
            var uri=java.net.URI.create(publicOrigin);
            return Set.of("http","https").contains(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null && uri.getRawQuery()==null && uri.getRawFragment()==null && (uri.getPath()==null || uri.getPath().isEmpty());
        } catch(RuntimeException ex) {return false;}
    }
}
