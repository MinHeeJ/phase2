package com.sicost.partchanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CalendarValues;
import java.util.Set;
import static com.sicost.partchanges.PartChangeModels.*;

public final class PartChangeInput {
    private PartChangeInput() {}

    public static PartChangeRequest parse(JsonNode node) {
        if (node == null || !node.isObject()) throw ApiException.badRequest("입력값을 확인하세요.", null);
        var allowed = Set.of("bsn_part_id", "aply_bgn_ym", "rsn_desc");
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw ApiException.badRequest("변경할 수 없는 항목입니다.", key);
        });
        JsonNode part = node.get("bsn_part_id");
        if (part == null || !part.isIntegralNumber() || !part.canConvertToLong()) {
            throw ApiException.badRequest("새 영업 파트를 골라 주세요.", "bsn_part_id");
        }
        return validate(new PartChangeRequest(part.longValue(), text(node, "aply_bgn_ym"), text(node, "rsn_desc")));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw ApiException.badRequest("입력값을 확인하세요.", field);
        return value.textValue();
    }

    public static PartChangeRequest validate(PartChangeRequest input) {
        if (input == null) throw ApiException.badRequest("입력값을 확인하세요.", null);
        if (input.bsn_part_id() == null || input.bsn_part_id() < 1) {
            throw ApiException.badRequest("새 영업 파트를 골라 주세요.", "bsn_part_id");
        }
        CalendarValues.month(input.aply_bgn_ym(), "aply_bgn_ym");
        String reason = input.rsn_desc() == null ? "" : input.rsn_desc().trim();
        if (reason.isBlank()) throw ApiException.badRequest("사유를 입력해 주세요.", "rsn_desc");
        if (reason.length() > 200) throw ApiException.badRequest("사유는 200자 이하로 입력해 주세요.", "rsn_desc");
        return new PartChangeRequest(input.bsn_part_id(), input.aply_bgn_ym(), reason);
    }

    public static long id(long id) {
        if (id < 1) throw ApiException.badRequest("프로젝트 번호를 확인하세요.", "id");
        return id;
    }
}
