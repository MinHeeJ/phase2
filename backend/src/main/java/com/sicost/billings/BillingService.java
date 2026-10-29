package com.sicost.billings;

import com.sicost.common.api.ApiException;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.Audit;
import com.sicost.common.security.*;
import com.sicost.common.validation.ProjectValues;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billings.BillingModels.*;

@Service
@Transactional(readOnly = true)
public class BillingService {
    private final BillingMapper mapper;
    private final BillingMutationService mutations;
    private final BillingAmountService amounts;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;

    public BillingService(BillingMapper mapper, BillingMutationService mutations, BillingAmountService amounts,
            CurrentUser user, PermissionService permissions, Clock clock) {
        this.mapper = mapper;
        this.mutations = mutations;
        this.amounts = amounts;
        this.user = user;
        this.permissions = permissions;
        this.clock = clock;
    }
    public BillingPage list(Map<String, String> filters) {
        BillingActions.can(permissions, "listBillings");
        Query query = BillingInput.query(filters, ProjectValues.today(clock));
        return new BillingPage(mapper.list(query), mapper.count(query), query.page(), query.size());
    }
    public BillingDetail get(long id) {
        BillingActions.can(permissions, "getBilling");
        if (id < 1) throw BillingRules.invalid("id");
        BillingRow row = mapper.row(id);
        if (row == null) throw ApiException.notFound("청구가 없습니다.");
        Header header = mapper.header(id);
        List<DetailLine> details = mapper.details(id);
        List<String> warnings = new ArrayList<>();
        long sum = details.stream().mapToLong(DetailLine::bill_amt)
            .reduce(0, (a, b) -> BillingRules.add(a, b, "details"));
        if ("TK".equals(row.contract_type())) {
            if (header.bill_stg_id() != null) {
                Stage stage = mapper.stage(header.bill_stg_id());
                if (stage != null) sum = stage.sply_amt();
            }
            if (row.bill_dt() == null) warnings.add("청구일 미입력: 확정 전에 청구일을 입력하세요.");
        }
        if (header.orgnl_bill_id() == null && sum != row.sply_amt()) warnings.add("공급가액과 원천 상세 합계가 다릅니다. 확인 후 확정할 수 있습니다.");
        if (row.txinv_no() == null || row.txinv_no().isBlank()) warnings.add("세금계산서 번호가 입력되지 않았습니다. 실제 발행은 별도로 진행하세요.");
        return new BillingDetail(row, details, mapper.receipts(id), mapper.amendments(id),
            header.orgnl_bill_id(), mapper.history(id), warnings);
    }
    private Project project(long id) {
        Project project = mapper.project(id);
        if (project == null) throw BillingRules.invalid("project_id");
        BillingRules.billable(project.contract_type());
        if (!"Y".equals(project.use_yn())) throw BillingRules.invalid("project_id");
        return project;
    }
    @Transactional
    public BillingDetail create(Input input) {
        BillingActions.can(permissions, "createBilling");
        long projectId = input.number("project_id");
        mutations.lockProject(projectId);
        Project project = project(projectId);
        boolean tk = "TK".equals(project.contract_type());
        Stage stage = null;
        String month = null;
        int order = 1;
        if (tk) {
            Long stageId = input.number("stage_id");
            if (stageId == null || (stage = mapper.stage(stageId)) == null || stage.prj_id() != projectId) throw BillingRules.invalid("stage_id");
            if (input.text("month") != null) throw BillingRules.invalid("month");
            order = stage.stg_odr_no();
        } else {
            month = input.text("month");
            BillingRules.month(month, "month");
            if (input.has("stage_id")) throw BillingRules.invalid("stage_id");
            if (input.number("bill_odr_no") != null) order = input.number("bill_odr_no").intValue();
        }
        var duplicate = mapper.duplicates(new Duplicate(projectId, tk ? stage.bill_stg_id() : null, month));
        if (!duplicate.isEmpty()) {
            int maximum = duplicate.stream().mapToInt(Header::bill_odr_no).max().orElse(0);
            if (tk || !Boolean.TRUE.equals(input.value("additional")) || !input.has("bill_odr_no") || order <= maximum) {
                throw ApiException.conflict("ALREADY_BILLED", "기존 청구 #" + duplicate.get(0).bill_id()
                    + "를 열어 확인하세요. 추가 청구는 추가 선택과 증가 차수가 필요합니다.", tk ? "stage_id" : "month");
            }
        }
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        YearMonth effective = BillingRules.effectiveMonth(project.contract_type(), month, input.date("bill_dt"),
            stage == null ? null : stage.bill_plan_dt(), now);
        mutations.requireOpen(effective, "month");
        Calculation calculation = tk ? new Calculation(new ArrayList<>(), stage.sply_amt(), new ArrayList<>())
            : calculate(project, month, input.details());
        long supply = input.has("sply_amt") ? input.number("sply_amt") : calculation.supply();
        long vat = input.has("vat_amt") ? input.number("vat_amt") : amounts.suggestedVat(supply, effective.toString());
        amounts.total(supply, vat, false);
        Header header = new Header(0, projectId, tk ? stage.bill_stg_id() : null, order, month,
            input.date("bill_dt"), supply, vat, input.text("txinv_no"), input.date("txinv_dt"),
            input.date("rcpt_plan_dt"), null, "N", null);
        String number = mutations.nextNumber();
        Long id = mapper.insert(new Write(number, header, user.userId()));
        for (DetailLine line : calculation.details()) mapper.insertDetail(new DetailWrite(id, line));
        audit(id, "INSERT", "bill_no", null, number);
        auditHeader(id, null, header);
        auditDetails(id, new ArrayList<>(), calculation.details());
        BillingDetail result = get(id);
        return warnings(result, calculation.warnings());
    }
    @Transactional
    public BillingDetail update(long id, Input input) {
        BillingActions.can(permissions, "updateBilling");
        var state = mutations.lock(id);
        BillingRules.requireDraft(state.bill_stts_cd());
        for (String identity : List.of("project_id", "stage_id", "month")) {
            if (input.has(identity)) throw BillingRules.invalid(identity);
        }
        mutations.requireOpen(state);
        Header before = mapper.header(id);
        LocalDate date = input.has("bill_dt") ? input.date("bill_dt") : before.bill_dt();
        YearMonth effective = BillingRules.effectiveMonth(state.contract_type(), state.atrb_ym(), date,
            state.bill_plan_dt(), state.created_at());
        mutations.requireOpen(effective, "bill_dt");
        List<DetailLine> oldDetails = null;
        Calculation calculation = null;
        if (input.recalculate()) {
            if (before.orgnl_bill_id() != null) throw BillingRules.invalid("recalculate");
            Project project = project(before.prj_id());
            oldDetails = mapper.details(id);
            if ("TK".equals(project.contract_type())) {
                Stage stage = mapper.stage(before.bill_stg_id());
                calculation = new Calculation(new ArrayList<>(), stage.sply_amt(), new ArrayList<>());
            } else calculation = calculate(project, before.atrb_ym(), input.details());
        }
        long supply = input.has("sply_amt") ? input.number("sply_amt")
            : calculation == null ? before.sply_amt() : calculation.supply();
        long vat = input.has("vat_amt") ? input.number("vat_amt") : before.vat_amt();
        amounts.total(supply, vat, "Y".equals(before.amnd_yn()));
        Header after = new Header(id, before.prj_id(), before.bill_stg_id(), before.bill_odr_no(), before.atrb_ym(),
            date, supply, vat, input.has("txinv_no") ? input.text("txinv_no") : before.txinv_no(),
            input.has("txinv_dt") ? input.date("txinv_dt") : before.txinv_dt(),
            input.has("rcpt_plan_dt") ? input.date("rcpt_plan_dt") : before.rcpt_plan_dt(),
            input.has("rmrk_desc") ? input.text("rmrk_desc") : before.rmrk_desc(), before.amnd_yn(), before.orgnl_bill_id());
        BillingRules.requireAffected(mapper.update(new Write(null, after, user.userId())));
        if (calculation != null) {
            mapper.deleteDetails(id);
            for (DetailLine line : calculation.details()) mapper.insertDetail(new DetailWrite(id, line));
            auditDetails(id, oldDetails, calculation.details());
        }
        auditHeader(id, before, after);
        BillingDetail result = get(id);
        return calculation == null ? result : warnings(result, calculation.warnings());
    }
    private BillingDetail warnings(BillingDetail result, List<String> additional) {
        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.addAll(additional);
        return new BillingDetail(result.billing(), result.details(), result.receipts(), result.amendments(),
            result.original_bill_id(), result.change_history(), warnings);
    }
    private Calculation calculate(Project project, String month, List<Overtime> overrides) {
        YearMonth ym = BillingRules.month(month, "month");
        List<Source> sources = mapper.sources(new SourceKey(project.prj_id(), month, ym.atDay(1), ym.atEndOfMonth()));
        Map<Long, Long> overtime = new HashMap<>();
        for (Overtime line : overrides) overtime.put(line.asgn_id(), line.ovtm_amt());
        Set<Long> sourceIds = new HashSet<>();
        List<DetailLine> details = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long sum = 0;
        String mode = "DP".equals(project.contract_type()) ? project.stlm_mthd_cd() : "MONTH";
        if (mode == null) mode = "MONTH";
        for (Source row : sources) {
            sourceIds.add(row.asgn_id());
            boolean free = "FREE".equals(row.asgn_se_cd());
            if (!free && row.untprc_amt() == null) {
                throw new ApiException(400, "VALIDATION_ERROR", row.display_name() + "의 적용 청구 단가가 없습니다.", "details");
            }
            long price = free ? 0 : row.untprc_amt();
            boolean monthly = mode.equals("MONTH");
            if (monthly ? row.mm_qty() == null : row.atnd_id() == null
                    || mode.equals("DAY") && row.work_day_qty() == null
                    || mode.equals("HOUR") && row.rglr_tm_qty() == null) {
                warnings.add(row.display_name() + ": " + (monthly ? "월 MM" : "근태") + " 미입력으로 상세에서 제외했습니다.");
                continue;
            }
            BigDecimal mm = monthly ? row.mm_qty() : row.work_day_qty() == null ? null
                : amounts.attendanceMm(row.work_day_qty(), row.mth_work_day_qty());
            if (mm != null) BillingRules.quantity(mm, 5, 2, "mm_qty");
            if (row.work_day_qty() != null) BillingRules.quantity(row.work_day_qty(), 5, 1, "work_day_qty");
            if (row.rglr_tm_qty() != null) BillingRules.quantity(row.rglr_tm_qty(), 6, 1, "work_tm_qty");
            if (row.ovtm_tm_qty() != null) BillingRules.quantity(row.ovtm_tm_qty(), 6, 1, "ovtm_tm_qty");
            long extra = overtime.getOrDefault(row.asgn_id(), 0L);
            long amount = mode.equals("HOUR") ? amounts.hourlyAmount(price, row.rglr_tm_qty(), extra, free)
                : amounts.lineAmount(price, monthly ? mm : row.work_day_qty(), free);
            if (!mode.equals("HOUR") && extra != 0) throw BillingRules.invalid("ovtm_amt");
            if (!monthly && mm == null) warnings.add(row.display_name() + ": 근무일수 미입력으로 산출 MM이 없습니다.");
            details.add(new DetailLine(row.asgn_id(), row.dev_id(), row.cntrct_dev_id(), row.display_name(), month,
                mm, row.mm_qty(), price, monthly ? null : row.work_day_qty(), monthly ? null : row.rglr_tm_qty(),
                monthly ? null : row.ovtm_tm_qty(), free ? 0 : extra, amount, null));
            sum = BillingRules.add(sum, amount, "sply_amt");
        }
        if (!sourceIds.containsAll(overtime.keySet())) throw BillingRules.invalid("asgn_id");
        return new Calculation(details, sum, warnings);
    }
    private void audit(long id, String type, String field, Object before, Object after) {
        if (Objects.equals(before, after)) return;
        mutations.audit(new Audit("bill.bill", Long.toString(id), type, field,
            before == null ? null : before.toString(), after == null ? null : after.toString(), null, user.userId()));
    }
    private void auditHeader(long id, Header before, Header after) {
        for (var component : Header.class.getRecordComponents()) {
            if (component.getName().equals("bill_id")) continue;
            try {
                audit(id, before == null ? "INSERT" : "UPDATE", component.getName(),
                    before == null ? null : component.getAccessor().invoke(before), component.getAccessor().invoke(after));
            } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        }
    }
    private void auditDetails(long id, List<DetailLine> before, List<DetailLine> after) {
        Map<Long, DetailLine> old = new LinkedHashMap<>(), next = new LinkedHashMap<>();
        before.forEach(line -> old.put(line.asgn_id(), line));
        after.forEach(line -> next.put(line.asgn_id(), line));
        Set<Long> keys = new LinkedHashSet<>(old.keySet()); keys.addAll(next.keySet());
        for (Long key : keys) {
            DetailLine b = old.get(key), a = next.get(key);
            if (!Objects.equals(b, a)) mutations.audit(new Audit("bill.bill_dtl", Long.toString(id),
                b == null ? "INSERT" : a == null ? "DELETE" : "UPDATE", "asgn_id=" + key,
                b == null ? null : b.toString(), a == null ? null : a.toString(), null, user.userId()));
        }
    }
}
