package com.sicost.assignmentsops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.AssignmentRules;
import java.time.*;
import java.util.*;
import static com.sicost.assignmentsops.AssignmentOperationModels.*;

public final class AssignmentOperationInput {
    private AssignmentOperationInput() {}
    static ApiException error(String field) { return ApiException.badRequest("입력값을 확인하세요.", field); }
    static void object(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) throw error(null);
        var allowed = Set.of(fields);
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw error(key); });
    }
    static String text(JsonNode node, String field, int max, boolean required) {
        var value = node.get(field);
        if (value == null || value.isNull()) { if (required) throw error(field); return null; }
        if (!value.isTextual()) throw error(field);
        String result = value.textValue().trim();
        if (result.length() > max || (required && result.isEmpty())) throw error(field);
        return result;
    }
    static long id(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw error(field);
        }
        return value.longValue();
    }
    static LocalDate date(JsonNode node, String field) {
        try { return LocalDate.parse(text(node, field, 10, true)); }
        catch (java.time.format.DateTimeParseException e) { throw error(field); }
    }
    static LocalDateTime timestamp(JsonNode node) {
        try { return LocalDateTime.parse(text(node, "updated_at", 40, true)); }
        catch (java.time.format.DateTimeParseException e) { throw error("updated_at"); }
    }
    static String chance(JsonNode node, String field, boolean required) {
        if (required && !node.has(field)) throw error(field);
        String value = text(node, field, 1, false);
        if (value != null && !Set.of("H", "M", "L").contains(value)) throw error(field);
        return value;
    }
    public static BatchRequest batch(JsonNode node, boolean chance) {
        if (chance) object(node, "ids", "versions", "value"); else object(node, "ids", "versions");
        if (!node.path("ids").isArray() || node.path("ids").isEmpty()) throw error("ids");
        if (!node.path("versions").isArray() || node.path("versions").isEmpty()) throw error("versions");
        List<Long> ids = new ArrayList<>();
        for (var value : node.get("ids")) {
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) throw error("ids");
            ids.add(value.longValue());
        }
        List<Version> versions = new ArrayList<>();
        for (var value : node.get("versions")) {
            object(value, "asgn_id", "updated_at");
            versions.add(new Version(id(value, "asgn_id"), timestamp(value)));
        }
        var keys = versions.stream().map(Version::asgn_id).toList();
        if (new HashSet<>(ids).size() != ids.size() || new HashSet<>(keys).size() != keys.size()
                || !new HashSet<>(ids).equals(new HashSet<>(keys))) throw error("versions");
        return new BatchRequest(List.copyOf(ids), List.copyOf(versions), chance ? chance(node, "value", true) : null);
    }
    public static ReplacementRequest replacement(JsonNode node) {
        object(node, "updated_at", "aply_dt", "dev_id", "rsn_cd", "rsn_desc");
        return new ReplacementRequest(timestamp(node), date(node, "aply_dt"), id(node, "dev_id"),
            text(node, "rsn_cd", 30, true), text(node, "rsn_desc", 200, false));
    }
    public static Proposal proposal(JsonNode node) {
        object(node, "prj_id", "dev_id", "job_cd", "bgn_dt", "end_dt", "asgn_rt", "bill_untprc_amt",
            "untprc_base_cd", "asgn_se_cd", "cntrct_dev_id", "ext_psblty_cd", "rmrk_desc", "rsn_cd", "rsn_desc");
        if (!node.path("asgn_rt").isNumber()) throw error("asgn_rt");
        var rate = node.get("asgn_rt").decimalValue();
        if (rate.signum() <= 0 || rate.compareTo(java.math.BigDecimal.ONE) > 0) throw error("asgn_rt");
        var period = new AssignmentRules.Period(date(node, "bgn_dt"), date(node, "end_dt"), rate);
        String kind = text(node, "asgn_se_cd", 10, true);
        if (!Set.of("NEW", "ADD", "SUB", "MOV", "FREE").contains(kind)) throw error("asgn_se_cd");
        Long contract = node.hasNonNull("cntrct_dev_id") ? id(node, "cntrct_dev_id") : null;
        if (kind.equals("SUB") && contract == null) throw error("cntrct_dev_id");
        return new Proposal(id(node, "prj_id"), id(node, "dev_id"), text(node, "job_cd", 30, true),
            period.start(), period.end(), period.rate(), node.hasNonNull("bill_untprc_amt") ? id(node, "bill_untprc_amt") : null,
            text(node, "untprc_base_cd", 10, false), kind, kind.equals("SUB") ? contract : null,
            chance(node, "ext_psblty_cd", false), text(node, "rmrk_desc", 500, false),
            text(node, "rsn_cd", 30, false), text(node, "rsn_desc", 200, false));
    }
}
