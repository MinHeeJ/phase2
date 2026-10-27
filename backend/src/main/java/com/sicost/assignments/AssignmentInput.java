package com.sicost.assignments;

import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.AssignmentRules;
import java.time.*;
import java.util.*;
import static com.sicost.assignments.AssignmentModels.*;

public final class AssignmentInput {
    private AssignmentInput() {}
    static ApiException error(String field) { return ApiException.badRequest("입력값을 확인하세요.", field); }
    public static CreateInput parse(JsonNode node, ObjectMapper json) {
        if (node == null || !node.isObject()) throw error(null);
        var fields = Set.of("prj_id", "dev_id", "job_cd", "bgn_dt", "end_dt", "asgn_rt",
            "bill_untprc_amt", "untprc_base_cd", "asgn_se_cd", "cntrct_dev_id",
            "ext_psblty_cd", "rmrk_desc", "rsn_cd", "rsn_desc");
        node.fieldNames().forEachRemaining(k -> { if (!fields.contains(k)) throw error(k); });
        for (String k : List.of("prj_id", "dev_id", "cntrct_dev_id", "bill_untprc_amt")) {
            var v = node.get(k);
            if (v != null && !v.isNull() && (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 1)) throw error(k);
        }
        if (!node.hasNonNull("prj_id")) throw error("prj_id");
        if (!node.hasNonNull("dev_id")) throw error("dev_id");
        if (!node.hasNonNull("asgn_rt") || !node.get("asgn_rt").isNumber()) throw error("asgn_rt");
        String job = text(node, "job_cd", 30, true);
        String kind = text(node, "asgn_se_cd", 10, true);
        if (!Set.of("NEW", "ADD", "SUB", "MOV", "FREE").contains(kind)) throw error("asgn_se_cd");
        String chance = text(node, "ext_psblty_cd", 10, false);
        if (chance != null && !Set.of("H", "M", "L").contains(chance)) throw error("ext_psblty_cd");
        String basis = text(node, "untprc_base_cd", 10, false);
        if (basis != null && !Set.of("MONTH", "DAY", "HOUR").contains(basis)) throw error("untprc_base_cd");
        LocalDate start = date(node, "bgn_dt"), end = date(node, "end_dt");
        var period = new AssignmentRules.Period(start, end, node.get("asgn_rt").decimalValue());
        Long contract = node.hasNonNull("cntrct_dev_id") ? node.get("cntrct_dev_id").longValue() : null;
        if (kind.equals("SUB") && contract == null) throw error("cntrct_dev_id");
        return new CreateInput(node.get("prj_id").longValue(), node.get("dev_id").longValue(), job,
            start, end, period.rate(), node.hasNonNull("bill_untprc_amt") ? node.get("bill_untprc_amt").longValue() : null,
            basis, kind, kind.equals("SUB") ? contract : null, chance,
            text(node, "rmrk_desc", 500, false), text(node, "rsn_cd", 30, false), text(node, "rsn_desc", 200, false));
    }
    private static String text(JsonNode node, String key, int max, boolean required) {
        var n = node.get(key);
        if (n == null || n.isNull()) { if (required) throw error(key); return null; }
        if (!n.isTextual()) throw error(key);
        String value = n.textValue().trim();
        if (value.length() > max || (required && value.isEmpty())) throw error(key);
        return value.isEmpty() ? null : value;
    }
    private static LocalDate date(JsonNode node, String key) {
        try { return LocalDate.parse(text(node, key, 10, true)); }
        catch (java.time.format.DateTimeParseException ex) { throw error(key); }
    }
    public static Query query(Map<String, String> values, Clock clock) {
        int year = integer(values, "year", null), page = integer(values, "page", 0), size = integer(values, "size", 50);
        if (year < 1 || year > 9999) throw error("year");
        if (page < 0) throw error("page");
        if (!Set.of(20, 50, 100).contains(size)) throw error("size");
        String warning = blank(values.get("warning"));
        if (warning != null && !Set.of("OVER_MM", "PRE_CONTRACT", "ON_LEAVE", "DISPATCH_NONREG").contains(warning)) throw error("warning");
        Long project = null;
        if (blank(values.get("projectId")) != null) {
            try { project = Long.valueOf(values.get("projectId")); } catch (NumberFormatException ex) { throw error("projectId"); }
            if (project < 1) throw error("projectId");
        }
        String sort = values.getOrDefault("sort", "prj_nm,asc;bgn_dt,asc");
        var columns = Set.of("asgn_no", "prj_nm", "dev_nm", "job_cd", "bgn_dt", "end_dt",
            "bill_untprc_amt", "current_month_mm_qty", "year_mm_qty", "ext_psblty_cd", "asgn_se_cd");
        for (String part : sort.split(";", -1)) {
            String[] pair = part.split(",", -1);
            if (pair.length != 2 || !columns.contains(pair[0]) || !Set.of("asc", "desc").contains(pair[1])) throw error("sort");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        return new Query(year, project, blank(values.get("job")), warning, blank(values.get("q")), page,
            size, sort, today, YearMonth.from(today).toString(), LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static int integer(Map<String, String> values, String key, Integer fallback) {
        if (!values.containsKey(key)) { if (fallback == null) throw error(key); return fallback; }
        try { return Integer.parseInt(values.get(key)); } catch (NumberFormatException ex) { throw error(key); }
    }
}
