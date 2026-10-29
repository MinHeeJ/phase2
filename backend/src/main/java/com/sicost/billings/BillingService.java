package com.sicost.billings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
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
    private final ObjectMapper json;
    private final Clock clock;
    public BillingService(BillingMapper mapper, BillingAmountService amounts, CurrentUser user,
            PermissionService permissions, ObjectMapper json, Clock clock) {
        this.mapper = mapper;
        this.amounts = amounts;
        this.user = user;
        this.permissions = permissions;
        this.json = json;
        this.clock = clock;
    }
    public BillingPage list(BillingQuery query) {
        permissions.can(BillingActions.READ);
        return new BillingPage(mapper.list(query).stream().map(this::reminder).toList(),
            mapper.count(query), query.page(), query.size());
    }
    public BillingDetail get(long id) {
        permissions.can(BillingActions.READ);
        return detail(require(id), new ArrayList<>());
    }
    private State require(long id) {
        var state = mapper.state(BillingInput.id(id));
        if (state == null) throw ApiException.notFound("청구가 없습니다.");
        return state;
    }
    private BillingDetail detail(State state, List<String> warnings) {
        var row = reminder(mapper.row(state.bill_id()));
        var lines = mapper.details(state.bill_id());
        if (!"TK".equals(state.cntrct_ty_cd()) && !"Y".equals(state.amnd_yn())) {
            long sum = 0;
            for (var line : lines) sum = BillingValues.add(sum, line.bill_amt(), "sply_amt");
            if (sum != state.sply_amt()) warnings.add("공급가액과 원천 상세 합계가 다릅니다. 확인 후 확정할 수 있습니다.");
        }
        if (state.txinv_no() == null || state.txinv_no().isBlank()) warnings.add("세금계산서 번호가 없습니다.");
        return new BillingDetail(row, lines, mapper.receipts(state.bill_id()), mapper.amendments(state.bill_id()),
            state.orgnl_bill_id(), mapper.history(state.bill_id()), List.copyOf(warnings));
    }
    private Project project(long id) {
        var project = mapper.lockProject(id);
        if (project == null) throw BillingValues.invalid("project_id");
        BillingRules.requireBillable(project.cntrct_ty_cd());
        if (!"Y".equals(project.use_yn())) throw BillingValues.invalid("project_id");
        return project;
    }
    private YearMonth month(State state) {
        LocalDate fallback = state.bill_plan_dt();
        if ("Y".equals(state.amnd_yn()) && state.bill_dt() == null && state.orgnl_bill_id() != null) {
            var original = require(state.orgnl_bill_id());
            fallback = original.bill_dt() == null ? original.bill_plan_dt() : original.bill_dt();
        }
        return new BillingRules.Period(state.cntrct_ty_cd(), state.atrb_ym() == null ? null
            : BillingValues.month(state.atrb_ym(), "month"), state.bill_dt(), fallback).billingMonth();
    }
    private void open(State state) {
        var month = month(state);
        BillingRules.requireOpen(month, mapper.monthStatus(month.toString()), "month");
    }
    private static void money(long supply, long vat, boolean amendment) {
        if (amendment ? supply >= 0 : supply < 0) throw BillingValues.invalid("sply_amt");
        if (amendment ? vat > 0 : vat < 0) throw BillingValues.invalid("vat_amt");
        BillingValues.add(supply, vat, "tot_amt");
    }
    @Transactional
    public BillingDetail create(Input input) {
        permissions.can(BillingActions.CREATE);
        YearMonth creationMonth = YearMonth.from(ProjectValues.today(clock));
        String prefix = BillingNumbers.prefix(creationMonth);
        mapper.numberLock(prefix);
        var project = project(input.project_id());
        boolean tk = "TK".equals(project.cntrct_ty_cd());
        Stage stage = null;
        if (tk) {
            if (input.stage_id() == null || input.month() != null || input.additional()) throw BillingValues.invalid("stage_id");
            stage = mapper.stage(new StageKey(project.prj_id(), input.stage_id()));
            if (stage == null) throw BillingValues.invalid("stage_id");
        } else {
            if (input.month() == null || input.stage_id() != null) throw BillingValues.invalid("month");
        }
        var scope = new DuplicateScope(project.prj_id(), tk ? stage.bill_stg_id() : null, tk ? null : input.month());
        Long existing = mapper.existing(scope);
        int maximum = mapper.maximumOrdinal(scope);
        if (existing != null && (tk || !input.additional())) {
            throw ApiException.conflict("ALREADY_BILLED", "기존 청구를 열어 주세요. 청구 ID: " + existing, tk ? "stage_id" : "month");
        }
        int ordinal = tk ? stage.stg_odr_no() : input.bill_odr_no() == null ? maximum + 1 : input.bill_odr_no();
        if (!tk && (ordinal <= maximum
                || (input.additional() && input.bill_odr_no() == null))) throw BillingValues.invalid("bill_odr_no");
        State initial = new State(0, project.prj_id(), tk ? stage.bill_stg_id() : null, ordinal,
            tk ? null : input.month(), input.bill_dt(), 0, 0, "DRAFT", "N", null,
            input.rcpt_plan_dt(), input.txinv_no(), input.txinv_dt(), null,
            tk ? stage.bill_plan_dt() : null, project.cntrct_ty_cd());
        open(initial);
        Amount source = tk ? new Amount(stage.sply_amt(), new ArrayList<>(), new ArrayList<>())
            : amounts.calculate(project, input.month(), input.details());
        if (tk && !input.details().isEmpty()) throw BillingValues.invalid("details");
        long supply = input.sply_amt() == null ? source.supply() : input.sply_amt();
        long vat = BillingValues.vat(supply, mapper.vatRate(month(initial).toString()), input.vat_amt());
        money(supply, vat, false);
        var state = values(initial, input, supply, vat);
        String number = BillingNumbers.allocated(creationMonth, mapper.maximumSuffix(prefix));
        long id = mapper.insert(new Write(0, number, state, user.userId()));
        for (var line : source.details()) mapper.insertDetail(new DetailWrite(id, line));
        audit(id, "INSERT", null, require(id));
        auditDetails(id, "INSERT", new ArrayList<>(), source.details());
        return detail(require(id), new ArrayList<>(source.warnings()));
    }
    @Transactional
    public BillingDetail update(long id, Input input) {
        permissions.can(BillingActions.UPDATE);
        var observed = require(id);
        var project = project(observed.prj_id());
        long root = observed.orgnl_bill_id() == null ? id : observed.orgnl_bill_id();
        mapper.lockBill(root);
        var before = mapper.lockBill(id);
        if (before == null) throw ApiException.notFound("청구가 없습니다.");
        BillingRules.requireDraft(BillingRules.Status.valueOf(before.bill_stts_cd()));
        open(before);
        List<DetailLine> oldLines = mapper.details(id);
        Amount source = null;
        if (input.recalculate()) {
            if ("Y".equals(before.amnd_yn()) || "TK".equals(before.cntrct_ty_cd())) {
                if (!input.details().isEmpty()) throw BillingValues.invalid("details");
            } else {
                List<Overtime> overrides = input.present().contains("details") ? input.details()
                    : oldLines.stream().map(l -> new Overtime(l.asgn_id(), l.ovtm_amt())).toList();
                source = amounts.calculate(project, before.atrb_ym(), overrides);
            }
        }
        List<DetailLine> editedLines = new ArrayList<>();
        if (!input.recalculate() && !input.details().isEmpty()) {
            if (!"HOUR".equals(project.stlm_mthd_cd()) || "Y".equals(before.amnd_yn()))
                throw BillingValues.invalid("details");
            Map<Long, Long> overrides = new HashMap<>();
            input.details().forEach(d -> overrides.put(d.asgn_id(), d.ovtm_amt()));
            for (var line : oldLines) {
                long overtime = overrides.getOrDefault(line.asgn_id(), line.ovtm_amt());
                editedLines.add(new DetailLine(line.asgn_id(), line.dev_id(), line.cntrct_dev_id(),
                    line.display_name(), line.work_ym(), line.mm_qty(), line.source_mm_qty(), line.untprc_amt(),
                    line.work_day_qty(), line.work_tm_qty(), line.ovtm_tm_qty(), overtime,
                    BillingValues.add(line.bill_amt() - line.ovtm_amt(), overtime, "details.ovtm_amt"), line.rmrk_desc()));
                overrides.remove(line.asgn_id());
            }
            if (!overrides.isEmpty()) throw BillingValues.invalid("details.asgn_id");
        }
        long supply = input.sply_amt() != null ? input.sply_amt() : source == null ? before.sply_amt() : source.supply();
        long vat = input.vat_amt() == null ? before.vat_amt() : input.vat_amt();
        money(supply, vat, "Y".equals(before.amnd_yn()));
        var after = values(before, input, supply, vat);
        open(after);
        BillingRules.requireChanged(mapper.update(new Write(id, null, after, user.userId())));
        if (source != null) {
            mapper.deleteDetails(id);
            for (var line : source.details()) mapper.insertDetail(new DetailWrite(id, line));
            auditDetails(id, "UPDATE", oldLines, source.details());
        }
        if (!editedLines.isEmpty()) {
            for (var line : editedLines) BillingRules.requireChanged(mapper.updateDetail(new DetailWrite(id, line)));
            auditDetails(id, "UPDATE", oldLines, editedLines);
        }
        audit(id, "UPDATE", before, after);
        return detail(require(id), source == null ? new ArrayList<>() : new ArrayList<>(source.warnings()));
    }
    private State values(State old, Input input, long supply, long vat) {
        return new State(old.bill_id(), old.prj_id(), old.bill_stg_id(), old.bill_odr_no(), old.atrb_ym(),
            input.present().contains("bill_dt") ? input.bill_dt() : old.bill_dt(), supply, vat,
            old.bill_stts_cd(), old.amnd_yn(), old.orgnl_bill_id(),
            input.present().contains("rcpt_plan_dt") ? input.rcpt_plan_dt() : old.rcpt_plan_dt(),
            input.present().contains("txinv_no") ? input.txinv_no() : old.txinv_no(),
            input.present().contains("txinv_dt") ? input.txinv_dt() : old.txinv_dt(),
            input.present().contains("rmrk_desc") ? input.rmrk_desc() : old.rmrk_desc(),
            old.bill_plan_dt(), old.cntrct_ty_cd());
    }
    private String snapshot(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private BillingRow reminder(BillingRow row) {
        Integer stage = null;
        if (row.receivable_amt() > 0 && row.rcpt_plan_dt() != null && "CONFIRM".equals(row.bill_stts_cd())) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(row.rcpt_plan_dt(), ProjectValues.today(clock));
            stage = days >= 60 ? 3 : days >= 30 ? 2 : days >= 7 ? 1 : 0;
        }
        return new BillingRow(row.bill_id(), row.bill_stg_id(), row.virtual_plan(), row.bill_no(), row.prj_id(),
            row.project_name(), row.customer_id(), row.customer_name(), row.contract_type(), row.atrb_ym(),
            row.bill_dt(), row.sply_amt(), row.vat_amt(), row.tot_amt(), row.bill_stts_cd(), row.txinv_no(),
            row.txinv_dt(), row.rcpt_plan_dt(), row.received_amt(), row.adjusted_total_amt(), row.receivable_amt(),
            stage, row.bill_odr_no(), row.amnd_yn(), row.rmrk_desc());
    }
    private void audit(long id, String type, State before, State after) {
        mapper.audit(new Audit(Long.toString(id), type, "청구", snapshot(before), snapshot(after), user.userId()));
    }
    private void auditDetails(long id, String type, List<DetailLine> before, List<DetailLine> after) {
        Map<Long, DetailLine> old = new LinkedHashMap<>(), next = new LinkedHashMap<>();
        before.forEach(l -> old.put(l.asgn_id(), l));
        after.forEach(l -> next.put(l.asgn_id(), l));
        Set<Long> keys = new LinkedHashSet<>(old.keySet());
        keys.addAll(next.keySet());
        for (Long key : keys) mapper.audit(new Audit(Long.toString(id), type, "상세 " + key,
            snapshot(old.get(key)), snapshot(next.get(key)), user.userId()));
    }
}
