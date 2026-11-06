package com.sicost.aiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.ai.*;
import com.sicost.common.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static com.sicost.aiops.RecommendationOptionsModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=RecommendationOptionsController.class, excludeFilters=@org.springframework.context.annotation.ComponentScan.Filter(
    type=org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes=com.sicost.authentication.AuthenticationFilter.class))
@Import({RequestIdFilter.class,ApiExceptionHandler.class,RecommendationOptionsService.class,
    AiAccessGuard.class,AiOwnerSession.class,AiSourceReads.class,RecommendationOptionsApiTest.Time.class})
class RecommendationOptionsApiTest {
    static final String URL="/api/ai/staff-recommendation/options";
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-12-31T15:00:00Z"),ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ConfigurableEnvironment environment;
    @MockBean RecommendationOptionsMapper mapper;
    @MockBean PlatformTransactionManager transactions;
    String[] profiles;
    static SourceRow row(String type,long id) {
        return new SourceRow(type,id,"PROJECT".equals(type)?11L:null,"OPP".equals(type)?id:null,
            "BE","SR","2027-02",3,2,new BigDecimal("0.50"),9L,null);
    }
    @BeforeEach void setup() {
        profiles=environment.getActiveProfiles(); environment.setActiveProfiles("test");
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.sources(anyString(),nullable(String.class))).thenReturn(List.of(row("PROJECT",1),row("OPP",1)));
        when(mapper.sourceSkills(any())).thenReturn(List.of("JAVA"));
        when(mapper.codes("JOB")).thenReturn(List.of("BE"));
        when(mapper.codes("GRADE")).thenReturn(List.of("SR"));
        when(mapper.codes("SKILL")).thenReturn(List.of("JAVA"));
        when(mapper.developerExists(anyLong())).thenReturn(true);
        when(mapper.developers(any())).thenReturn(List.of(
            new ExitOption(1,LocalDate.of(2027,1,31),null,"H",true),
            new ExitOption(2,LocalDate.of(2027,2,28),null,"M",false),
            new ExitOption(3,LocalDate.of(2027,3,31),null,"L",false),
            new ExitOption(4,null,null,null,false)));
    }
    @AfterEach void resetEnvironment() { environment.setActiveProfiles(profiles); environment.getPropertySources().remove("ai-options-test"); }
    @Test void anonymousHappyPathUsesCamelCaseCodesAndSeoulNextMonth() throws Exception {
        var result=mvc.perform(get("/api/ai/staff-recommendation/options")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.defaultStartYm").value("2027-02"))
            .andExpect(jsonPath("$.data.sources[0].criteria.jobCode").value("BE"))
            .andExpect(jsonPath("$.data.sources[0].criteria.startYm").value("2027-02"))
            .andExpect(jsonPath("$.data.sources[0].criteria.durationMonths").value(3))
            .andExpect(jsonPath("$.data.sources[0].criteria.requestedMm").value(0.5))
            .andExpect(jsonPath("$.data.sources[0].criteria.headcount").value(2))
            .andExpect(jsonPath("$.data.jobCodes[0]").value("BE"))
            .andExpect(jsonPath("$.data.developers.length()").value(4))
            .andExpect(jsonPath("$.data.developers[0].extensionLikelihood").value("H"))
            .andExpect(jsonPath("$.data.developers[0].hasNextConfirmedAssignment").value(true))
            .andExpect(jsonPath("$.data.developers[3].exitDate").isEmpty())
            .andExpect(header().doesNotExist("Location")).andReturn();
        assertThat(result.getRequest().getSession(false)).isNotNull();
        String body=result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("owner","salary","tel_no","dev_nm","mng_no","dflt_untprc_amt");
        verify(mapper).sources("2027-01",null);
        verify(mapper).developers(new ExitQuery(LocalDate.of(2027,1,1),LocalDate.of(2027,3,31),null,null));
    }
    long handle(MockHttpSession session,int index) throws Exception {
        var result=mvc.perform(get(URL).session(session)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).at("/data/sources/"+index+"/sourceId").asLong();
    }
    @Test void collidingPhysicalIdsHaveDistinctStableSessionHandles() throws Exception {
        var session=new MockHttpSession(); long a=handle(session,0),b=handle(session,1);
        assertThat(a).isPositive().isNotEqualTo(b); assertThat(handle(session,0)).isEqualTo(a);
        mvc.perform(get(URL).session(session).param("sourceId",Long.toString(b)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.sources.length()").value(1))
            .andExpect(jsonPath("$.data.sources[0].sourceType").value("OPP"));
    }
    @Test void foreignSessionCannotUseAnOptionHandle() throws Exception {
        long id=handle(new MockHttpSession(),0);
        mvc.perform(get(URL).session(new MockHttpSession()).param("sourceId",Long.toString(id))).andExpect(status().isNotFound());
    }
    @Test void sourceChangesAreRejectedInsteadOfSilentlyApplyingOldCriteria() throws Exception {
        var session=new MockHttpSession(); long id=handle(session,0);
        when(mapper.sources(anyString(),nullable(String.class))).thenReturn(List.of(
            new SourceRow("PROJECT",1,11L,null,"FE","SR","2027-03",1,1,BigDecimal.ONE,9L,null)));
        mvc.perform(get("/api/ai/staff-recommendation/options").session(session).param("sourceId",Long.toString(id)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.field").value("sourceId"));
    }
    @Test void disappearedOrFullyStaffedSourceIsConflict() throws Exception {
        var session=new MockHttpSession(); long id=handle(session,0);
        when(mapper.sources(anyString(),nullable(String.class))).thenReturn(List.of());
        mvc.perform(get(URL).session(session).param("sourceId",Long.toString(id))).andExpect(status().isConflict());
    }
    @Test void missingHandleIs404() throws Exception { mvc.perform(get(URL).param("sourceId","999")).andExpect(status().isNotFound()); }
    @Test void missingDeveloperIs404() throws Exception {
        when(mapper.developerExists(999)).thenReturn(false);
        mvc.perform(get(URL).param("devId","999")).andExpect(status().isNotFound());
        verify(mapper,never()).developers(any());
    }
    @ParameterizedTest @ValueSource(strings={"A","B","","PROJECT"})
    void invalidDirectionIs400(String direction) throws Exception {
        mvc.perform(get("/api/ai/staff-recommendation/options").param("direction",direction)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("direction"));
        verifyNoInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","9223372036854775808"})
    void malformedIdsAre400(String id) throws Exception {
        mvc.perform(get(URL).param("devId",id)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("devId"));
        verifyNoInteractions(mapper);
    }
    @Test void unsupportedSensitiveParameterCannotRequestPersonalCost() throws Exception {
        mvc.perform(get(URL).param("salary","true")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("salary"));
        verifyNoInteractions(mapper);
    }
    @Test void directionFiltersAndSearchAreForwardedWithoutIdentityLookup() throws Exception {
        mvc.perform(get(URL).param("direction","PERSON_TO_POSITION").param("search","BE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.sources").isEmpty());
        verify(mapper).sources("2027-01","BE");
    }
    @Test void emptyDatabaseIsAnEmptyListNotDummyData() throws Exception {
        when(mapper.sources(anyString(),nullable(String.class))).thenReturn(List.of()); when(mapper.developers(any())).thenReturn(List.of());
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.sources").isEmpty()).andExpect(jsonPath("$.data.developers").isEmpty());
    }
    @Test void requiredDatabaseFailureIs503WithoutSqlLeak() throws Exception {
        when(mapper.sources(anyString(),nullable(String.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("SQL secret"));
        mvc.perform(get(URL)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL secret"))));
    }
    @Test void productionWithTestBypassIs403BeforeAnySourceAccess() throws Exception {
        environment.setActiveProfiles("test","production");
        environment.getPropertySources().addFirst(new MapPropertySource("ai-options-test",Map.of("sicost.ai.environment","test","sicost.ai.synthetic-test-enabled",true)));
        mvc.perform(get("/api/ai/staff-recommendation/options")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(mapper,transactions);
    }
}
