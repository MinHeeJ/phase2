package com.sicost.common.security;

import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SmokeAccountInitializer implements ApplicationRunner {
    private final SmokeAccountMapper mapper;
    private final PasswordEncoder encoder;
    public SmokeAccountInitializer(SmokeAccountMapper mapper,PasswordEncoder encoder) { this.mapper=mapper;this.encoder=encoder; }
    @Override @Transactional public void run(ApplicationArguments arguments) {
        String login=System.getenv("AIOPS_SMOKE_LOGIN_ID");
        String password=System.getenv("AIOPS_SMOKE_LOGIN_PASSWORD");
        String roles=System.getenv("AIOPS_SMOKE_ROLE_CODES");
        initialize(login,password,roles);
    }
    void initialize(String login,String password,String roles) {
        if(login==null || login.isBlank() || password==null || password.isEmpty() || roles==null || roles.isBlank()) return;
        List<String> names=Arrays.stream(roles.split(",")).map(String::trim).filter(s->!s.isEmpty()).distinct().toList();
        if(names.isEmpty()) return;
        List<Long> ids=mapper.roles(names);
        if(ids.size()!=names.size()) throw new IllegalStateException("확인 계정 역할 설정을 확인하세요.");
        Long id=mapper.upsert(Map.of("login",login,"hash",encoder.encode(password)));
        mapper.clearRoles(id);mapper.assign(Map.of("id",id,"roles",ids));
    }
}
