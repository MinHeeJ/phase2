package com.sicost.withdrawalsops;

import com.sicost.common.api.*;
import com.sicost.us1.WithdrawalMapper;
import com.sicost.us1.WithdrawalModels.Source;
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
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.withdrawalsops.NextSlotModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = NextSlotController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, NextSlotService.class, NextSlotApiTest.Time.class})
class NextSlotApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired NextSlotService service;
    @MockBean NextSlotMapper mapper;
    @MockBean WithdrawalMapper withdrawals;
    static Source source(long id, long dev, String end, String chance, Long next) {
        return new Source(id, "A-" + id, dev, "인력", "K-" + dev, 1, "프로젝트", "BE", "백엔드",
            LocalDate.of(2026, 1, 1), LocalDate.parse(end), LocalDate.parse(end), chance, "미정", "ACTIVE",
            LocalDateTime.of(2026, 10, 14, 0, 0), 0, false, null, "MO", "RUN", "Y",
            LocalDate.of(2026, 1, 1), next, null, null);
    }
    static Need need(long id, String start, String end, String job, String grade, int capacity, String probability) {
        return new Need(id, id, "기회 " + id, job, grade, start, end, capacity, 9L, new BigDecimal(probability));
    }
    @BeforeEach void setup() {
        when(withdrawals.sources()).thenReturn(List.of(source(1, 1, "2026-12-31", null, null)));
        when(mapper.profiles()).thenReturn(List.of(new Profile(1, 1, "SR", 9L)));
        when(mapper.grades()).thenReturn(List.of("JR", "MD", "SR", "EX"));
        when(mapper.jobs(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of("BE"));
        when(mapper.needs()).thenReturn(List.of(need(7, "2027-01", "2027-03", "BE", "SR", 1, "60")));
        when(mapper.temporary(1)).thenReturn(List.of());
    }
    @Test void happyReturnsExactComponentsAndYearBoundary() throws Exception {
        mvc.perform(get("/api/withdrawals/1/next-slots"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items[0].need_id").value(7))
            .andExpect(jsonPath("$.data.items[0].bgn_ym").value("2027-01"))
            .andExpect(jsonPath("$.data.items[0].mtch_scr").value(100))
            .andExpect(jsonPath("$.data.items[0].score_components.job").value(40))
            .andExpect(jsonPath("$.data.items[0].score_components.grade").value(20))
            .andExpect(jsonPath("$.data.items[0].score_components.gap").value(25))
            .andExpect(jsonPath("$.data.items[0].score_components.customer").value(10))
            .andExpect(jsonPath("$.data.items[0].score_components.probability").value(5))
            .andExpect(jsonPath("$.data.items[0].waiting_days").value(0))
            .andExpect(jsonPath("$.data.recommendation_count").value(1))
            .andExpect(jsonPath("$.data.without_next_assignment_count").value(1))
            .andExpect(jsonPath("$.data.with_valid_slots_count").value(1));
    }
    @Test void anonymousHasNoLoginRedirectOrRoleDenial() throws Exception {
        mvc.perform(get("/api/withdrawals/1/next-slots"))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("Location"));
    }
    @ParameterizedTest @ValueSource(strings = {"month=2026-13", "sort=score,down", "job=BE", "grade=SR", "page=0"})
    void unsupportedParametersAre400AndNeverReadSources(String value) throws Exception {
        String[] pair = value.split("=", 2);
        mvc.perform(get("/api/withdrawals/1/next-slots").param(pair[0], pair[1]))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(pair[0]))
            .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(mapper, withdrawals);
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidIdentifier(String id) throws Exception {
        mvc.perform(get("/api/withdrawals/{id}/next-slots", id)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("asgnId"));
    }
    @Test void missingAssignmentIs404() throws Exception {
        mvc.perform(get("/api/withdrawals/99/next-slots")).andExpect(status().isNotFound());
    }
    @Test void repeatedReadUsesSameCalculationAndDoesNotMutateFixtures() throws Exception {
        var before = mapper.needs();
        var expected = service.get(1);
        assertThat(service.get(1)).isEqualTo(expected);
        mvc.perform(get("/api/withdrawals/1/next-slots")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].mtch_scr").value(expected.items().get(0).mtch_scr()));
        assertThat(mapper.needs()).isEqualTo(before);
    }
    @Test void noRecommendationIsEmptyNotFabricated() throws Exception {
        when(mapper.needs()).thenReturn(List.of());
        mvc.perform(get("/api/withdrawals/1/next-slots")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.recommendation_count").value(0))
            .andExpect(jsonPath("$.data.with_valid_slots_count").value(0));
    }
    @Test void highExtensionIsNotEligible() { exclude(source(1, 1, "2026-12-31", "H", null)); }
    @Test void confirmedNextIsNotEligible() { exclude(source(1, 1, "2026-12-31", null, 2L)); }
    @Test void pastWithdrawalIsNotEligible() { exclude(source(1, 1, "2026-10-13", null, null)); }
    @Test void beyondThreeMonthsIsNotEligible() { exclude(source(1, 1, "2027-01-01", null, null)); }
    private void exclude(Source s) {
        when(withdrawals.sources()).thenReturn(List.of(s));
        assertThat(service.get(1).items()).isEmpty();
        assertThat(service.get(1).without_next_assignment_count()).isZero();
    }
    @Test void temporaryIsSeparateAndDoesNotRemoveEligibility() {
        when(mapper.temporary(1)).thenReturn(List.of(new TemporaryRow(4,7,1,"2027-01","2027-03",
            BigDecimal.ONE,"NEXT",new BigDecimal("100"),"ACTIVE",null)));
        assertThat(service.get(1).next_temp_assignments()).hasSize(1);
        assertThat(service.get(1).without_next_assignment_count()).isEqualTo(1);
    }
    @Test void countsAreDistinctPeopleNotAssignmentRows() {
        when(withdrawals.sources()).thenReturn(List.of(source(1,1,"2026-12-31",null,null), source(2,1,"2026-12-31",null,null)));
        when(mapper.profiles()).thenReturn(List.of(new Profile(1,1,"SR",9L),new Profile(2,1,"SR",9L)));
        assertThat(service.get(1).without_next_assignment_count()).isEqualTo(1);
        assertThat(service.get(1).with_valid_slots_count()).isEqualTo(1);
    }
    @Test void rankingIsScoreThenWaitingThenNeedIdAndAtMostFive() {
        when(mapper.needs()).thenReturn(List.of(
            need(8,"2027-01","2027-03","BE","SR",1,"60"),need(4,"2027-01","2027-03","BE","SR",1,"60"),
            need(9,"2027-02","2027-03","BE","SR",1,"60"),need(3,"2027-01","2027-03","BE","SR",1,"60"),
            need(2,"2027-01","2027-03","BE","SR",1,"60"),need(1,"2027-01","2027-03","BE","SR",1,"60")));
        assertThat(service.get(1).items()).extracting(NextSlot::need_id).containsExactly(1L,2L,3L,4L,8L);
    }
    @Test void waitingDaysBreakEqualScoresBeforeId() {
        when(withdrawals.sources()).thenReturn(List.of(source(1,1,"2026-12-15",null,null)));
        when(mapper.needs()).thenReturn(List.of(need(1,"2027-01","2027-03","BE","SR",1,"60"),need(9,"2026-12","2027-03","BE","SR",1,"60")));
        assertThat(service.get(1).items()).extracting(NextSlot::need_id).containsExactly(9L,1L);
        assertThat(service.get(1).items().get(0).warnings()).isNotEmpty();
        assertThat(service.get(1).items().get(1).waiting_days()).isEqualTo(16);
    }
    @Test void fullExpiredAndTooLateNeedsAreExcluded() {
        when(mapper.needs()).thenReturn(List.of(need(1,"2027-01","2027-03","BE","SR",0,"60"),
            need(2,"2026-01","2026-12","BE","SR",1,"60"),need(3,"2027-04","2027-06","BE","SR",1,"60")));
        assertThat(service.get(1).items()).isEmpty();
    }
    @Test void adjacentGradeGetsTenAndUnknownRelatedJobGetsZero() {
        when(mapper.needs()).thenReturn(List.of(need(7,"2027-01","2027-03","FE","MD",1,"59.99")));
        var score = service.get(1).items().get(0).score_components();
        assertThat(score.job()).isZero(); assertThat(score.grade()).isEqualTo(10); assertThat(score.probability()).isZero();
    }
    @Test void distantOrMissingGradesGetZero() {
        when(mapper.needs()).thenReturn(List.of(need(7,"2027-01","2027-03","BE","JR",1,"60"),
            need(8,"2027-01","2027-03","BE",null,1,"60")));
        assertThat(service.get(1).items()).allSatisfy(n -> assertThat(n.score_components().grade()).isZero());
    }
    @Test void monthGapUsesCalendarBoundaryNotThirtyDayDivision() {
        var s = source(1,1,"2026-12-31",null,null);
        var profile = new Profile(1,1,"SR",null);
        var rows = List.of(need(1,"2027-01","2027-06","BE","SR",1,"60"),
            need(2,"2027-02","2027-06","BE","SR",1,"60"),need(3,"2027-03","2027-06","BE","SR",1,"60"));
        assertThat(service.recommend(s,profile,rows,List.of("SR"),List.of("BE"))).extracting(n -> n.score_components().gap()).containsExactly(25,15,8);
        assertThat(service.recommend(s,profile,rows,List.of("SR"),List.of("BE"))).allSatisfy(n -> assertThat(n.score_components().customer()).isZero());
    }
    @Test void secondaryJobIsAnExactMatch() {
        when(mapper.jobs(1)).thenReturn(List.of("FE", "BE"));
        assertThat(service.get(1).items().get(0).score_components().job()).isEqualTo(40);
    }
}
