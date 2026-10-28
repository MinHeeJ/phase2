package com.sicost.billings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billings.BillingModels.*;

@Service
@Transactional(readOnly = true)
public class BillingService {
    private final BillingMapper mapper;
    private final BillingAmountService amounts;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ObjectMapper json;
    public BillingService(BillingMapper mapper, BillingAmountService amounts, CurrentUser user,
            PermissionService permissions, Clock clock, ObjectMapper json) {
        this.mapper = mapper;
        this.amounts = amounts;
        this.user = user;
        this.permissions = permissions;
        this.clock = clock;
        this.json = json;
    }
    private YearMonth now() { return YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    private void can(String operation) { BillingActions.can(permissions, operation); }
    public BillingPage list(ListInput input) {
        can("listBillings");
        int year = input.year() == null ? now().getYear() : input.year();
        if (year < 1 || year > 9999) throw BillingValues.invalid("year");
        if (input.status() != null && !input.status().isBlank()) BillingRules.status(input.status());
        if (input.type() != null && !input.type().isBlank()) BillingRules.requireBillable(input.type());
        if (input.customerId() != null) BillingInput.id(input.customerId(), "customerId");
        if (input.q() != null && input.q().length() > 200) throw BillingValues.invalid("q");
        var allowed = new HashSet<String>();
        for (String field : List.of("bill_no", "bill_dt", "atrb_ym", "project_name", "sply_amt",
                "vat_amt", "tot_amt", "bill_stts_cd", "rcpt_plan_dt", "receivable_amt")) {
            allowed.add(field + ",asc");
            allowed.add(field + ",desc");
        }
        var paging = BillingValues.paging(input.page(), input.size(), input.sort(), allowed, "bill_no,desc");
        String[] order = paging.sort().split(",");
        var filter = new BillingFilter(year, blank(input.status()), blank(input.type()), input.customerId(),
            blank(input.q()), paging.toPageRequest().offset(), paging.size(), order[0], order[1]);
        return new BillingPage(mapper.list(filter), mapper.count(filter), paging.page(), paging.size());
    }
    private String blank(String value) { return value == null || value.isBlank() ? null : value; }
    private StoredBill require(long id, boolean lock) {
        BillingInput.id(id, "id");
        StoredBill stored = lock ? mapper.lockBill(id) : mapper.stored(id);
        if (stored == null) throw ApiException.notFound("청구가 없습니다.");
        return stored;
    }
    public BillingDetail detail(long id) {
        can("getBilling");
        var stored = require(id, false);
        var row = mapper.row(id);
        var lines = mapper.details(id);
        var warnings = new ArrayList<String>();
        long sum = lines.stream().mapToLong(DetailLine::bill_amt).reduce(0, Math::addExact);
        if (sum != row.sply_amt()) warnings.add("상세 합과 공급가액이 다릅니다. 확인 후 확정할 수 있습니다.");
        if (row.txinv_no() == null || row.txinv_no().isBlank()) warnings.add("세금계산서 번호가 없습니다. 실제 발행은 별도 업무입니다.");
        return new BillingDetail(row, lines, mapper.receipts(id), mapper.amendments(id),
            stored.orgnl_bill_id(), mapper.history(id), List.copyOf(warnings));
    }
    private Project project(long id) {
        Project project = mapper.lockProject(id);
        if (project == null) throw ApiException.notFound("프로젝트가 없습니다.");
        BillingRules.requireBillable(project.cntrct_ty_cd());
        if (!"Y".equals(project.use_yn())) throw BillingValues.invalid("project_id");
        return project;
    }
    private Stage stage(Project project, Long id) {
        if (!"TK".equals(project.cntrct_ty_cd())) {
            if (id != null) throw BillingValues.invalid("stage_id");
            return null;
        }
        if (id == null) throw BillingValues.invalid("stage_id");
        var stage = mapper.stage(new StageKey(project.prj_id(), id));
        if (stage == null) throw BillingValues.invalid("stage_id");
        return stage;
    }
    private String month(Project project, Stage stage, String month, LocalDate billDate,
            LocalDateTime created) {
        if (!"TK".equals(project.cntrct_ty_cd())) return BillingValues.month(month, "month").toString();
        LocalDate date = billDate != null ? billDate : stage == null ? null : stage.bill_plan_dt();
        return date != null ? YearMonth.from(date).toString()
            : created == null ? now().toString() : YearMonth.from(created).toString();
    }
    private void open(String month) {
        YearMonth target = BillingValues.month(month, "month");
        if ("CLOSED".equals(mapper.monthStatus(month))) {
            throw ApiException.conflict("CLOSED_MONTH", "마감된 달은 변경할 수 없습니다.", "month");
        }
        if (BillingRules.pastYear(target.getYear(), clock)) throw BillingValues.invalid("month");
    }
    private BillingMoney money(Long supply, Long vat, long fallback, String month, boolean amendment) {
        long selected = supply == null ? fallback : supply;
        long tax = vat == null ? BillingAmounts.suggestedVat(selected, mapper.vatRate(month)) : vat;
        if (amendment ? selected >= 0 : selected < 0) throw BillingValues.invalid("sply_amt");
        if (amendment ? tax > 0 : tax < 0) throw BillingValues.invalid("vat_amt");
        new BillingAmounts.Amounts(selected, tax).total();
        return new BillingMoney(selected, tax);
    }
    @Transactional
    public BillingDetail create(BillingCreate input) {
        can("createBilling");
        if (input.project_id() == null) throw BillingValues.invalid("project_id");
        BillingInput.id(input.project_id(), "project_id");
        String prefix = "B-" + now().format(DateTimeFormatter.ofPattern("yyMM")) + "-";
        mapper.lockNumber(prefix);
        Project project = project(input.project_id());
        Stage stage = stage(project, input.stage_id());
        if (stage != null && input.month() != null) throw BillingValues.invalid("month");
        String period = month(project, stage, input.month(), input.bill_dt(), null);
        open(period);
        int ordinal;
        if (stage != null) {
            if (mapper.existingStage(new StageKey(project.prj_id(), stage.bill_stg_id())) > 0) {
                throw ApiException.conflict("ALREADY_BILLED", "이미 청구한 단계입니다. 기존 청구를 열어 주세요.", "stage_id");
            }
            ordinal = stage.stg_odr_no();
        } else {
            int existing = mapper.maxOrdinal(new SourceKey(project.prj_id(), period));
            if (existing > 0 && !Boolean.TRUE.equals(input.additional())) {
                throw ApiException.conflict("ALREADY_BILLED", "기존 청구를 열어 주세요. 추가 청구는 명시해야 합니다.", "additional");
            }
            ordinal = input.bill_odr_no() == null ? 1 : input.bill_odr_no();
            if (ordinal < 1 || (Boolean.TRUE.equals(input.additional())
                    && (input.bill_odr_no() == null || ordinal <= existing))
                    || (existing == 0 && !Boolean.TRUE.equals(input.additional()) && ordinal != 1)) {
                throw BillingValues.invalid("bill_odr_no");
            }
        }
        var calculated = amounts.calculate(project, stage, period,
            input.details() == null ? List.of() : input.details());
        var money = money(input.sply_amt(), input.vat_amt(), calculated.supply(), period, false);
        String number = prefix + String.format(Locale.ROOT, "%02d", mapper.nextSerial(prefix));
        var fields = new BillingUpdate(input.bill_dt(), money.supply(), money.vat(), input.rcpt_plan_dt(),
            input.txinv_no(), input.txinv_dt(), null, false, input.details());
        var identity = new BillingIdentity(project.prj_id(), input.stage_id(), ordinal,
            "TK".equals(project.cntrct_ty_cd()) && input.bill_dt() == null ? null : period);
        long id = mapper.insert(new BillWrite(null, number, identity, fields, money, user.userId()));
        replace(id, calculated.details());
        audit(id, "INSERT", "bill", null, mapper.stored(id));
        var detail = detail(id);
        var warnings = new ArrayList<>(detail.warnings());
        warnings.addAll(calculated.warnings());
        return new BillingDetail(detail.billing(), detail.details(), detail.receipts(), detail.amendments(),
            detail.original_bill_id(), detail.change_history(), List.copyOf(warnings));
    }
    @Transactional
    public BillingDetail update(long id, BillingUpdate input, Set<String> supplied) {
        can("updateBilling");
        var found = require(id, false);
        Project project = project(found.prj_id());
        if (found.orgnl_bill_id() != null) require(found.orgnl_bill_id(), true);
        var before = require(id, true);
        BillingRules.requireDraft(before.bill_stts_cd());
        Stage stage = before.bill_stg_id() == null ? null
            : mapper.stage(new StageKey(project.prj_id(), before.bill_stg_id()));
        String oldMonth = month(project, stage, before.atrb_ym(), before.bill_dt(), before.created_at());
        LocalDate billDate = supplied.contains("bill_dt") ? input.bill_dt() : before.bill_dt();
        String period = month(project, stage, before.atrb_ym(), billDate, before.created_at());
        open(oldMonth);
        open(period);
        boolean recalculate = Boolean.TRUE.equals(input.recalculate());
        if (!recalculate && input.details() != null && !input.details().isEmpty()) {
            throw BillingValues.invalid("recalculate");
        }
        Calculation calculation = recalculate ? amounts.calculate(project, stage, period,
            input.details() == null ? List.of() : input.details()) : null;
        var selected = money(input.sply_amt(), input.vat_amt() == null ? before.vat_amt() : input.vat_amt(),
            recalculate ? calculation.supply() : before.sply_amt(), period, "Y".equals(before.amnd_yn()));
        var fields = new BillingUpdate(billDate, selected.supply(), selected.vat(),
            supplied.contains("rcpt_plan_dt") ? input.rcpt_plan_dt() : before.rcpt_plan_dt(),
            supplied.contains("txinv_no") ? input.txinv_no() : before.txinv_no(),
            supplied.contains("txinv_dt") ? input.txinv_dt() : before.txinv_dt(),
            supplied.contains("rmrk_desc") ? input.rmrk_desc() : before.rmrk_desc(), recalculate, input.details());
        String attribution = "TK".equals(project.cntrct_ty_cd())
            ? billDate == null ? null : period : before.atrb_ym();
        var identity = new BillingIdentity(project.prj_id(), before.bill_stg_id(), before.bill_odr_no(), attribution);
        BillingRules.requireChanged(mapper.update(new BillWrite(id, null, identity, fields, selected, user.userId())));
        var previousLines = mapper.details(id);
        if (recalculate) replace(id, calculation.details());
        else if ("TK".equals(project.cntrct_ty_cd()) && !period.equals(oldMonth)) {
            mapper.moveDetails(new DetailMonth(id, period));
        }
        auditChanges(id, before, mapper.stored(id));
        if (recalculate || !period.equals(oldMonth)) {
            int position = 0;
            for (var line : previousLines) audit(id, "UPDATE", "detail_before_" + position++, line, null);
            position = 0;
            for (var line : mapper.details(id)) audit(id, "UPDATE", "detail_after_" + position++, null, line);
        }
        return detail(id);
    }
    private void replace(long id, List<DetailLine> lines) {
        mapper.deleteDetails(id);
        for (var line : lines) mapper.insertDetail(new DetailWrite(id, line));
    }
    private String snapshot(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("청구 변경 기록을 저장할 수 없습니다.", ex); }
    }
    private void audit(long id, String type, String column, Object before, Object after) {
        mapper.audit(new Audit(Long.toString(id), type, column, snapshot(before), snapshot(after), user.userId()));
    }
    private void auditChanges(long id, StoredBill before, StoredBill after) {
        var old = json.valueToTree(before);
        var next = json.valueToTree(after);
        next.fieldNames().forEachRemaining(field -> {
            if (!Objects.equals(old.get(field), next.get(field))) {
                audit(id, "UPDATE", field, old.get(field), next.get(field));
            }
        });
    }
}
