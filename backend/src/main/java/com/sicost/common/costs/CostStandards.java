package com.sicost.common.costs;

import com.sicost.apiops.ProjectBoardMapper;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CostStandards {
    private final ProjectBoardMapper mapper;
    public CostStandards(ProjectBoardMapper mapper) { this.mapper = mapper; }
    public BigDecimal effective(String month, String businessKey) {
        CostAllocationService.month(month);
        String storedKey = "RENEG_COST_RATE".equals(businessKey) ? "RENEGO_COST_RATIO" : businessKey;
        if (!Set.of("RENEGO_COST_RATIO", "TARGET_MARGIN_RATE", "RENEGO_RAISE_RATE").contains(storedKey)) {
            throw new IllegalArgumentException("원가 분석 기준값이 아닙니다.");
        }
        return mapper.params(month).stream().filter(p -> storedKey.equals(p.oprt_std_id()))
            .filter(p -> p.num_val() != null).map(p -> p.num_val()).findFirst()
            .orElseThrow(() -> new ApiException(400, "VALIDATION_ERROR", "적용월의 원가 분석 기준값이 없습니다.", "month"));
    }
}
