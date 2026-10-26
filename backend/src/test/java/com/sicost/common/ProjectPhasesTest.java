package com.sicost.common;

import static org.assertj.core.api.Assertions.*;

import com.sicost.common.api.ApiException;
import com.sicost.common.validation.ProjectPhases;
import com.sicost.common.validation.ProjectPhases.Definition;
import com.sicost.common.validation.ProjectValues.PerformancePeriod;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

class ProjectPhasesTest {
    private List<Definition> definitions(String type) throws Exception {
        String schema;
        try (var input = new ClassPathResource("db/migration/V1__schema.sql").getInputStream()) {
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var pattern = Pattern.compile("\\('" + ProjectPhases.codeGroup(type)
            + "','[^']+','([^']+)','([0-9.]+)',([0-9]+)\\)");
        var match = pattern.matcher(schema);
        var phases = new ArrayList<Definition>();
        while (match.find()) phases.add(new Definition(Integer.parseInt(match.group(3)),
            match.group(1), new BigDecimal(match.group(2))));
        assertThat(phases).isNotEmpty();
        return phases;
    }

    @ParameterizedTest @CsvSource({"TK,7", "IN,7", "MO,5", "DP,5"})
    void initializesExactlyTheExistingCodeRows(String type, int count) throws Exception {
        LocalDate start = LocalDate.of(2026, 1, 1);
        var period = new PerformancePeriod(start, start.plusDays(99));
        var plans = ProjectPhases.initialize(period, definitions(type));
        assertThat(plans).hasSize(count);
        assertThat(plans.get(0).start()).isEqualTo(start);
        assertThat(plans.get(plans.size() - 1).end()).isEqualTo(period.end());
        assertThat(plans.stream().mapToLong(p -> ChronoUnit.DAYS.between(p.start(), p.end()) + 1).sum())
            .isEqualTo(100);
        if (type.equals("TK")) {
            var analysis = plans.stream().filter(p -> p.name().equals("분석")).findFirst().orElseThrow();
            assertThat(ChronoUnit.DAYS.between(analysis.start(), analysis.end()) + 1).isEqualTo(15);
        }
    }

    @Test void floorRemainderBelongsOnlyToFinalPhase() {
        var definitions = List.of(new Definition(3, "마지막", new BigDecimal("20")),
            new Definition(1, "첫째", new BigDecimal("40")), new Definition(2, "둘째", new BigDecimal("40")));
        var start = LocalDate.of(2026, 1, 1);
        var plans = ProjectPhases.initialize(new PerformancePeriod(start, start.plusDays(2)), definitions);
        assertThat(plans).extracting(ProjectPhases.Plan::sequence).containsExactly(1, 2, 3);
        assertThat(plans).extracting(ProjectPhases.Plan::start).containsExactly(start, start.plusDays(1), start.plusDays(2));
        assertThat(definitions.get(0).sequence()).isEqualTo(3);
    }

    @Test void zeroDayPhasesRetainNullDatesAndDoNotExtendSingleDayPeriod() throws Exception {
        var start = LocalDate.of(2026, 1, 1);
        var plans = ProjectPhases.initialize(new PerformancePeriod(start, start), definitions("IN"));
        assertThat(plans).hasSize(7);
        assertThat(plans.subList(0, 6)).allSatisfy(phase -> {
            assertThat(phase.start()).isNull();
            assertThat(phase.end()).isNull();
        });
        assertThat(plans.get(6).start()).isEqualTo(start);
        assertThat(plans.get(6).end()).isEqualTo(start);
    }

    @Test void incompleteWeightsAreNotSilentlyInvented() {
        invalid(List.of(new Definition(1, "첫째", new BigDecimal("90"))));
    }
    @Test void duplicateOrderIsRejectedBeforePersistence() {
        invalid(List.of(new Definition(1, "첫째", new BigDecimal("50")), new Definition(1, "둘째", new BigDecimal("50"))));
    }
    @Test void negativeWeightIsRejectedBeforePersistence() {
        invalid(List.of(new Definition(1, "첫째", new BigDecimal("-5")), new Definition(2, "둘째", new BigDecimal("105"))));
    }
    @Test void absentCodeGroupIsNotReplacedWithLocalCatalogue() { invalid(List.of()); }
    private void invalid(List<Definition> definitions) {
        var start = LocalDate.of(2026, 1, 1);
        assertThatThrownBy(() -> ProjectPhases.initialize(new PerformancePeriod(start, start), definitions))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(400);
                assertThat(error.field()).isEqualTo("phases");
            });
    }
}
