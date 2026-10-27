package com.sicost.memos;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.util.Set;
import static com.sicost.memos.ProjectMemoModels.*;

public final class ProjectMemoInput {
    private ProjectMemoInput() {}
    public static void id(long value, String field) {
        if (value < 1) throw ApiException.badRequest("식별자를 확인하세요.", field);
    }
    public static MemoInput parse(JsonNode node) {
        if (node == null || !node.isObject()) throw ApiException.badRequest("입력값을 확인하세요.", null);
        var allowed = Set.of("actl_prgs_rt", "cmpl_work_desc", "plan_work_desc", "rqst_desc");
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw ApiException.badRequest("변경할 수 없는 항목입니다.", key);
        });
        return new MemoInput(node.get("actl_prgs_rt"), text(node, "cmpl_work_desc", true),
            text(node, "plan_work_desc", true), text(node, "rqst_desc", false));
    }
    private static String text(JsonNode node, String key, boolean required) {
        var value = node.get(key);
        if (value == null || value.isNull()) {
            if (required) throw textError(key);
            return null;
        }
        if (!value.isTextual()) throw textError(key);
        String result = value.textValue().trim();
        if (result.length() > 200 || required && result.isEmpty()) throw textError(key);
        return result.isEmpty() ? null : result;
    }
    private static ApiException textError(String field) {
        return ApiException.badRequest(field.equals("rqst_desc") ? "요청 사항은 200자 이내로 적어 주세요."
            : "한 일과 할 일을 적어 주세요.", field);
    }
}
