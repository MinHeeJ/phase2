package com.sicost.authentication;

import com.sicost.common.security.InitialAdministratorInitializer;
import com.sicost.common.security.SecurityStateMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class AdministratorBootstrap implements ApplicationRunner {
    private final InitialAdministratorInitializer initializer;
    private final SecurityStateMapper state;
    public AdministratorBootstrap(InitialAdministratorInitializer initializer,SecurityStateMapper state) { this.initializer=initializer;this.state=state; }
    @Override public void run(ApplicationArguments arguments) {
        String login=System.getenv("INITIAL_ADMIN_LOGIN_ID"), password=System.getenv("INITIAL_ADMIN_PASSWORD"), email=System.getenv("INITIAL_ADMIN_EMAIL");
        initialize(login,password,email);
    }
    void initialize(String login,String password,String email) {
        if(login==null || password==null || email==null || login.isBlank() || password.isBlank() || email.isBlank()) return;
        if(state.administratorCount()==0) initializer.initialize(login,password.toCharArray(),login,email);
    }
}
