package com.sicost.projectsops;

import static com.sicost.projectsops.ProjectAssignmentMonthModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ProjectAssignmentMonthController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ProjectAssignmentMonthService.class, PermissionService.class})
class ProjectAssignmentMonthApiTest {
    @Autowired MockMvc mvc;
    @MockBean ProjectAssignmentMonthMapper mapper;
    static final String BASE = "/api/projects/1/assignment-months";
    @BeforeEach void setup() {
        when(mapper.projectExists(1)).thenReturn(true);
        var cells = new ArrayList<StoredMonth>();
        for (int person = 1; person <= 3; person++) {
            for (int month = 1; month <= 12; month++) cells.add(new StoredMonth(person, person,
                "개발자 " + person, YearMonth.of(2026, month).toString(), new BigDecimal("0.50")));
        }
        when(mapper.months(any())).thenReturn(cells);
    }
    @Test void anonymousReadReturnsConcreteDataWithoutCredentials() throws Exception {
        mvc.perform(get(BASE).param("year", "2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.prj_id").value(1))
            .andExpect(jsonPath("$.data.year").value(2026)).andExpect(jsonPath("$.data.items[0].dev_nm").value("개발자 1"));
    }
    @Test void threeAssignmentsHaveTwelveCellsAndExactRowAndMonthTotals() throws Exception {
        var result = mvc.perform(get(BASE).param("year", "2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.month_totals.length()").value(12));
        for (int row = 0; row < 3; row++) result.andExpect(jsonPath("$.data.items["+row+"].months.length()").value(12))
            .andExpect(jsonPath("$.data.items["+row+"].total_mm_qty").value(6));
        for (int month = 0; month < 12; month++) result.andExpect(jsonPath("$.data.month_totals["+month+"].mm_qty").value(1.5));
    }
    @Test void missingYearIsFieldErrorBeforeAnyQuery() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("year"));
        verifyNoInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings={"", "wrong", "0", "10000", "-1", "2026.5"})
    void invalidYearIsFieldError(String year) throws Exception {
        mvc.perform(get(BASE).param("year", year)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("year"));
        verifyNoInteractions(mapper);
    }
    @Test void missingProjectReturns404WithoutReadingMonths() throws Exception {
        mvc.perform(get("/api/projects/999/assignment-months").param("year", "2026"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        verify(mapper, never()).months(any());
    }
    @Test void invalidProjectIdIsRejected() throws Exception {
        mvc.perform(get("/api/projects/0/assignment-months").param("year", "2026"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));
        verifyNoInteractions(mapper);
    }
    @Test void emptyProjectStillHasTwelveZeroMonthTotals() throws Exception {
        when(mapper.months(any())).thenReturn(Collections.emptyList());
        mvc.perform(get(BASE).param("year", "2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.month_totals.length()").value(12))
            .andExpect(jsonPath("$.data.month_totals[0].work_ym").value("2026-01"))
            .andExpect(jsonPath("$.data.month_totals[11].mm_qty").value(0));
    }
}
