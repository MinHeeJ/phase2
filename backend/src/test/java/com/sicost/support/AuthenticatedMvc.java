package com.sicost.support;

import com.sicost.common.api.RequestIdFilter;
import com.sicost.common.security.AuthenticationProof;
import com.sicost.common.security.SecurityPrincipal;
import com.sicost.common.security.SessionService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Authenticated request defaults for domain slices; the real security chain remains enabled. */
public final class AuthenticatedMvc {
    private AuthenticatedMvc() {}
    public static Cookie identity(SessionService sessions,long userId) {
        String raw=AuthenticationProof.randomId();
        when(sessions.authenticate(raw)).thenReturn(new SecurityPrincipal(userId,"계약 시험 사용자",List.of(1L),AuthenticationProof.digest(raw),false));
        when(sessions.expiresAt(any())).thenReturn(Instant.parse("2099-01-01T00:00:00Z"));
        return new Cookie("JSESSIONID",raw);
    }
    public static MockMvc build(WebApplicationContext context,SessionService sessions) {
        return MockMvcBuilders.webAppContextSetup(context)
            .defaultRequest(get("/").cookie(identity(sessions,7L)).with(csrf().asHeader()))
            .addFilters(new RequestIdFilter()).apply(springSecurity()).build();
    }
}
