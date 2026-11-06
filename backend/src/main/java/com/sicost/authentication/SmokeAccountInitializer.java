package com.sicost.authentication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.useraccounts.ManagementMapper;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.authentication.AuthenticationService.*;

/** No credentials are logged. Smoke values are read literally only during startup. */
@Component
public class SmokeAccountInitializer implements ApplicationRunner {
    private final AuthenticationMapper authentication;
    private final ManagementMapper management;
    private final Environment environment;
    @Value("${sicost.security.initial-login-file:}") private String initialLoginFile;
    public SmokeAccountInitializer(AuthenticationMapper authentication,ManagementMapper management,Environment environment) {this.authentication=authentication;this.management=management;this.environment=environment;}
    @Override @Transactional public void run(ApplicationArguments ignored) throws Exception {
        String initialId=System.getenv("INITIAL_ADMIN_LOGIN_ID"),initialPassword=System.getenv("INITIAL_ADMIN_PASSWORD"),initialEmail=System.getenv("INITIAL_ADMIN_EMAIL");
        if(initialId!=null && !initialId.isBlank() && initialPassword!=null && !initialPassword.isBlank() && initialEmail!=null && !initialEmail.isBlank() && authentication.account(values("loginId",initialId))==null) upsert(initialId,initialPassword,List.of(1L),false);
        if(!environment.matchesProfiles("prod","production") && initialLoginFile!=null && !initialLoginFile.isBlank()) {
            var document=new ObjectMapper().readTree(Files.readString(Path.of(initialLoginFile)));
            String login=document.path("login_id").asText(),password=document.path("password").asText();
            if(authentication.account(values("loginId",login))==null) upsert(login,password,List.of(1L),false);
        }
        String login=System.getenv("AIOPS_SMOKE_LOGIN_ID"),password=System.getenv("AIOPS_SMOKE_LOGIN_PASSWORD"),roles=System.getenv("AIOPS_SMOKE_ROLE_CODES");
        if(login==null || login.isBlank() || password==null || password.isBlank() || roles==null || roles.isBlank()) return;
        List<Long> ids=new ArrayList<>();for(String role:roles.split(",")) {long id=Long.parseLong(role.trim());if(management.role(id)==null) throw new IllegalStateException("확인 계정 역할이 유효하지 않습니다.");ids.add(id);}
        upsert(login,password,ids,true);
    }
    private void upsert(String login,String password,List<Long> roles,boolean update) {
        AuthenticationService.validatePassword(password,password);
        var existing=authentication.account(values("loginId",login,"lock",true));
        long id;
        if(existing==null) id=management.createUser(values("loginId",login,"name","앱 시험 관리자","email",System.getenv("INITIAL_ADMIN_EMAIL"),"passwordHash",new BCryptPasswordEncoder(12).encode(password)));
        else {id=number(existing,"id");if(update) authentication.password(values("id",id,"passwordHash",new BCryptPasswordEncoder(12).encode(password),"now",java.time.LocalDateTime.now(com.sicost.common.security.SecurityTime.SEOUL)));}
        management.deleteAssignments(values("id",id,"kind","roles"));for(long role:roles) management.insertAssignment(values("id",id,"kind","roles","value",role));
    }
}
