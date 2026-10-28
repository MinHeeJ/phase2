package com.sicost.billingsops;

import com.sicost.common.validation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
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
    public String month(String requested) {
        if (requested != null) return BillingValues.month(requested, "month").toString();
        return mapper.closedMonths().stream().max(String::compareTo)
            .orElseGet(() -> YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString());
    }
    public static String judgment(long basis, long billed, long count, boolean freeOnly, boolean missing) {
        if (missing) return "근태 미입력";
        if (freeOnly && billed == 0) return "맞음(무상)";
        if (count == 0 && basis > 0) return "청구 없음";
        BigDecimal difference = BigDecimal.valueOf(basis).subtract(BigDecimal.valueOf(billed));
        if (difference.abs().compareTo(tolerance(basis)) > 0) return difference.signum() > 0 ? "적게 청구" : "많이 청구";
        return "맞음";
    }
    public static BigDecimal tolerance(long basis) {
        return BigDecimal.valueOf(basis).abs().multiply(new BigDecimal("0.005")).max(BigDecimal.valueOf(10000));
    }
    public List<GapRow> all(GapRequest request) {
        String month = month(request.month());
        if (request.judgment() != null && !Set.of("맞음", "맞음(무상)", "청구 없음", "적게 청구", "많이 청구", "근태 미입력").contains(request.judgment())) {
            throw BillingValues.invalid("judgment");
        }
        var bills = mapper.bills();
        var rows = new ArrayList<GapRow>();
        for (Project project : mapper.gapProjects(month)) {
            var source = mapper.sources(new SourceKey(project.project_id(), month));
            Preview preview = amounts.preview(new PreviewRequest(project.project_id(), month, null));
            var confirmed = bills.stream().filter(b -> b.prj_id() == project.project_id()
                && "CONFIRM".equals(b.bill_stts_cd()) && month.equals(b.atrb_ym())).toList();
            long billed = BillingAmountService.sum(confirmed.stream().map(Bill::sply_amt).toList());
            long basis = preview.sply_amt();
            boolean missing = source.stream().anyMatch(BillingAmountService::attendanceMissing);
            boolean freeOnly = !source.isEmpty() && source.stream().allMatch(s -> "FREE".equals(s.kind()));
            if (!missing && !freeOnly && basis == 0 && billed == 0 && preview.missing_price_assignments().isEmpty()) continue;
            long diff = BillingAmounts.exact(BigDecimal.valueOf(basis).subtract(BigDecimal.valueOf(billed)), "difference_amt");
            String judgment = judgment(basis, billed, confirmed.size(), freeOnly, missing);
            if (!preview.missing_price_assignments().isEmpty()) judgment = "단가 미입력";
            var suspects = source.stream().filter(s -> "NEW".equals(s.kind())
                && YearMonth.from(s.bgn_dt()).toString().equals(month) && s.price() != null && s.mm_qty() != null
                && Math.abs(diff) == BillingAmountService.calculate(s.mm_qty(), s.price(), 0))
                .map(s -> new SuspectedPerson(s.display_name(), s.management_number())).toList();
            if (request.judgment() != null && !request.judgment().equals(judgment)) continue;
            if (!BillingQuery.search(request.q(), project.project_name())) continue;
            String explanation = String.join(" · ", preview.warnings());
            if (!suspects.isEmpty()) explanation += " 신규 투입 누락 가능";
            rows.add(new GapRow(project.project_id(), project.project_name(), month, basis, billed, diff,
                tolerance(basis), judgment, explanation, missing, suspects, confirmed.stream().map(Bill::bill_id).toList()));
        }
        Map<String, Function<GapRow, ? extends Comparable<?>>> fields = Map.of(
            "project_name", GapRow::project_name, "basis_supply_amt", GapRow::basis_supply_amt,
            "confirmed_supply_amt", GapRow::confirmed_supply_amt, "difference_amt", GapRow::difference_amt,
            "judgment", GapRow::judgment, "month", GapRow::month);
        return BillingQuery.sort(rows, paging(request).sort(), fields, GapRow::project_id);
    }
    public GapResult list(GapRequest request) {
        var rows = all(request);
        var paging = paging(request);
        return new GapResult(month(request.month()), BillingAmountService.sum(rows.stream().map(GapRow::difference_amt).toList()),
            BillingQuery.page(rows, paging), rows.size(), paging.page(), paging.size());
    }
    private com.sicost.common.api.DeveloperPaging paging(GapRequest request) {
        return BillingValues.paging(request.page(), request.size(), request.sort(), BillingQuery.sorts(
            "project_name", "basis_supply_amt", "confirmed_supply_amt", "difference_amt", "judgment", "month"), "project_name,asc");
    }
}
