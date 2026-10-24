package com.sicost.common.validation;

import com.sicost.common.api.ApiException;
import java.util.Locale;

public final class DeveloperManagementNumbers {
    private DeveloperManagementNumbers() {}

    public static String supplied(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.matches("[A-Z0-9-]{1,20}")) {
            throw ApiException.badRequest("관리번호는 영문 대문자·숫자·하이픈 1~20자로 입력하세요.", "mng_no");
        }
        return value;
    }

    public static String allocated(long devId) {
        if (devId < 1) throw ApiException.badRequest("개발자 번호를 확인하세요.", "dev_id");
        return "K-" + String.format(Locale.ROOT, "%04d", devId);
    }

    public static ApiException duplicate() {
        return ApiException.conflict("DUPLICATE_MNG_NO", "이미 있는 관리번호입니다.", "mng_no");
    }
}
