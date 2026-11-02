package com.sicost.adjustments;

import com.sicost.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

public final class AdjustmentDeviceGuard {
    private static final Pattern MOBILE=Pattern.compile("(?i)android|iphone|ipad|ipod|mobile|windows phone");
    private AdjustmentDeviceGuard() {}
    public static void requireDesktop(HttpServletRequest request) {
        String agent=request.getHeader("User-Agent");
        if ("?1".equals(request.getHeader("Sec-CH-UA-Mobile")) || (agent!=null && MOBILE.matcher(agent).find())) {
            throw ApiException.conflict("PC_ONLY","등록·취소는 PC에서 이용해 주세요.",null);
        }
    }
}
