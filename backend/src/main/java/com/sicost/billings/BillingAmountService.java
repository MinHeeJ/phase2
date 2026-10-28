package com.sicost.billings;

import com.sicost.common.validation.*;
import java.math.*;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.sicost.billings.BillingModels.*;

@Service
public class BillingAmountService {
    private final BillingMapper mapper;
    public BillingAmountService(BillingMapper mapper) { this.mapper = mapper; }

    public Calculation calculate(Project project, Stage stage, String month, List<Overtime> overtime) {
        var lines = new ArrayList<DetailLine>();
        var warnings = new ArrayList<String>();
        if (stage != null) {
            lines.add(new DetailLine(null, null, null, "청구 단계", month, null, null, null,
                null, null, null, 0, stage.sply_amt(), null));
            return new Calculation(List.copyOf(lines), List.copyOf(warnings), stage.sply_amt());
        }
        var amounts = new HashMap<Long, Long>();
        for (var item : overtime) {
            if (item.asgn_id() == null || item.ovtm_amt() == null || item.ovtm_amt() < 0
                    || amounts.put(item.asgn_id(), item.ovtm_amt()) != null) {
                throw BillingValues.invalid("details");
            }
        }
        long total = 0;
        for (var source : mapper.sources(new SourceKey(project.prj_id(), month))) {
            boolean free = "FREE".equals(source.asgn_se_cd());
            if (source.untprc_amt() == null && !free) throw BillingValues.invalid("untprc_amt");
            boolean attendance = "DP".equals(project.cntrct_ty_cd())
                && Set.of("DAY", "HOUR").contains(Objects.toString(project.stlm_mthd_cd(), ""));
            if (attendance && (source.atnd_id() == null
                    || ("DAY".equals(project.stlm_mthd_cd()) && source.work_day_qty() == null)
                    || ("HOUR".equals(project.stlm_mthd_cd()) && source.rglr_tm_qty() == null))) {
                warnings.add(source.display_name() + ": 근태 미입력으로 상세에서 제외했습니다.");
                amounts.remove(source.asgn_id());
                continue;
            }
            BigDecimal mm = source.mm_qty();
            BigDecimal quantity = mm;
            long extra = amounts.getOrDefault(source.asgn_id(), 0L);
            amounts.remove(source.asgn_id());
            if (attendance) {
                BigDecimal denominator = source.mth_work_day_qty();
                if (denominator == null || denominator.signum() <= 0
                        || denominator.compareTo(BigDecimal.valueOf(YearMonth.parse(month).lengthOfMonth())) > 0) {
                    throw BillingValues.invalid("mth_work_day_qty");
                }
                mm = source.work_day_qty() == null ? null
                    : source.work_day_qty().divide(denominator, 2, RoundingMode.HALF_UP);
                quantity = "DAY".equals(project.stlm_mthd_cd())
                    ? source.work_day_qty() : source.rglr_tm_qty();
            }
            if (quantity == null || quantity.signum() < 0) throw BillingValues.invalid("mm_qty");
            long price = source.untprc_amt() == null ? 0 : source.untprc_amt();
            if (price < 0) throw BillingValues.invalid("untprc_amt");
            if (!"HOUR".equals(project.stlm_mthd_cd()) && extra != 0) throw BillingValues.invalid("ovtm_amt");
            long amount = free ? 0 : BillingAmounts.exact(quantity.multiply(BigDecimal.valueOf(price))
                .setScale(0, RoundingMode.FLOOR).add(BigDecimal.valueOf(extra)), "bill_amt");
            total = BillingAmounts.exact(BigDecimal.valueOf(total).add(BigDecimal.valueOf(amount)), "sply_amt");
            lines.add(new DetailLine(source.asgn_id(), source.dev_id(), source.cntrct_dev_id(),
                source.display_name(), month, mm, source.mm_qty(), price,
                attendance ? source.work_day_qty() : null, attendance ? source.rglr_tm_qty() : null,
                attendance ? source.ovtm_tm_qty() : null, free ? 0 : extra, amount, null));
        }
        if (!amounts.isEmpty()) throw BillingValues.invalid("asgn_id");
        return new Calculation(List.copyOf(lines), List.copyOf(warnings), total);
    }
}
