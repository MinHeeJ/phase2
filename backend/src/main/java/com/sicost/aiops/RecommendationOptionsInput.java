package com.sicost.aiops;

import com.sicost.common.api.ApiException;
import java.util.Map;
import java.util.Set;
import static com.sicost.aiops.RecommendationOptionsModels.*;

public final class RecommendationOptionsInput {
    private RecommendationOptionsInput() {}
    public static Query parse(Map<String,String> values) {
        for (String key : values.keySet()) {
            if (!Set.of("direction", "sourceId", "devId", "search").contains(key))
                throw ApiException.badRequest("지원하지 않는 조회 조건입니다.", key);
        }
        String direction = values.get("direction");
        if (direction != null && !Set.of("PROJECT_TO_PERSON", "PERSON_TO_POSITION").contains(direction))
            throw ApiException.badRequest("추천 방향을 확인하세요.", "direction");
        String search = values.get("search");
        if (search != null) {
            search = search.trim();
            if (search.length() > 200 || search.chars().anyMatch(Character::isISOControl))
                throw ApiException.badRequest("검색어를 확인하세요.", "search");
            if (search.isEmpty()) search = null;
        }
        return new Query(direction, id(values.get("sourceId"), "sourceId"), id(values.get("devId"), "devId"), search);
    }
    private static Long id(String value, String field) {
        if (value == null) return null;
        try {
            if (!value.matches("[0-9]{1,19}")) throw new NumberFormatException();
            long parsed = Long.parseLong(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) { throw ApiException.badRequest("식별자를 확인하세요.", field); }
    }
}
