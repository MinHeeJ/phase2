package com.sicost.authentication;

import com.sicost.common.security.*;
import com.sicost.systemadmin.AdministrationMapper;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SmokeAccountInitializer implements ApplicationRunner {
    private final AuthenticationMapper authentication;
    private final AdministrationMapper administration;
    public SmokeAccountInitializer(AuthenticationMapper authentication,AdministrationMapper administration) { this.authentication=authentication;this.administration=administration; }
    @Override @Transactional public void run(ApplicationArguments args) {
        String login=System.getenv("AIOPS_SMOKE_LOGIN_ID");
        String password=System.getenv("AIOPS_SMOKE_LOGIN_PASSWORD");
        String codes=System.getenv("AIOPS_SMOKE_ROLE_CODES");
        initialize(login,password,codes);
    }
    void initialize(String login,String password,String codes) {
        if(login==null || login.isBlank() || password==null || password.isBlank() || codes==null || codes.isBlank()) return;
        // Platform-generated verification credentials are not user-selected passwords.
        // Preserve them exactly, while rejecting values BCrypt would truncate.
        if(password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new IllegalStateException("확인 계정 비밀번호 길이를 확인하세요.");
        if(!login.matches("[A-Za-z0-9._-]{1,50}")) throw new IllegalStateException("확인 계정 식별자 형식이 올바르지 않습니다.");
        List<Long> roles=new ArrayList<>();
        for(String code:codes.split(",")) {
            var role=administration.roles().stream().filter(r->code.trim().equals(r.get("name")) || code.trim().equals(String.valueOf(r.get("roleId")))).findFirst().orElseThrow(()->new IllegalStateException("확인 계정 역할을 확인하세요."));
            if(!"Y".equals(role.get("useYn"))) throw new IllegalStateException("확인 계정 역할이 비활성 상태입니다.");
            roles.add(((Number)role.get("roleId")).longValue());
        }
        var account=authentication.login(login);Long id;
        if(account==null) {
            var w=new HashMap<String,Object>();w.put("loginId",login);w.put("passwordHash",AuthenticationProof.hash(password));w.put("name",login);w.put("email",null);w.put("phone",null);w.put("partId",null);w.put("developerId",null);w.put("actor",null);
            id=administration.insertUser(w);
        } else id=((Number)account.get("userId")).longValue();
        authentication.password(Map.of("id",id,"hash",AuthenticationProof.hash(password),"now",java.time.LocalDateTime.now(SessionService.SEOUL)));
        administration.clearRoles(id);roles.stream().distinct().forEach(role->administration.addRole(id,role));administration.revokeUser(id);
    }
}
