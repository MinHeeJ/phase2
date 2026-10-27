package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import com.sicost.developers.*;
import com.sicost.projects.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

public final class AssignmentTestSupport implements AutoCloseable {
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul"));
    @Configuration @EnableTransactionManagement public static class Transactions {}
    public final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public final AnnotationConfigApplicationContext context;
    public final MockMvc mvc;
    public final TransactionTemplate transaction;
    public final AssignmentConcurrencyService concurrency;

    public AssignmentTestSupport(DataSource source) throws Exception {
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/projects/ProjectMapper.xml"),
            new ClassPathResource("mappers/developers/DeveloperMapper.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"));
        factory.afterPropertiesSet();
        var session = new SqlSessionTemplate(factory.getObject());
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, ProjectService.class, DeveloperService.class,
            AssignmentConcurrencyService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(ProjectMapper.class, () -> session.getMapper(ProjectMapper.class));
        context.registerBean(DeveloperMapper.class, () -> session.getMapper(DeveloperMapper.class));
        context.registerBean(AssignmentConcurrencyMapper.class, () -> session.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> manager);
        context.registerBean(Clock.class, () -> CLOCK);
        context.registerBean(ObjectMapper.class, () -> json);
        context.refresh();
        concurrency = context.getBean(AssignmentConcurrencyService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProjectController(context.getBean(ProjectService.class), json),
            new DeveloperController(context.getBean(DeveloperService.class), json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }

    public long createDeveloper() throws Exception {
        var payload = Map.of("dev_nm", "투입 시험 " + UUID.randomUUID(), "emp_se_cd", "FT",
            "main_job_cd", "BE", "sub_job", List.of(), "skill", List.of());
        var result = mvc.perform(post("/api/developers").contentType("application/json")
            .content(json.writeValueAsBytes(payload))).andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("dev_id").asLong();
    }

    public long createProject() throws Exception {
        return createProject(Map.of("prj_nm", "투입 시험 " + UUID.randomUUID(), "cntrct_ty_cd", "IN",
            "prj_stts_cd", "RUN", "cntrct_bgn_dt", "2025-01-01", "cntrct_end_dt", "2027-12-31"));
    }

    public long createProject(Map<String, Object> payload) throws Exception {
        var result = mvc.perform(post("/api/projects").contentType("application/json")
            .content(json.writeValueAsBytes(payload))).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("prj_id").asLong();
    }

    public JsonNode createAssignment(MockMvc assignmentApi, Map<String, Object> payload) throws Exception {
        var result = assignmentApi.perform(post("/api/assignments").contentType("application/json")
            .content(json.writeValueAsBytes(payload))).andExpect(status().is2xxSuccessful())
            .andExpect(jsonPath("$.success").value(true)).andReturn();
        JsonNode assignment = json.readTree(result.getResponse().getContentAsByteArray())
            .path("data").path("assignment");
        assertThat(assignment.path("asgn_id").asLong()).isPositive();
        return assignment;
    }

    @Override public void close() { context.close(); }
}
