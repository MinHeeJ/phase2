package com.sicost.changes;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.time.*;
import java.util.*;
import static com.sicost.changes.AssignmentChangeModels.*;

public final class AssignmentChangeInput {
    private AssignmentChangeInput() {}
    static ApiException error(String field) { return ApiException.badRequest("입력값을 확인하세요.", field); }
    public static String type(String value) {
        if (value != null && !Set.of("START", "EXTEND", "EARLY_END", "RATE", "EXT_CHANCE", "PERIOD", "REPLACE", "FIX").contains(value)) {
            throw error("type");
        }
        return value;
    }
    private static void fields(JsonNode n, Set<String> allowed) {
        if (n == null || !n.isObject()) throw error(null);
        n.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw error(k); });
    }
    static String text(JsonNode n, String key, int max, boolean required) {
        var v = n.get(key);
        if (v == null || v.isNull()) { if (required) throw error(key); return null; }
        if (!v.isTextual()) throw error(key);
        String s = v.textValue().trim();
        if (s.length() > max || (required && s.isEmpty())) throw error(key);
        return s.isEmpty() ? null : s;
    }
    static LocalDate date(JsonNode n, String key, boolean required) {
        String s = text(n, key, 10, required);
        if (s == null) return null;
        try { return LocalDate.parse(s); } catch (DateTimeException e) { throw error(key); }
    }
    static LocalDateTime version(JsonNode n) {
        String s = text(n, "updated_at", 40, true);
        try {
            try { return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime(); }
            catch (DateTimeException e) { return LocalDateTime.parse(s); }
        } catch (DateTimeException e) { throw error("updated_at"); }
    }
    static Long number(JsonNode n, String key, boolean required) {
        var v = n.get(key);
        if (v == null || v.isNull()) { if (required) throw error(key); return null; }
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 1) throw error(key);
        return v.longValue();
    }
    public static ChangeInput parse(JsonNode n) {
        if (n == null || !n.isObject()) throw error(null);
        String type = text(n, "asgn_chg_ty_cd", 10, true);
        var allowed = new HashSet<>(Set.of("updated_at", "asgn_chg_ty_cd", "aply_dt", "rsn_cd", "rsn_desc"));
        switch (type) {
            case "EXTEND" -> allowed.add("end_dt");
            case "EARLY_END" -> { }
            case "RATE" -> allowed.addAll(Set.of("work_ym", "bill_untprc_amt"));
            case "EXT_CHANCE" -> allowed.add("ext_psblty_cd");
            case "PERIOD" -> allowed.addAll(Set.of("asgn_se_cd", "bgn_dt", "end_dt", "cntrct_dev_id"));
            default -> throw error("asgn_chg_ty_cd");
        }
        fields(n, allowed);
        if (type.equals("EXT_CHANCE") && !n.has("ext_psblty_cd")) throw error("ext_psblty_cd");
        String chance = text(n, "ext_psblty_cd", 1, false);
        if (chance != null && !Set.of("H", "M", "L").contains(chance)) throw error("ext_psblty_cd");
        String kind = text(n, "asgn_se_cd", 10, type.equals("PERIOD"));
        if (kind != null && !Set.of("NEW", "ADD", "SUB", "MOV", "FREE").contains(kind)) throw error("asgn_se_cd");
        String month = text(n, "work_ym", 7, type.equals("RATE"));
        if (month != null) {
            try { YearMonth.parse(month); } catch (DateTimeException e) { throw error("work_ym"); }
        }
        return new ChangeInput(version(n), type, date(n, "aply_dt", true), text(n, "rsn_cd", 30, false),
            text(n, "rsn_desc", 200, false), date(n, "end_dt", type.equals("EXTEND")), month,
            number(n, "bill_untprc_amt", type.equals("RATE")), chance, kind,
            date(n, "bgn_dt", false), number(n, "cntrct_dev_id", false));
    }
    public static FixInput fix(JsonNode n) {
        fields(n, Set.of("updated_at", "aft_val", "rsn_cd", "rsn_desc"));
        String after = text(n, "aft_val", 200, false);
        if (after == null) throw ApiException.badRequest("바른 내용을 적어 주세요.", "aft_val");
        String reason = text(n, "rsn_cd", 30, false);
        if (reason == null) throw ApiException.badRequest("사유를 골라 주세요.", "rsn_cd");
        return new FixInput(version(n), after, reason, text(n, "rsn_desc", 200, false));
    }
}
