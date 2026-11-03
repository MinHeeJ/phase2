package com.sicost.common.workforce;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Read-only projection supplied by the common WorkforceForecastService, before pagination. */
public interface CandidateForecastReader {
    List<Month> candidateMonths(Map<String,String> filters);
    record Month(String month, String job_cd, String grd_cd, BigDecimal shortage_mm) {}
}
