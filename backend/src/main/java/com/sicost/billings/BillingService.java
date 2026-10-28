package com.sicost.billings;

import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.validation.BillingRules;
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
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    public Page list(Map<String, String> params) {
        BillingActions.can(permissions, "listBillings");
        var query = query(params);
        return new Page(mapper.list(query), mapper.count(query), query.page(), query.size());
    }
    public Query query(Map<String, String> params) {
        Integer year = integer(params, "year", today().getYear());
        if (year < 1 || year > 9999) throw BillingRules.invalid("year");
        String status = optional(params.get("status")), type = optional(params.get("type"));
        if (status != null) BillingRules.status(status);
        if (type != null) BillingRules.contractType(type);
        Long customer = null;
        if (optional(params.get("customerId")) != null) {
            try { customer = Long.valueOf(params.get("customerId")); }
            catch (NumberFormatException ex) { throw BillingRules.invalid("customerId"); }
            if (customer < 1) throw BillingRules.invalid("customerId");
        }
        String q = optional(params.get("q"));
        if (q != null && q.length() > 200) throw BillingRules.invalid("q");
        var paging = new BillingPaging(integer(params, "page", 0), integer(params, "size", 50),
            optional(params.get("sort")), BillingPaging.View.BILLINGS);
        String order = optional(params.get("sort")) == null
            ? "virtual_plan asc, bill_dt desc nulls last, bill_id desc, bill_plan_dt asc, bill_stg_id asc"
            : paging.order() + ", bill_stg_id asc";
        return new Query(year, status, type, customer, q, paging.page(), paging.size(),
            paging.offset(), order, today());
    }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value; }
    private static int integer(Map<String, String> params, String field, int fallback) {
        if (!params.containsKey(field)) return fallback;
        try { return Integer.parseInt(params.get(field)); }
        catch (NumberFormatException ex) { throw BillingRules.invalid(field); }
    }
    private State require(long id) {
        if (id < 1) throw BillingRules.invalid("id");
        var state = mapper.state(id);
        if (state == null) throw ApiException.notFound("청구가 없습니다.");
        return state;
    }
    public Detail detail(long id) {
        BillingActions.can(permissions, "getBilling");
        var state = require(id);
        var lines = mapper.details(id);
        List<String> warnings = new ArrayList<>();
        if (BillingAmountService.sum(lines) != state.sply_amt()) warnings.add("상세 합계와 공급가액이 다릅니다. 확정할 수 있습니다.");
        if (state.txinv_no() == null || state.txinv_no().isBlank()) warnings.add("세금계산서 번호가 없습니다.");
        var key = new ReadKey(id, today());
        return new Detail(mapper.row(key), lines, mapper.receipts(id), mapper.amendments(key),
            state.orgnl_bill_id(), mapper.history(id), warnings);
    }
    private Project writable(long id) {
        var project = mapper.lockProject(id);
        if (project == null) throw ApiException.notFound("프로젝트가 없습니다.");
        BillingRules.contractType(project.contract_type());
        if (!"Y".equals(project.use_yn())) throw BillingRules.invalid("project_id");
        return project;
    }
    private void open(String month) { BillingRules.openMonth(month, mapper.monthStatus(month)); }
    private String month(Project project, State state) {
        if (!"TK".equals(project.contract_type())) return state.atrb_ym();
        if (state.bill_dt() != null) return YearMonth.from(state.bill_dt()).toString();
        var stage = state.bill_stg_id() == null ? null
            : mapper.stage(new Scope(state.prj_id(), state.bill_stg_id(), null));
        return YearMonth.from(stage != null && stage.date() != null ? stage.date() : state.created_date()).toString();
    }
    @Transactional
    public Detail create(JsonNode input) {
        BillingActions.can(permissions, "createBilling");
        BillingInput.parse(input, true);
        long projectId = BillingInput.positive(input, "project_id", true);
        String prefix = "B-" + today().format(DateTimeFormatter.ofPattern("yyMM")) + "-";
        mapper.lockNumber(prefix);
        var project = writable(projectId);
        boolean tk = "TK".equals(project.contract_type());
        Long stageId = input.has("stage_id") ? BillingInput.positive(input, "stage_id", true) : null;
        LocalDate date = BillingInput.date(input, "bill_dt", null);
        String month;
        if (tk) {
            if (stageId == null || input.hasNonNull("month")) throw BillingRules.invalid("stage_id");
            var stage = mapper.stage(new Scope(projectId, stageId, null));
            if (stage == null) throw BillingRules.invalid("stage_id");
            month = YearMonth.from(date != null ? date : stage.date() != null ? stage.date() : today()).toString();
        } else {
            if (stageId != null) throw BillingRules.invalid("stage_id");
            month = BillingRules.month(BillingInput.text(input, "month", 7, null), "month").toString();
        }
        open(month);
        var scope = new Scope(projectId, stageId, tk ? null : month);
        var active = mapper.active(scope);
        int ordinal = (int) BillingInput.amount(input, "bill_odr_no", 1);
        if (!active.isEmpty()) {
            int max = active.stream().mapToInt(State::bill_odr_no).max().orElse(0);
            if (tk || !input.path("additional").asBoolean(false) || ordinal <= max) {
                throw ApiException.conflict("ALREADY_BILLED", "기존 청구를 여세요: " + active.get(0).bill_id(),
                    tk ? "stage_id" : "month");
            }
        }
        var proposed = amounts.calculate(project, new Scope(projectId, stageId, month), BillingInput.overtime(input));
        long supply = BillingInput.amount(input, "sply_amt", proposed.supply());
        long vat = BillingInput.amount(input, "vat_amt", proposed.vat());
        BillingRules.amounts(supply, vat, false, false);
        long sequence = mapper.nextNumber(prefix);
        String number = prefix + String.format(Locale.ROOT, "%02d", sequence);
        if (number.length() > 20) throw BillingRules.invalid("bill_no");
        var state = new State(0, number, projectId, stageId, ordinal, tk ? null : month,
            date, supply, vat, "DRAFT", BillingInput.text(input, "txinv_no", 50, null),
            BillingInput.date(input, "txinv_dt", null), BillingInput.date(input, "rcpt_plan_dt", null),
            null, "N", null, today());
        long id = mapper.insert(new Write(state, user.userId()));
        for (var line : proposed.details()) mapper.insertDetail(new DetailWrite(id, line));
        audit(id, "INSERT", null, require(id));
        auditDetails(id, "INSERT", Collections.emptyList(), proposed.details());
        return warnings(detail(id), proposed.warnings());
    }
    @Transactional
    public Detail update(long id, JsonNode input) {
        BillingActions.can(permissions, "updateBilling");
        var initial = require(id);
        var project = writable(initial.prj_id());
        var before = mapper.lockBill(id);
        if (before == null) throw ApiException.notFound("청구가 없습니다.");
        BillingRules.draft(before.bill_stts_cd());
        BillingInput.parse(input, false);
        String oldMonth = month(project, before);
        open(oldMonth);
        var date = BillingInput.date(input, "bill_dt", before.bill_dt());
        var candidate = new State(id, before.bill_no(), before.prj_id(), before.bill_stg_id(),
            before.bill_odr_no(), before.atrb_ym(), date, before.sply_amt(), before.vat_amt(),
            before.bill_stts_cd(), before.txinv_no(), before.txinv_dt(), before.rcpt_plan_dt(),
            before.rmrk_desc(), before.amnd_yn(), before.orgnl_bill_id(), before.created_date());
        String newMonth = month(project, candidate);
        open(newMonth);
        var oldLines = mapper.details(id);
        var lines = oldLines;
        long supply = before.sply_amt(), vat = before.vat_amt();
        List<String> calculationWarnings = new ArrayList<>();
        boolean recalculate = input.path("recalculate").asBoolean(false);
        if (recalculate) {
            if ("Y".equals(before.amnd_yn())) throw BillingRules.invalid("recalculate");
            var proposal = amounts.calculate(project,
                new Scope(before.prj_id(), before.bill_stg_id(), newMonth), BillingInput.overtime(input));
            lines = proposal.details();
            calculationWarnings.addAll(proposal.warnings());
            supply = proposal.supply();
            vat = proposal.vat();
        } else if (input.has("details")) {
            lines = amounts.manualOvertime(oldLines, BillingInput.overtime(input), project);
        }
        supply = BillingInput.amount(input, "sply_amt", supply);
        vat = BillingInput.amount(input, "vat_amt", vat);
        BillingRules.amounts(supply, vat, "Y".equals(before.amnd_yn()), false);
        var after = new State(id, before.bill_no(), before.prj_id(), before.bill_stg_id(),
            before.bill_odr_no(), before.atrb_ym(), date, supply, vat, "DRAFT",
            BillingInput.text(input, "txinv_no", 50, before.txinv_no()),
            BillingInput.date(input, "txinv_dt", before.txinv_dt()),
            BillingInput.date(input, "rcpt_plan_dt", before.rcpt_plan_dt()),
            BillingInput.text(input, "rmrk_desc", 500, before.rmrk_desc()), before.amnd_yn(),
            before.orgnl_bill_id(), before.created_date());
        BillingRules.changed(mapper.update(new Write(after, user.userId())));
        if (recalculate) {
            mapper.deleteDetails(id);
            for (var line : lines) mapper.insertDetail(new DetailWrite(id, line));
        } else if (input.has("details")) {
            for (var line : lines) if (line.asgn_id() != null) mapper.overtime(new DetailWrite(id, line));
        }
        if ("TK".equals(project.contract_type()) && !oldMonth.equals(newMonth)) {
            mapper.moveDetailMonth(new Scope(id, null, newMonth));
        }
        audit(id, "UPDATE", before, after);
        auditDetails(id, "UPDATE", oldLines, mapper.details(id));
        return warnings(detail(id), calculationWarnings);
    }
    private Detail warnings(Detail detail, List<String> additional) {
        var combined = new ArrayList<>(detail.warnings());
        combined.addAll(additional);
        return new Detail(detail.billing(), detail.details(), detail.receipts(), detail.amendments(),
            detail.original_bill_id(), detail.change_history(), List.copyOf(combined));
    }
    private void audit(long id, String type, State before, State after) {
        JsonNode old = before == null ? json.createObjectNode() : json.valueToTree(before);
        JsonNode next = json.valueToTree(after);
        next.fieldNames().forEachRemaining(field -> {
            if (!Objects.equals(old.get(field), next.get(field))) {
                mapper.audit(new Audit(Long.toString(id), type, field,
                    old.has(field) ? old.get(field).toString() : null, next.get(field).toString(), user.userId()));
            }
        });
    }
    private void auditDetails(long id, String type, List<Line> before, List<Line> after) {
        int count = Math.max(before.size(), after.size());
        for (int i = 0; i < count; i++) {
            Line old = i < before.size() ? before.get(i) : null;
            Line next = i < after.size() ? after.get(i) : null;
            if (!Objects.equals(old, next)) mapper.audit(new Audit(Long.toString(id), type,
                "detail_" + i, old == null ? null : json.valueToTree(old).toString(),
                next == null ? null : json.valueToTree(next).toString(), user.userId()));
        }
    }
}
