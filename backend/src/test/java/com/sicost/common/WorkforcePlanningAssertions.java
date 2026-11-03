package com.sicost.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared assertions for forecast consumers and selected-engine persistence tests. */
public final class WorkforcePlanningAssertions {
    private WorkforcePlanningAssertions() {}

    private record Bucket(String month, String job, String grade) {}

    public static void assertForecastItemsEqual(JsonNode expectedPage, JsonNode actualPage) {
        var expected = forecastItems(expectedPage);
        var actual = forecastItems(actualPage);
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        expected.forEach((key, row) -> assertThat(actual.get(key)).as("forecast bucket %s", key).isEqualTo(row));
    }

    private static Map<Bucket,JsonNode> forecastItems(JsonNode page) {
        assertThat(page).isNotNull();
        assertThat(page.path("items").isArray()).isTrue();
        Map<Bucket,JsonNode> result = new LinkedHashMap<>();
        for (var row : page.path("items")) {
            assertThat(row.path("month").isTextual()).isTrue();
            assertThat(row.path("job_cd").isTextual()).isTrue();
            var grade = row.get("grd_cd");
            var key = new Bucket(row.path("month").asText(), row.path("job_cd").asText(),
                grade == null || grade.isNull() ? "" : grade.asText());
            assertThat(result.put(key, row)).as("duplicate forecast bucket %s", key).isNull();
        }
        return result;
    }

    public static ClosedSources captureClosedSources(JdbcTemplate jdbc) {
        Map<String,String> rows = new LinkedHashMap<>();
        rows.put("fin.mth_cls", snapshot(jdbc, "fin.mth_cls", "true"));
        for (var source : List.of(
                Map.entry("asgn.asgn_mth", "t.work_ym"),
                Map.entry("cst.mth_cst", "t.cst_ym"),
                Map.entry("cst.drct_expns", "t.expns_ym"),
                Map.entry("cst.cst_aloc", "t.cst_ym"),
                Map.entry("bill.bill", "coalesce(t.atrb_ym,to_char(t.bill_dt,'YYYY-MM'))"),
                Map.entry("bill.bill_dtl", "t.work_ym"),
                Map.entry("fin.mth_pl", "t.pl_ym"),
                Map.entry("fin.mth_pl_dtl", "t.pl_ym"))) {
            rows.put(source.getKey(), snapshot(jdbc, source.getKey(),
                "EXISTS (SELECT 1 FROM fin.mth_cls c WHERE c.cls_stts_cd='CLOSED' AND c.cls_ym="
                    + source.getValue() + ")"));
        }
        return new ClosedSources(rows);
    }

    private static String snapshot(JdbcTemplate jdbc, String table, String predicate) {
        // Identifiers are exclusively the private, source-proven whitelist above, never request values.
        return jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "
            + table + " t WHERE " + predicate, String.class);
    }

    public static final class ClosedSources {
        private final Map<String,String> rows;
        private ClosedSources(Map<String,String> rows) { this.rows = Map.copyOf(rows); }
        public void assertUnchanged(JdbcTemplate jdbc) {
            var current = captureClosedSources(jdbc);
            rows.forEach((table, before) -> assertTrue(Objects.equals(before, current.rows.get(table)),
                "Closed source changed: " + table));
        }
    }
}
