package com.sicost.months;

import static com.sicost.months.AssignmentMonthsModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
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
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = AssignmentMonthsController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, AssignmentMonthsService.class,
    AssignmentConcurrencyService.class, CurrentUser.class, PermissionService.class})
class AssignmentMonthsApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AssignmentMonthsMapper mapper;
    @MockBean AssignmentConcurrencyMapper concurrency;
    static final String PATH = "/api/assignments/1/months";
    static final LocalDateTime VERSION = LocalDateTime.parse("2026-10-14T09:00:00.123456");
    AssignmentState assignment;
    List<MonthRow> months;
    List<MonthAudit> audits;

    static BigDecimal number(String value) { return new BigDecimal(value); }
    AssignmentState state(String status, String project, String use, LocalDateTime version) {
        return new AssignmentState(1, 2, 3, LocalDate.of(2026,9,1), LocalDate.of(2026,11,30),
            number("0.52"), status, project, use, version);
    }
    @BeforeEach void setup() {
        assignment = state("ACTIVE", "RUN", "Y", VERSION);
        months = new ArrayList<>(List.of(
            new MonthRow("2026-09", number("0.52"), number("0.52"), "N", number("1.20"), "Y"),
            new MonthRow("2026-10", number("0.52"), number("0.52"), "N", number("0.52"), "N"),
            new MonthRow("2026-11", number("0.52"), number("0.52"), "N", number("0.52"), "N")));
        audits = new ArrayList<>();
        when(mapper.assignment(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 1 ? assignment : null);
        when(mapper.months(1)).thenAnswer(i -> List.copyOf(months));
        when(mapper.warnings(1)).thenReturn(List.of());
        when(concurrency.lockAssignment(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 1
            ? new AssignmentConcurrencyMapper.AssignmentLock(1, assignment.updated_at(), VERSION.plusSeconds(10)) : null);
        when(concurrency.advance(any())).thenAnswer(i -> {
            assignment = state(assignment.asgn_stts_cd(), assignment.prj_stts_cd(), assignment.use_yn(),
                assignment.updated_at().plusNanos(1000));
            return assignment.updated_at();
        });
        when(mapper.update(any())).thenAnswer(i -> {
            var write = i.getArgument(0, MonthWrite.class);
            for (int n=0; n<months.size(); n++) {
                var row=months.get(n);
                if (row.work_ym().equals(write.month())) months.set(n, new MonthRow(row.work_ym(),
                    row.auto_mm_qty(), write.quantity(), write.manual(), write.quantity(), row.closed_yn()));
            }
            return 1;
        });
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    Map<String,Object> input(String month, Object value) {
        return Map.of("updated_at", assignment.updated_at().toString(), "months",
            List.of(Map.of("work_ym", month, "mm_qty", value)));
    }
    ResultActions save(Map<String,Object> input) throws Exception {
        return mvc.perform(put(PATH).contentType("application/json").content(json.writeValueAsString(input)));
    }
    void unchanged() {
        verify(mapper, never()).update(any());
        verify(mapper, never()).audit(any());
        verify(concurrency, never()).advance(any());
    }
    @Test void anonymousBusinessReadReturnsManualValuePersonTotalClosedFlagAndMicroseconds() throws Exception {
        months.set(1, new MonthRow("2026-10",number("0.52"),number("0.50"),"Y",number("0.50"),"N"));
        mvc.perform(get("/api/assignments/1/months")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.asgn_id").value(1))
            .andExpect(jsonPath("$.data.updated_at").value(VERSION.toString()))
            .andExpect(jsonPath("$.data.items[1].auto_mm_qty").value(0.52))
            .andExpect(jsonPath("$.data.items[1].mm_qty").value(0.50))
            .andExpect(jsonPath("$.data.items[1].mnl_yn").value("Y"))
            .andExpect(jsonPath("$.data.items[0].person_month_mm_qty").value(1.20))
            .andExpect(jsonPath("$.data.items[0].closed_yn").value("Y"))
            .andExpect(jsonPath("$.data.warnings[0].code").value("OVER_MM"));
        unchanged();
    }
    @Test void nonexistentAssignmentReturnsNotFoundWithoutOtherMonths() throws Exception {
        mvc.perform(get("/api/assignments/999/months")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.data").doesNotExist());
        verify(mapper,never()).months(anyLong());
    }
    @Test void anonymousSaveChangesOnlyRequestedMonthAndRestoresAutomaticFlag() throws Exception {
        save(input("2026-10",number("0.50"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items[1].mnl_yn").value("Y"))
            .andExpect(jsonPath("$.data.items[1].mm_qty").value(0.50));
        assertThat(audits).containsExactly(new MonthAudit("1","2026-10","0.52","0.50",null));
        save(input("2026-10",number("0.52"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[1].mnl_yn").value("N"));
        assertThat(months.get(0).mm_qty()).isEqualByComparingTo("0.52");
    }
    @Test void thirdDecimalRoundsHalfUpBeforeAutomaticComparison() throws Exception {
        save(input("2026-10",number("0.523"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[1].mm_qty").value(0.52))
            .andExpect(jsonPath("$.data.items[1].mnl_yn").value("N"));
        verify(mapper,never()).update(any());
    }
    @Test void zeroIsAValidManualQuantity() throws Exception {
        save(input("2026-10",0)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[1].mm_qty").value(0));
    }
    @ParameterizedTest @ValueSource(strings={"-0.1","1.20"})
    void outOfRangeReturnsOriginalMessageAndField(String value) throws Exception {
        save(input("2026-10",number(value))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("MM을 0~1.00 사이 숫자로 입력하세요."))
            .andExpect(jsonPath("$.field").value("mm_qty"));
        unchanged();
    }
    @Test void nonNumericQuantityIsRejected() throws Exception {
        save(input("2026-10","0.50")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("mm_qty"));
        unchanged();
    }
    @Test void closedMonthTransmissionEvenUnchangedIsConflict() throws Exception {
        save(input("2026-09",number("0.52"))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        unchanged();
    }
    @Test void validationOfLaterMonthPreventsEarlierUpdate() throws Exception {
        var body=Map.<String,Object>of("updated_at",VERSION.toString(),"months",List.of(
            Map.of("work_ym","2026-10","mm_qty",0.5),Map.of("work_ym","2026-09","mm_qty",0.5)));
        save(body).andExpect(status().isConflict());
        unchanged();
    }
    @Test void secondSaveWithSameTimestampIsStale() throws Exception {
        var body=input("2026-10",number("0.50"));
        save(body).andExpect(status().isOk());
        save(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_ASSIGNMENT"))
            .andExpect(jsonPath("$.message").value("다른 사람이 먼저 바꿨습니다. 창을 다시 열어 주세요."));
        assertThat(audits).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"END","LOST","DISABLED"})
    void closedProjectRefusesWrites(String status) throws Exception {
        assignment=state("ACTIVE",status.equals("DISABLED")?"RUN":status,status.equals("DISABLED")?"N":"Y",VERSION);
        save(input("2026-10",0.5)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));
        unchanged();
    }
    @Test void cancelledAssignmentIsReadOnly() throws Exception {
        assignment=state("CANCELLED","RUN","Y",VERSION);
        save(input("2026-10",0.5)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ASSIGNMENT_LOCKED"));
        unchanged();
    }
    @Test void replacedOriginalAllowsRemainingOpenMonths() throws Exception {
        assignment=state("REPLACED","RUN","Y",VERSION);
        save(input("2026-10",0.5)).andExpect(status().isOk());
    }
    @Test void monthOutsideCurrentPeriodIsRejected() throws Exception {
        save(input("2026-12",0.5)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("work_ym"));
        unchanged();
    }
    @Test void duplicateMonthIsRejected() throws Exception {
        var body=input("2026-10",0.5);
        save(Map.of("updated_at",VERSION.toString(),"months",List.of(
            Map.of("work_ym","2026-10","mm_qty",0.5),Map.of("work_ym","2026-10","mm_qty",0.4))))
            .andExpect(status().isBadRequest());
        unchanged();
    }
    @Test void overlapWithinSameProjectIsConflict() throws Exception {
        when(mapper.overlaps(1)).thenReturn(true);
        save(input("2026-10",0.5)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("OVERLAP_ASSIGNMENT"));
        unchanged();
    }
    @Test void missingAssignmentPutReturnsNotFound() throws Exception {
        mvc.perform(put("/api/assignments/999/months").contentType("application/json")
            .content(json.writeValueAsBytes(input("2026-10",0.5)))).andExpect(status().isNotFound());
        unchanged();
    }
    @Test void malformedVersionAndTrailingJsonAreRejected() throws Exception {
        save(Map.of("updated_at","bad","months",List.of())).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("updated_at"));
        mvc.perform(put(PATH).contentType("application/json").content("{} {}"))
            .andExpect(status().isBadRequest());
        unchanged();
    }
}
