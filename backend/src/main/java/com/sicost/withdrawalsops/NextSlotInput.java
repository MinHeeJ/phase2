package com.sicost.withdrawalsops;

import com.sicost.common.api.ApiException;
import java.util.Map;

public final class NextSlotInput {
    private NextSlotInput() {}
    public static long parse(String identifier, Map<String,String> query) {
        if (!query.isEmpty()) throw ApiException.badRequest("지원하지 않는 조회 조건입니다.",
            query.keySet().stream().sorted().findFirst().orElseThrow());
        try {
            if (!identifier.matches("[0-9]{1,19}")) throw new NumberFormatException();
            long id = Long.parseLong(identifier);
            if (id < 1) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("투입 식별자를 확인하세요.", "asgnId");
        }
    }
}
