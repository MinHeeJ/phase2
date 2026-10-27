package com.sicost.issues;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.*;
import com.sicost.common.validation.CalendarValues;
import java.time.LocalDate;
import java.util.*;
import static com.sicost.issues.IssueModels.*;

public final class IssueInputParser {
    private IssueInputParser() {}
    static ApiException error(String field) { return ApiException.badRequest("입력값을 확인하세요.",field); }
    static void object(JsonNode node, boolean create) {
        if (node == null || !node.isObject()) throw error(null);
        var allowed = new HashSet<>(Set.of("ttl_nm","svrt_cd","chrg_nm","ddln_dt","issue_stts_cd","actn_desc"));
        if (create) allowed.add("prj_id");
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw error(key); });
    }
    public static long id(long id, String field) { if (id < 1) throw error(field); return id; }
    public static IssueCreate create(JsonNode node) {
        object(node,true);
        var project = node.get("prj_id");
        if (project == null || project.isNull()) throw ApiException.badRequest("프로젝트를 골라 주세요.","prj_id");
        if (!project.isIntegralNumber() || !project.canConvertToLong()) throw error("prj_id");
        return new IssueCreate(id(project.longValue(),"prj_id"),fields(node));
    }
    public static IssueInput update(JsonNode node) { object(node,false); return fields(node); }
    static IssueInput fields(JsonNode node) {
        String title = text(node,"ttl_nm",100);
        if (title == null || title.isBlank()) throw ApiException.badRequest("제목을 넣어 주세요.","ttl_nm");
        String deadline = text(node,"ddln_dt",10);
        if (deadline == null || deadline.isBlank()) throw ApiException.badRequest("기한을 넣어 주세요.","ddln_dt");
        return new IssueInput(title,choice(text(node,"svrt_cd",10),"svrt_cd",Set.of("HIGH","MID","LOW")),
            text(node,"chrg_nm",50),CalendarValues.date(deadline,"ddln_dt"),
            choice(text(node,"issue_stts_cd",10),"issue_stts_cd",Set.of("OPEN","DOING","CLOSED")),text(node,"actn_desc",300));
    }
    static String text(JsonNode node, String key, int max) {
        var value = node.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().length() > max) throw error(key);
        return value.textValue();
    }
    static String choice(String value, String key, Set<String> choices) {
        if (value == null || !choices.contains(value)) throw error(key); return value;
    }
    public static IssueQuery query(Map<String,String> params, LocalDate today) {
        String status = choice(params.getOrDefault("status","OPEN_DOING"),"status",Set.of("OPEN_DOING","OVERDUE","CLOSED"));
        String severity = params.get("severity");
        if (severity != null) choice(severity,"severity",Set.of("HIGH","MID","LOW"));
        Long project = null;
        if (params.containsKey("projectId")) {
            try { project = id(Long.parseLong(params.get("projectId")),"projectId"); }
            catch (NumberFormatException ex) { throw error("projectId"); }
        }
        int page = integer(params,"page",0), size = integer(params,"size",50);
        new DeveloperPaging(page,size,null);
        return new IssueQuery(status,severity,project,today,page,size,(long)page*size);
    }
    static int integer(Map<String,String> params, String key, int fallback) {
        if (!params.containsKey(key)) return fallback;
        try { return Integer.parseInt(params.get(key)); }
        catch (NumberFormatException ex) { throw error(key); }
    }
}
