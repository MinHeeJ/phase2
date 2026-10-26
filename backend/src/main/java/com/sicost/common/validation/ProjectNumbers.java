package com.sicost.common.validation;

import com.sicost.common.api.ApiException;
import java.time.LocalDate;
import java.util.Locale;

public final class ProjectNumbers {
    private ProjectNumbers() {}

    public static String supplied(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.matches("P-[0-9]{4,5}")) {
            throw ApiException.badRequest("프로젝트ID는 P-와 숫자 4~5자리로 입력하세요.", "prj_no");
        }
        return value;
    }

    // The caller reads the year's numeric maximum under pg_advisory_xact_lock in its write transaction.
    public static String allocated(LocalDate contractStart, int maximumSuffix) {
        if (contractStart == null || contractStart.getYear() < 1 || contractStart.getYear() > 9999) {
            throw ApiException.badRequest("계약 시작일을 확인하세요.", "cntrct_bgn_dt");
        }
        if (maximumSuffix < 0 || maximumSuffix >= 999) {
            throw ApiException.badRequest("해당 연도의 프로젝트 번호 발급 범위를 초과했습니다.", "prj_no");
        }
        return String.format(Locale.ROOT, "P-%02d%02d", contractStart.getYear() % 100, maximumSuffix + 1);
    }

    public static ApiException duplicate() {
        return ApiException.conflict("DUPLICATE_PRJ_NO", "이미 있는 프로젝트ID입니다.", "prj_no");
    }
}
