package com.sicost.parts;

import com.sicost.common.api.ApiException;
import com.sicost.common.costallocation.CostAllocationService;
import com.sicost.common.costallocation.CostAnalysisInput;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.parts.PartSettlementModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PartSettlementService {
    private final PartSettlementMapper mapper;
    private final CostAllocationService allocation;
    private final PermissionService permissions;
    private final CurrentUser user;
    private final Clock clock;
    private final PartSettlementApprovalPolicy approval;
    public PartSettlementService(PartSettlementMapper mapper, CostAllocationService allocation,
        PermissionService permissions, CurrentUser user, Clock clock, PartSettlementApprovalPolicy approval) {
        this.mapper = mapper;
        this.allocation = allocation;
        this.permissions = permissions;
        this.user = user;
        this.clock = clock;
        this.approval = approval;
    }
    private String reference() {
        return YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString();
    }
    private Part require(Key key) {
        CostAnalysisInput.month(key.month());
        if (key.partId() <= 0) throw CostAnalysisInput.invalid("partId");
        var part = mapper.part(key.partId());
        if (part == null) throw ApiException.notFound("영업 파트가 없습니다.");
        return part;
    }
    private String status(String month) {
        return "CLOSED".equals(mapper.monthStatus(month)) ? "CLOSED" : "OPEN";
    }
    public Page list(Map<String, String> input) {
        permissions.can(CostAnalysisActions.SETTLEMENTS_READ);
        String month = CostAnalysisInput.month(input.get("month"));
        var paging = CostAnalysisInput.paging(input);
        String sort = input.getOrDefault("sort", "bsn_part_nm,asc");
        var rows = rows(month);
        rows.sort(order(sort));
        var warnings = new LinkedHashSet<String>();
        for (var row : rows) if (row.provisional()) warnings.add("D-31/D-32 운영 승인 대기: 잠정 정산");
        Long total = rows.stream().anyMatch(r -> r.part_prft_amt() == null) ? null
            : rows.stream().map(Row::part_prft_amt).reduce(0L, Math::addExact);
        var summary = new Summary(rows.size(), (int) rows.stream()
            .filter(r -> "CONFIRM".equals(r.stlm_stts_cd())).count(), total);
        return new Page(month, status(month), reference(), "KRW", List.copyOf(warnings),
            rows.stream().skip(paging.offset()).limit(paging.size()).toList(),
            rows.size(), paging.page(), paging.size(), summary);
    }
    List<Row> rows(String month) {
        CostAnalysisInput.month(month);
        var rows = new ArrayList<Row>();
        for (var part : mapper.parts()) {
            var detail = month(new Key(part.bsn_part_id(), month), part);
            var h = detail.settlement();
            rows.add(new Row(part.bsn_part_id(), part.bsn_part_nm(), detail.ldr_nm(), h.prft_dstrb_rt(),
                h.psn_cnt(), h.tot_diff_amt(), h.tot_psn_prft_amt(), h.corp_card_amt(), h.part_prft_amt(),
                h.stlm_stts_cd(), detail.provisional()));
        }
        return rows;
    }
    public Detail get(Key key, String view) {
        permissions.can(CostAnalysisActions.SETTLEMENTS_READ);
        PartSettlementInput.view(view);
        var part = require(key);
        var current = month(key, part);
        if (!"TREND".equals(view)) return current;
        var months = new ArrayList<TrendMonth>();
        var details = new ArrayList<Detail>();
        YearMonth end = YearMonth.parse(key.month());
        for (int m = 1; m <= end.getMonthValue(); m++) {
            String target = YearMonth.of(end.getYear(), m).toString();
            var detail = target.equals(key.month()) ? current : month(new Key(key.partId(), target), part);
            details.add(detail);
            months.add(new TrendMonth(target, detail.settlement().stlm_stts_cd(), detail.provisional()));
        }
        var people = new TreeMap<Long, String>();
        details.forEach(d -> d.items().forEach(p -> people.put(p.dev_id(), p.dev_nm())));
        var trend = new ArrayList<TrendRow>();
        var metrics = List.of("MM", "PRICE", "COST", "DIFFERENCE", "PROFIT");
        for (var person : people.entrySet()) {
            for (String metric : metrics) {
                var values = new ArrayList<BigDecimal>();
                for (var detail : details) {
                    var row = detail.items().stream().filter(p -> p.dev_id() == person.getKey()).findFirst().orElse(null);
                    values.add(row == null ? null : metric(row, metric));
                }
                boolean missing = false;
                for (int i = 0; i < details.size(); i++) {
                    final int index = i;
                    if (values.get(i) == null && details.get(i).items().stream()
                        .anyMatch(p -> p.dev_id() == person.getKey())) missing = true;
                }
                BigDecimal total = missing ? null : values.stream().filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                trend.add(new TrendRow(person.getKey(), person.getValue(), metric,
                    Collections.unmodifiableList(values), total));
            }
        }
        var warnings = new LinkedHashSet<String>();
        details.forEach(d -> warnings.addAll(d.warnings()));
        return new Detail(current.month(), current.status(), current.referenceMonth(), "KRW",
            List.copyOf(warnings), current.provisional(), current.confirmation_allowed(), current.bsn_part_nm(),
            current.ldr_nm(), current.settlement(), current.note(), current.items(), current.history(),
            current.standards(), months, trend);
    }
    private static BigDecimal metric(Person row, String metric) {
        if ("MM".equals(metric)) return row.mm_qty();
        Long value = switch (metric) {
            case "PRICE" -> row.mth_untprc_amt();
            case "COST" -> row.tot_exp_amt();
            case "DIFFERENCE" -> row.diff_amt();
            case "PROFIT" -> row.prft_amt();
            default -> throw new IllegalArgumentException("추이 항목을 확인하세요.");
        };
        return value == null ? null : BigDecimal.valueOf(value);
    }
    private Detail month(Key key, Part part) {
        var saved = mapper.header(key);
        var standards = mapper.standards(key.partId());
        String closed = status(key.month());
        if (saved != null && "CONFIRM".equals(saved.stlm_stts_cd())) {
            String leader = standards.stream().filter(s -> Objects.equals(s.part_std_hist_id(), saved.part_std_hist_id()))
                .map(s -> Objects.toString(s.ldr_nm(), "미지정")).findFirst().orElse("미지정");
            return new Detail(key.month(), closed, reference(), "KRW", List.of(), false, false,
                part.bsn_part_nm(), leader, saved, mapper.note(saved.stlm_id()), mapper.details(saved.stlm_id()),
                mapper.history(saved.stlm_id()), standards, List.of(), List.of());
        }
        var standard = mapper.standard(key);
        boolean approved = standard != null && approval.approved(key, standard);
        var warnings = new LinkedHashSet<String>();
        if (!approved) warnings.add("D-31/D-32 운영 승인 대기: 잠정 정산, 승인된 적용 정산 기준이 없어 확정할 수 없습니다.");
        if (standard == null) warnings.add("파트 기준 없음: 해당 월 적용 이력을 등록해 주세요.");
        var salaries = mapper.salaries(key);
        if (mapper.missingSalary(key) > 0) warnings.add("급여 미업로드: 월 스냅샷이 없어 귀속 파트를 확인할 수 없는 인력이 있습니다.");
        List<com.sicost.common.costallocation.CostAllocationModels.Allocation> allocations = List.of();
        if (!salaries.isEmpty()) {
            var result = allocation.get(key.month(), null);
            allocations = result.items();
            warnings.addAll(result.warnings());
        }
        var people = PartSettlementCalculator.people(key, standard, salaries, allocations, warnings);
        var header = PartSettlementCalculator.header(key, saved, standard, people);
        return new Detail(key.month(), closed, reference(), "KRW", List.copyOf(warnings), !approved,
            approved && "OPEN".equals(closed) && key.month().substring(0, 4).equals(reference().substring(0, 4))
                && warnings.isEmpty(), part.bsn_part_nm(), standard == null ? null : standard.ldr_nm(), header,
            saved == null ? null : mapper.note(saved.stlm_id()), people,
            saved == null ? List.of() : mapper.history(saved.stlm_id()), standards, List.of(), List.of());
    }
    private void open(Key key) {
        if ("CLOSED".equals(status(key.month())) || !key.month().substring(0, 4).equals(reference().substring(0, 4)))
            throw ApiException.conflict("CLOSED_MONTH", "지난 연도 또는 마감한 달은 조회만 가능합니다.", "month");
    }
    private void lock(Key key) {
        require(key);
        if (mapper.lockPart(key.partId()) == null) throw ApiException.notFound("영업 파트가 없습니다.");
        open(key);
    }
    private static void changed(int count) {
        if (count != 1) throw ApiException.conflict("STALE_SETTLEMENT", "정산 상태가 변경되었습니다. 다시 조회하세요.", null);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail card(Key key, Card card) {
        permissions.can(CostAnalysisActions.SETTLEMENTS_UPDATE);
        if (card == null || card.corp_card_amt() < 0) throw CostAnalysisInput.invalid("corp_card_amt");
        if (card.notePresent() && (card.note() == null || card.note().length() > 500)) throw CostAnalysisInput.invalid("note");
        lock(key);
        mapper.ensureDraft(key);
        var before = mapper.lockHeader(key);
        if (!"DRAFT".equals(before.stlm_stts_cd()))
            throw ApiException.conflict("PART_ALREADY_CONFIRMED", "확정한 정산의 카드는 변경할 수 없습니다.", null);
        String previous = mapper.note(before.stlm_id());
        String note = card.notePresent() ? card.note().trim() : previous;
        changed(mapper.card(new CardWrite(before.stlm_id(), card.corp_card_amt(), user.userId())));
        mapper.audit(new Audit(before.stlm_id().toString(), "corp_card_amt",
            Long.toString(before.corp_card_amt()), Long.toString(card.corp_card_amt()), note, user.userId()));
        if (!Objects.equals(previous, note)) mapper.audit(new Audit(before.stlm_id().toString(), "note",
            previous, note, "카드 비고 변경", user.userId()));
        return month(key, require(key));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail confirm(Key key) {
        permissions.can(CostAnalysisActions.SETTLEMENTS_CONFIRM);
        lock(key);
        var existing = mapper.lockHeader(key);
        if (existing != null && "CONFIRM".equals(existing.stlm_stts_cd()))
            throw ApiException.conflict("STALE_SETTLEMENT", "이미 확정되었거나 정산 상태가 변경되었습니다.", null);
        var standard = mapper.standard(key);
        if (standard == null || !approval.approved(key, standard))
            throw ApiException.conflict("PART_STANDARD_MISSING",
                "운영 승인 대기: 승인된 적용 정산 기준이 없어 확정할 수 없습니다.", null);
        mapper.ensureDraft(key);
        var before = mapper.lockHeader(key);
        var computed = month(key, require(key));
        if (!computed.confirmation_allowed()) throw ApiException.conflict("PART_STANDARD_MISSING", "정산 원천 결손을 확인해 주세요.", null);
        open(key);
        changed(mapper.confirm(new Confirmation(computed.settlement(), user.userId())));
        mapper.clearDetails(before.stlm_id());
        for (var person : computed.items()) mapper.insertDetail(new DetailWrite(before.stlm_id(), person));
        mapper.insertHistory(new HistoryWrite(before.stlm_id(), "CONFIRM", null, user.userId()));
        audit(before, mapper.header(key), "정산 확정");
        return month(key, require(key));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail cancel(Key key, String reason) {
        permissions.can(CostAnalysisActions.SETTLEMENTS_CANCEL);
        if (reason == null || reason.isBlank() || reason.length() > 500) throw CostAnalysisInput.invalid("reason");
        lock(key);
        var before = mapper.lockHeader(key);
        if (before == null || !"CONFIRM".equals(before.stlm_stts_cd()))
            throw ApiException.conflict("STALE_SETTLEMENT", "확정된 정산만 취소할 수 있습니다.", null);
        changed(mapper.cancel(new Transition(before.stlm_id(), reason.trim(), user.userId())));
        mapper.insertHistory(new HistoryWrite(before.stlm_id(), "CANCEL", reason.trim(), user.userId()));
        audit(before, mapper.header(key), reason.trim());
        mapper.audit(new Audit(before.stlm_id().toString(), "cncl_rsn_desc", null, reason.trim(), reason.trim(), user.userId()));
        return month(key, require(key));
    }
    private void audit(Header before, Header after, String reason) {
        var fields = new LinkedHashMap<String, Function<Header, Object>>();
        fields.put("part_std_hist_id", Header::part_std_hist_id);
        fields.put("prft_dstrb_rt", Header::prft_dstrb_rt);
        fields.put("bsn_fee_amt", Header::bsn_fee_amt);
        fields.put("psn_cnt", Header::psn_cnt);
        fields.put("tot_sls_amt", Header::tot_sls_amt);
        fields.put("tot_exp_amt", Header::tot_exp_amt);
        fields.put("tot_diff_amt", Header::tot_diff_amt);
        fields.put("tot_psn_prft_amt", Header::tot_psn_prft_amt);
        fields.put("corp_card_amt", Header::corp_card_amt);
        fields.put("part_prft_amt", Header::part_prft_amt);
        fields.put("stlm_stts_cd", Header::stlm_stts_cd);
        fields.put("cnfrm_at", Header::cnfrm_at);
        fields.forEach((field, getter) -> {
            Object oldValue = getter.apply(before), newValue = getter.apply(after);
            if (!Objects.equals(oldValue, newValue)) mapper.audit(new Audit(before.stlm_id().toString(), field,
                Objects.toString(oldValue, null), Objects.toString(newValue, null), reason, user.userId()));
        });
    }
    static Comparator<Row> order(String sort) {
        String[] parts = sort.split(",", -1);
        if (parts.length != 2 || !Set.of("asc", "desc").contains(parts[1])) throw CostAnalysisInput.invalid("sort");
        Function<Row, Comparable<?>> getter = switch (parts[0]) {
            case "bsn_part_id" -> Row::bsn_part_id;
            case "bsn_part_nm" -> Row::bsn_part_nm;
            case "psn_cnt" -> Row::psn_cnt;
            case "part_prft_amt" -> Row::part_prft_amt;
            case "stlm_stts_cd" -> Row::stlm_stts_cd;
            default -> throw CostAnalysisInput.invalid("sort");
        };
        @SuppressWarnings({"rawtypes", "unchecked"})
        Comparator<Comparable<?>> scalar = (a, b) -> ((Comparable) a).compareTo(b);
        if ("desc".equals(parts[1])) scalar = scalar.reversed();
        return Comparator.comparing(getter, Comparator.nullsLast(scalar)).thenComparingLong(Row::bsn_part_id);
    }
}
