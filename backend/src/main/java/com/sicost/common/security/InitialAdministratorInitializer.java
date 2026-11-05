package com.sicost.common.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.env.Environment;

@Component
public class InitialAdministratorInitializer implements ApplicationRunner {
    private final InitialAdministratorService service;
    private final Environment environment;
    private final ObjectMapper json;
    public InitialAdministratorInitializer(InitialAdministratorService service,Environment environment,ObjectMapper json) { this.service=service;this.environment=environment;this.json=json; }
    @Override public void run(ApplicationArguments arguments) {
        String login=System.getenv("SICOST_INITIAL_ADMIN_LOGIN_ID"),password=System.getenv("SICOST_INITIAL_ADMIN_PASSWORD"),email=System.getenv("SICOST_INITIAL_ADMIN_EMAIL");
        if(login!=null && login.isBlank()) login=null;
        if(password!=null && password.isEmpty()) password=null;
        if(email!=null && email.isBlank()) email=null;
        Path guide=Path.of("/app/initial-login.json");
        if(login==null && password==null && email!=null && !ProductionSecurityGuard.isProduction(environment) && Files.isRegularFile(guide)) {
            try {
                var data=json.readTree(Files.readString(guide));
                login=data.path("login_id").asText(null);password=data.path("password").asText(null);
            } catch(java.io.IOException ex) { throw new IllegalStateException("초기 로그인 안내 파일을 읽을 수 없습니다."); }
        }
        service.initialize(login,password,email);
    }
}
