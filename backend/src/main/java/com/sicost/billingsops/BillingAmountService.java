package com.sicost.billingsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.validation.BillingRules;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service("billingPreviewAmountService")
@Transactional(readOnly = true)
public class BillingAmountService {
    private final BillingMapper mapper;
    private final Clock clock;
    public BillingAmountService(BillingMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }
    public static SourceKey sourceKey(long id, String month) {
        YearMonth ym = BillingRules.month(month, "month");
        return new SourceKey(id, ym.toString(), ym.atDay(1), ym.atEndOfMonth());
    }
    public Preview preview(long projectId, String month, Long stageId) {
        if (projectId < 1) throw BillingRules.invalid("projectId");
        Project project = mapper.project(projectId);
        if (project == null) throw ApiException.notFound("프로젝트가 없습니다.");
        BillingRules.contractType(project.contract_type());
        var details = new ArrayList<DetailLine>();
        var warnings = new ArrayList<String>();
        var missing = new ArrayList<Long>();
        long supply = 0;
        LocalDate due = null;
        if ("TK".equals(project.contract_type())) {
            if (stageId == null || stageId < 1) throw BillingRules.invalid("stageId");
            Stage stage = mapper.stage(new StageKey(projectId, stageId));
            if (stage == null) throw BillingRules.invalid("stageId");
            supply = stage.sply_amt();
            due = stage.bill_plan_dt();
            month = YearMonth.from(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))).toString();
        } else {
            if (stageId != null) throw BillingRules.invalid("stageId");
            SourceKey key = sourceKey(projectId, month);
            String unit = project.settlement();
            if (!Set.of("MONTH", "DAY", "HOUR").contains(unit == null ? "" : unit)
                || ("MO".equals(project.contract_type()) && !"MONTH".equals(unit))) {
                throw BillingRules.invalid("settlement");
            }
            for (Source source : mapper.sources(key)) {
                boolean free = "FREE".equals(source.kind());
                if (!free && source.price() == null) {
                    missing.add(source.asgn_id());
                    warnings.add(source.display_name() + ": 청구 단가 없음");
                    continue;
                }
                if (!"MONTH".equals(unit) && (source.atnd_id() == null
                    || source.mth_work_day_qty() == null || source.mth_work_day_qty().signum() <= 0
                    || ("DAY".equals(unit) ? source.work_day_qty() == null : source.rglr_tm_qty() == null))) {
                    warnings.add(source.display_name() + ": 근태 미입력");
                    continue;
                }
                BigDecimal quantity = switch (unit) {
                    case "DAY" -> source.work_day_qty();
                    case "HOUR" -> source.rglr_tm_qty();
                    default -> source.mm_qty();
                };
                if (quantity == null) {
                    warnings.add(source.display_name() + ": 월 MM 미입력");
                    continue;
                }
                long amount = free ? 0 : BillingRules.sourceAmount(quantity, source.price());
                BigDecimal mm = "MONTH".equals(unit) ? source.mm_qty()
                    : source.work_day_qty() == null ? null
                    : BillingRules.attendanceMm(source.work_day_qty(), source.mth_work_day_qty());
                if (mm == null) warnings.add(source.display_name() + ": 근무일수 없음, 산출 MM 없음");
                details.add(new DetailLine(source.asgn_id(), source.dev_id(), source.cntrct_dev_id(),
                    source.display_name(), month, mm, source.mm_qty(), source.price(), source.work_day_qty(),
                    source.rglr_tm_qty(), source.ovtm_tm_qty(), 0, amount, null));
                supply = BillingRules.add(supply, amount, "sply_amt");
            }
        }
        long vat = BillingRules.vat(supply, mapper.vatRate(month));
        BillingRules.total(supply, vat);
        return new Preview(projectId, stageId, month, supply, vat, due, details, warnings, missing);
    }
}
