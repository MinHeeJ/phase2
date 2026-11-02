package com.sicost.costsops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.costallocation.*;
import com.sicost.people.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {CostAllocationController.class, PeopleController.class})
@Import({RequestIdFilter.class, ApiExceptionHandler.class, CostAllocationService.class,
    PeopleCostService.class, CurrentUser.class, PermissionService.class})
class CostCalculationReadApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean CostAllocationMapper mapper;
    @MockBean PeopleMapper people;
    @MockBean PeopleExportService exports;
    @MockBean Clock clock;

    @BeforeEach void setup() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(fixed.instant());
        when(clock.getZone()).thenReturn(fixed.getZone());
        when(clock.withZone(any())).thenReturn(fixed);
        when(mapper.people("2026-10")).thenReturn(List.of(new Person(37, "FT", "DIRECT", 101L)));
        when(mapper.assignments("2026-10")).thenReturn(List.of(
            assignment(9, 71, "0.50"), assignment(2, 72, "0.50")));
        when(mapper.expenses("2026-10")).thenReturn(List.of(new Expense(81, 37L, null, null, null, 1)));
        when(mapper.projectExists(71)).thenReturn(true);
        when(people.personExists(37)).thenReturn(true);
        when(people.projects(any())).thenReturn(List.of(
            new PeopleModels.ProjectLink(9, 37, 71, "첫 프로젝트", "MO", "BE", "2026-10-01", "2026-10-31"),
            new PeopleModels.ProjectLink(2, 37, 72, "둘째 프로젝트", "MO", "BE", "2026-10-01", "2026-10-31")));
    }

    private Assignment assignment(long id, long project, String mm) {
        return new Assignment(id, 37, project, "MO", "RUN", "NEW", "ACTIVE", new BigDecimal(mm), 1000L);
    }
    private AllocationRow snapshot() {
        return new AllocationRow("2026-09", 37L, 9L, 71L, "PROJECT", new BigDecimal("0.50"),
            new BigDecimal("0.50"), new BigDecimal("0.50"), 17, 3, 41, 82L, "Y", "Y", "Y");
    }

    @Test void anonymousAllocationUsesLargestRemainderAndSeparateExpenseRounding() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.unit").value("원"))
            .andExpect(jsonPath("$.data.items[0].asgn_id").value(2))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(51))
            .andExpect(jsonPath("$.data.items[0].drct_expns_amt").value(1))
            .andExpect(jsonPath("$.data.items[1].lbr_cst_amt").value(50))
            .andExpect(jsonPath("$.data.items[1].drct_expns_amt").value(0));
        verify(mapper, never()).snapshots(anyString());
    }
    @Test void projectFilterDoesNotChangeDenominatorOrRemainderWinner() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", "71"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(50))
            .andExpect(jsonPath("$.data.items[0].tot_mm_qty").value(1.0));
    }
    @Test void absentMonthFailsBeforePersistence() throws Exception {
        mvc.perform(get("/api/costs/allocation")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings = {"2026-13", "2026-1", "2026-11", "0000-01", "invalid"})
    void invalidAllocationMonthIs400(String month) throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", month))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "x", "1.1", "9223372036854775808"})
    void invalidProjectIs400(String id) throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", id))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("projectId"));
        verifyNoInteractions(mapper);
    }
    @Test void nonexistentProjectIs404() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", "99"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(mapper, never()).monthStatus(anyString());
    }
    @Test void closedAllocationPreservesHistoricalAmountsAndFlagsWithoutMutableReads() throws Exception {
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        when(mapper.snapshots("2026-09")).thenReturn(List.of(snapshot()));
        mvc.perform(get("/api/costs/allocation").param("month", "2026-09"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.closed").value(true))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(17))
            .andExpect(jsonPath("$.data.items[0].sls_amt").value(41))
            .andExpect(jsonPath("$.data.items[0].free_sprt_yn").value("Y"))
            .andExpect(jsonPath("$.data.items[0].pre_cntrct_yn").value("Y"));
        verify(mapper, never()).people(anyString());
        verify(mapper, never()).assignments(anyString());
        verify(mapper, never()).expenses(anyString());
        verify(mapper, never()).turnkey(anyString());
    }
    @Test void missingClosedAllocationDoesNotRecalculate() throws Exception {
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        when(mapper.expectedCost(any())).thenReturn(true);
        mvc.perform(get("/api/costs/allocation").param("month", "2026-09"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.field").value("month"));
        verify(mapper, never()).people(anyString());
    }
    @Test void openAllocationCanBeActuallyEmpty() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-08"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.unallocated_cost_amt").value(0));
    }
    @Test void anonymousBreakdownUsesSameAllocationRowsAndActualPersonIdentity() throws Exception {
        mvc.perform(get("/api/costs/people/37/breakdown").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.dev_id").value(37))
            .andExpect(jsonPath("$.data.items[0].asgn_id").value(2))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(51))
            .andExpect(jsonPath("$.data.items[0].drct_expns_amt").value(1))
            .andExpect(jsonPath("$.data.items[0].prj_nm").value("둘째 프로젝트"));
        verify(people, never()).download(any());
        verifyNoInteractions(exports);
    }
    @Test void averageBreakdownKeepsActualMonthlyAmounts() throws Exception {
        mvc.perform(get("/api/costs/people/37/breakdown").param("year", "2026").param("view", "AVERAGE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(51))
            .andExpect(jsonPath("$.data.items[0].mm_qty").value(0.5));
    }
    @Test void breakdownDoesNotIncludeAnotherPersonsSnapshot() throws Exception {
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        when(mapper.snapshots("2026-09")).thenReturn(List.of(snapshot(),
            new AllocationRow("2026-09", 99L, null, null, "IDLE", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, 999, 0, 0, null, "N", "N", "N")));
        var body = mvc.perform(get("/api/costs/people/37/breakdown").param("year", "2026"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var rows = json.readTree(body).path("data").path("items");
        assertThat(rows).hasSize(3);
        assertThat(rows.findValuesAsText("lbr_cst_amt")).doesNotContain("999");
    }
    @Test void missingClosedMonthPropagatesToBreakdownWithoutLiveFallback() throws Exception {
        when(mapper.monthStatus("2026-01")).thenReturn("CLOSED");
        when(mapper.expectedCost(any())).thenReturn(true);
        mvc.perform(get("/api/costs/people/37/breakdown").param("year", "2026"))
            .andExpect(status().isConflict());
        verify(mapper, never()).people("2026-01");
        verify(people, never()).download(any());
    }
    @Test void existingPersonCanHaveNoAllocationRows() throws Exception {
        when(people.personExists(99)).thenReturn(true);
        mvc.perform(get("/api/costs/people/99/breakdown").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.dev_id").value(99))
            .andExpect(jsonPath("$.data.items").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidPersonIs400(String id) throws Exception {
        mvc.perform(get("/api/costs/people/" + id + "/breakdown").param("year", "2026"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("devId"));
        verifyNoInteractions(people, mapper);
    }
    @Test void missingPersonIs404BeforeAllocation() throws Exception {
        mvc.perform(get("/api/costs/people/99/breakdown").param("year", "2026"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verifyNoInteractions(mapper);
    }
    @Test void breakdownRequiresYear() throws Exception {
        mvc.perform(get("/api/costs/people/37/breakdown"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("year"));
        verifyNoInteractions(people, mapper);
    }
    @ParameterizedTest @ValueSource(strings = {"0", "2027", "not-year"})
    void invalidBreakdownYearIs400(String year) throws Exception {
        mvc.perform(get("/api/costs/people/37/breakdown").param("year", year))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("year"));
        verifyNoInteractions(people, mapper);
    }
    @Test void invalidBreakdownViewIs400() throws Exception {
        mvc.perform(get("/api/costs/people/37/breakdown").param("year", "2026").param("view", "MONTH"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("view"));
        verifyNoInteractions(people, mapper);
    }
    @Test void databaseFailureDoesNotLeakSqlOrEchoUntrustedRequestId() throws Exception {
        when(mapper.monthStatus("2026-10")).thenThrow(new IllegalStateException("private SQL details"));
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").header("X-Request-Id", "untrusted"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SERVER_ERROR"))
            .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
            .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.not("untrusted")));
    }
    @Test void approvedOperationsRemainInDurableContract() throws Exception {
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            var contract = new org.yaml.snakeyaml.Yaml().loadAs(input, Map.class);
            var paths = (Map<?, ?>) contract.get("paths");
            assertThat(((Map<?, ?>) ((Map<?, ?>) paths.get("/api/costs/allocation")).get("get")).get("operationId"))
                .isEqualTo("getCostAllocation");
            assertThat(((Map<?, ?>) ((Map<?, ?>) paths.get("/api/costs/people/{devId}/breakdown")).get("get")).get("operationId"))
                .isEqualTo("getCostPersonBreakdown");
        }
    }
}
