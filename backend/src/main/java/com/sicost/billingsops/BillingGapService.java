package com.sicost.billingsops;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.validation.BillingRules;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service
@Transactional(readOnly = true)
public class BillingGapService {
    private final BillingMapper mapper;
    private final BillingAmountService amounts;
    private final Clock clock;
    public BillingGapService(BillingMapper mapper, BillingAmountService amounts, Clock clock) {
        this.mapper = mapper;
        this.amounts = amounts;
        this.clock = clock;
    }
    public GapResult gaps(GapFilter filter, BillingPaging paging) {
        String month = filter.month();
        if (month == null) {
            month = mapper.lastClosed();
            if (month == null) month = YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString();
        }
        BillingRules.month(month, "month");
        if (filter.judgment() != null && !Set.of("맞음", "맞음(무상)", "적게 청구", "많이 청구", "청구 없음", "근태 미입력", "단가 없음").contains(filter.judgment())) {
            throw BillingRules.invalid("judgment");
        }
        List<GapRow> result = new ArrayList<>();
        for (Project project : mapper.gapProjects(month)) {
            Preview preview = amounts.preview(project.prj_id(), month, null);
            var source = mapper.sources(BillingAmountService.sourceKey(project.prj_id(), month));
            final String selected = month;
            var bills = mapper.bills().stream().filter(b -> b.prj_id() == project.prj_id()
                && selected.equals(b.atrb_ym()) && "CONFIRM".equals(b.bill_stts_cd())).toList();
            long billed = bills.stream().mapToLong(Bill::sply_amt)
                .reduce(0, (a, b) -> BillingRules.add(a, b, "confirmed_supply_amt"));
            boolean attendance = preview.warnings().stream().anyMatch(w -> w.contains("근태 미입력"));
            boolean freeOnly = !source.isEmpty() && source.stream().allMatch(s -> "FREE".equals(s.kind()));
            long basis = preview.sply_amt();
            if (!attendance && preview.missing_price_assignments().isEmpty() && basis == 0 && bills.isEmpty() && !freeOnly) continue;
            long difference = BillingRules.add(basis, -billed, "difference_amt");
            BigDecimal tolerance = BigDecimal.valueOf(basis).abs().multiply(new BigDecimal("0.005")).max(BigDecimal.valueOf(10000));
            String judgment = attendance ? "근태 미입력" : !preview.missing_price_assignments().isEmpty() ? "단가 없음"
                : basis == 0 && billed == 0 && freeOnly ? "맞음(무상)"
                : basis > 0 && bills.isEmpty() ? "청구 없음"
                : BigDecimal.valueOf(difference).abs().compareTo(tolerance) <= 0 ? "맞음"
                : difference > 0 ? "적게 청구" : "많이 청구";
            var suspected = source.stream().filter(s -> !"FREE".equals(s.kind()) && s.price() != null
                && s.mm_qty() != null && s.bgn_dt().toString().startsWith(selected)
                && BillingRules.sourceAmount(s.mm_qty(), s.price()) == difference)
                .map(s -> new Person(s.display_name(), s.management_number())).toList();
            String explanation = String.join(" · ", preview.warnings());
            if (!suspected.isEmpty()) explanation += " 신규 투입 누락 가능";
            var row = new GapRow(project.prj_id(), project.project_name(), month, basis, billed, difference,
                tolerance, judgment, explanation, attendance, suspected, bills.stream().map(Bill::bill_id).toList());
            if (filter.judgment() != null && !filter.judgment().equals(judgment)) continue;
            if (filter.q() != null && !project.project_name().toLowerCase(Locale.ROOT).contains(filter.q().toLowerCase(Locale.ROOT))) continue;
            result.add(row);
        }
        result.sort(BillingReceivableService.order(paging, r -> r, GapRow::project_id));
        long difference = result.stream().mapToLong(GapRow::difference_amt)
            .reduce(0, (a, b) -> BillingRules.add(a, b, "total_difference_amt"));
        return new GapResult(month, difference, BillingReceivableService.page(result, paging), result.size(), paging.page(), paging.size());
    }
}
