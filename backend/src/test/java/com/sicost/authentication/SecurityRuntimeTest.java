package com.sicost.authentication;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"sicost.security.secure-cookie=false","sicost.security.mail-from=isolated@example.invalid"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches=".+")
class SecurityRuntimeTest {
    static String database="runtime_"+UUID.randomUUID().toString().replace("-","");
    static String admin=System.getenv("TEST_DATABASE_URL"),user=System.getenv("TEST_DATABASE_USER"),password=System.getenv("TEST_DATABASE_PASSWORD");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry properties) throws Exception {
        try(var connection=DriverManager.getConnection(admin,user,password);var statement=connection.createStatement()) {statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");}
        properties.add("spring.datasource.url",()->admin.substring(0,admin.lastIndexOf('/')+1)+database);properties.add("spring.datasource.username",()->user);properties.add("spring.datasource.password",()->password);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockBean JavaMailSender mail;
    @AfterAll static void cleanup() throws Exception {if(admin==null) return;try(var connection=DriverManager.getConnection(admin,user,password);var statement=connection.createStatement()) {statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
    @Test void actualStartupMfaSessionAdminHandlersAndLogout() throws Exception {
        String login="runtime."+UUID.randomUUID();String raw="Trial!"+UUID.randomUUID();
        long id=jdbc.queryForObject("INSERT INTO sys.user_info(lgn_id,pswd_hash,user_nm,email_addr,pswd_chg_need_yn) VALUES(?,?,?,'isolated@example.invalid','N') RETURNING user_id",Long.class,login,new BCryptPasswordEncoder(4).encode(raw),"격리 관리자");
        jdbc.update("INSERT INTO sys.user_role(user_id,role_id) VALUES(?,1)",id);
        var csrfResponse=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String csrf=json.readTree(csrfResponse.getContentAsString()).path("data").path("csrfToken").asText();
        var first=mvc.perform(post("/api/auth/login").header("Host","isolated.test").cookie(new Cookie("XSRF-TOKEN",csrf)).header("X-CSRF-TOKEN",csrf).contentType("application/json").content(json.writeValueAsString(Map.of("loginId",login,"password",raw))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("MFA_REQUIRED")).andReturn().getResponse();
        assertThat(first.getCookie("JSESSIONID")).isNull();
        var sent=org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail).send(sent.capture());
        var matcher=java.util.regex.Pattern.compile("[0-9]{6}").matcher(sent.getValue().getText());assertThat(matcher.find()).isTrue();String code=matcher.group();
        mvc.perform(get("/api/auth/me").cookie(first.getCookie("PREAUTH"))).andExpect(status().isUnauthorized());
        var complete=mvc.perform(post("/api/auth/mfa/verify").cookie(first.getCookie("PREAUTH"),new Cookie("XSRF-TOKEN",csrf)).header("X-CSRF-TOKEN",csrf).contentType("application/json").content(json.writeValueAsString(Map.of("code",code))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("AUTHENTICATED")).andReturn().getResponse();
        Cookie session=complete.getCookie("JSESSIONID"),xsrf=complete.getCookie("XSRF-TOKEN");assertThat(session).isNotNull();assertThat(session.isHttpOnly()).isTrue();
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.roles[0]").value("1"));
        for(String path:List.of("/api/admin/users","/api/admin/roles","/api/admin/menus","/api/admin/access-history","/api/navigation","/api/menus","/api/me/permissions","/api/notifications","/api/notifications/unread-count","/api/admin/notification-channels")) mvc.perform(get(path).cookie(session)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/api/auth/logout").cookie(session)).andExpect(status().isForbidden());
        for(String path:List.of("/api/admin/users/export","/api/admin/access-history/export"))
            mvc.perform(get(path).cookie(session)).andExpect(status().isOk()).andExpect(header().exists("Content-Disposition"));
        mvc.perform(post("/api/admin/users/"+id+"/disable").cookie(session,xsrf).header("X-CSRF-TOKEN",xsrf.getValue()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        assertThat(jdbc.queryForObject("SELECT user_stts_cd FROM sys.user_info WHERE user_id=?",String.class,id)).isEqualTo("ACTIVE");
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").cookie(session,xsrf).header("X-CSRF-TOKEN",xsrf.getValue())).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys.acs_hist WHERE user_id=? AND acs_ty_cd='LOGIN'",Long.class,id)).isEqualTo(1);
    }
}
