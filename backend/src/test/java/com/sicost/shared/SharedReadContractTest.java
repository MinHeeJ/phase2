package com.sicost.shared;

import com.sicost.common.api.ApiExceptionHandler;
import com.sicost.common.api.RequestIdFilter;
import com.sicost.common.health.HealthController;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SharedReadContractTest {
    private SharedReadMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void configureHttp() {
        mapper = mock(SharedReadMapper.class);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new SharedReadController(new SharedReadService(mapper)), new HealthController())
                .setControllerAdvice(new ApiExceptionHandler()).setValidator(validator)
                .addFilters(new RequestIdFilter()).build();
    }

    @Test
    void menusAreTwoLevelsWithSingleArrayEnvelopeAndNullableFields() throws Exception {
        assertThat(new ClassPathResource("contracts/openapi.yaml").exists()).isTrue();
        when(mapper.menus()).thenReturn(List.of(new MenuRow(1L,null,"시스템",null,null,true),
                new MenuRow(27L,1L,"공통코드","UI-27",null,false)));
        mvc.perform(get("/api/menus")).andExpect(status().isOk()).andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap())
                .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data[0].children[0].screenId").value("UI-27"))
                .andExpect(jsonPath("$.data[0].children[0].mobile").value(false))
                .andExpect(jsonPath("$.data[0].children[0].children").isEmpty());
    }

    @Test
    void codesExposeOnlyOptionProjection() throws Exception {
        when(mapper.groupExists("JOB")).thenReturn(true);
        when(mapper.codes("JOB")).thenReturn(List.of(new CodeOption("BE","백엔드","200000",null,null)));
        mvc.perform(get("/api/codes/JOB")).andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"meta\":{},\"data\":[{\"code\":\"BE\",\"name\":\"백엔드\",\"attr1\":\"200000\",\"attr2\":null,\"attr3\":null}]}",true));
    }

    @Test
    void existingGroupWithoutActiveCodesReturnsEmptyArray() throws Exception {
        when(mapper.groupExists("JOB")).thenReturn(true);
        when(mapper.codes("JOB")).thenReturn(List.of());
        mvc.perform(get("/api/codes/JOB")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void missingGroupIs404() throws Exception {
        mvc.perform(get("/api/codes/ABSENT")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(mapper, never()).codes(anyString());
    }

    @Test
    void oversizeGroupIs400BeforeLookup() throws Exception {
        mvc.perform(get("/api/codes/" + "A".repeat(31))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("group"));
        verifyNoInteractions(mapper);
    }

    @Test
    void monthsReturnOnlyActualRowsIncludingOpen() throws Exception {
        when(mapper.months("2026-09","2026-11")).thenReturn(List.of(new MonthClose("2026-09","CLOSED"),new MonthClose("2026-10","OPEN")));
        mvc.perform(get("/api/month-close").param("from","2026-09").param("to","2026-11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("CLOSED")).andExpect(jsonPath("$.data[1].status").value("OPEN"));
    }

    @ParameterizedTest
    @ValueSource(strings={"2026-00","2026-13","2026-1","0000-01","2026-01-01","abcd-01"})
    void invalidFromIs400(String value) throws Exception {
        mvc.perform(get("/api/month-close").param("from",value).param("to","2026-12"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("from"));
        verifyNoInteractions(mapper);
    }

    @Test
    void invalidToIs400() throws Exception {
        mvc.perform(get("/api/month-close").param("from","2026-01").param("to","2026-13"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("to"));
    }

    @Test
    void reversedRangeIs400() throws Exception {
        mvc.perform(get("/api/month-close").param("from","2026-11").param("to","2026-10"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("from"));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings={"from","to"})
    void missingRangeParameterIs400(String missing) throws Exception {
        mvc.perform(get("/api/month-close").param(missing.equals("from") ? "to" : "from","2026-10"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(missing));
    }

    @Test
    void effectiveParameterUsesRequestedMonthAndBigDecimal() throws Exception {
        when(mapper.parameter("VAT_RATE","2026-10")).thenReturn(new OperatingParam("VAT_RATE","2000-01","부가세율",new BigDecimal("10.0000"),null,"%",null));
        mvc.perform(get("/api/params/VAT_RATE").param("month","2026-10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.num_val").value(10))
                .andExpect(jsonPath("$.data.oprt_std_id").value("VAT_RATE"));
        verify(mapper).parameter("VAT_RATE","2026-10");
    }

    @Test
    void missingEffectiveRowIs404() throws Exception {
        mvc.perform(get("/api/params/VAT_RATE").param("month","0001-01"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void invalidParameterMonthIs400BeforeLookup() throws Exception {
        mvc.perform(get("/api/params/VAT_RATE").param("month","2026-13"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(mapper);
    }

    @Test
    void missingParameterMonthIs400() throws Exception {
        mvc.perform(get("/api/params/VAT_RATE")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
    }

    @ParameterizedTest
    @ValueSource(strings={"/api/menus","/api/codes/JOB","/api/month-close?from=2026-01&to=2026-12","/api/params/VAT_RATE?month=2026-10"})
    void databaseFailuresDoNotLeakSql(String path) throws Exception {
        when(mapper.menus()).thenThrow(new IllegalStateException("SELECT password"));
        when(mapper.groupExists(anyString())).thenThrow(new IllegalStateException("SELECT password"));
        when(mapper.months(anyString(),anyString())).thenThrow(new IllegalStateException("SELECT password"));
        when(mapper.parameter(anyString(),anyString())).thenThrow(new IllegalStateException("SELECT password"));
        var response = mvc.perform(get(path)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(jsonPath("$.error").doesNotExist()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("SELECT","password");
        assertThat(response.getContentAsString()).contains(response.getHeader("X-Request-Id"));
    }

    @Test
    void healthReusesFoundationPublicJson() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"meta\":{}}",true));
    }
}
