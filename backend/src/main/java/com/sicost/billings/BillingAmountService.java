package com.sicost.billings;

import com.sicost.common.validation.BillingValues;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billings.BillingModels.*;

@Service
@Transactional(readOnly = true)
public class BillingAmountService {
    private final BillingMapper mapper;
    public BillingAmountService(BillingMapper mapper) { this.mapper = mapper; }
    public Amount calculate(Project project, String month, List<Overtime> overrides) {
        YearMonth ym = BillingValues.month(month, "month");
        var sources = mapper.sources(new SourceScope(project.prj_id(), month, ym.atDay(1), ym.atEndOfMonth()));
        Map<Long, Long> overtime = new HashMap<>();
        for (var input : overrides) overtime.put(input.asgn_id(), input.ovtm_amt());
        Set<Long> used = new HashSet<>();
        List<DetailLine> details = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long sum = 0;
        for (var source : sources) {
            used.add(source.asgn_id());
            boolean free = "FREE".equals(source.asgn_se_cd());
            String basis = "MO".equals(project.cntrct_ty_cd()) ? "MONTH" : project.stlm_mthd_cd();
            if (!free && (source.untprc_amt() == null || source.untprc_amt() <= 0))
                throw new com.sicost.common.api.ApiException(400, "VALIDATION_ERROR",
                    source.display_name() + "의 적용 청구 단가가 없습니다.", "details");
            BigDecimal mm = source.mm_qty();
            BigDecimal quantity = mm;
            long extra = overtime.getOrDefault(source.asgn_id(), 0L);
            if (!"MONTH".equals(basis)) {
                if (source.atnd_id() == null || ("DAY".equals(basis) ? source.work_day_qty() : source.work_tm_qty()) == null) {
                    warnings.add(source.display_name() + " 근태 미입력: 청구 상세에서 제외했습니다.");
                    continue;
                }
                quantity = "DAY".equals(basis) ? source.work_day_qty() : source.work_tm_qty();
                mm = source.work_day_qty() == null || source.mth_work_day_qty() == null
                    || source.mth_work_day_qty().signum() <= 0 ? null
                    : source.work_day_qty().divide(source.mth_work_day_qty(), 2, RoundingMode.HALF_UP);
                if (mm == null) warnings.add(source.display_name() + " 근무일수 미입력: 산출 MM 없음");
            } else if (quantity == null) {
                throw BillingValues.invalid("details.mm_qty");
            }
            if (!"HOUR".equals(basis) && extra != 0) throw BillingValues.invalid("details.ovtm_amt");
            long amount = free ? 0 : BillingValues.floorWon(
                BigDecimal.valueOf(source.untprc_amt()).multiply(quantity), "sply_amt");
            if (!free && "HOUR".equals(basis)) amount = BillingValues.add(amount, extra, "sply_amt");
            sum = BillingValues.add(sum, amount, "sply_amt");
            details.add(new DetailLine(source.asgn_id(), source.dev_id(), source.cntrct_dev_id(),
                source.display_name(), month, mm, source.mm_qty(), source.untprc_amt(),
                source.work_day_qty(), source.work_tm_qty(), source.ovtm_tm_qty(), extra, amount, null));
        }
        if (!used.containsAll(overtime.keySet())) throw BillingValues.invalid("details.asgn_id");
        return new Amount(sum, List.copyOf(details), List.copyOf(warnings));
    }
}
