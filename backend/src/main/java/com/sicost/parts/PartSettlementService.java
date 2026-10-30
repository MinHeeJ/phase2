package com.sicost.parts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.validation.*;
import com.sicost.costallocation.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.parts.PartSettlementModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PartSettlementService {
    private final PartSettlementMapper mapper;
    private final CostAllocationService allocations;
    private final PartSettlementApprovalPolicy approval;
    private final Clock clock;
    private final ObjectMapper json;
    private final CurrentUser user;

    public PartSettlementService(PartSettlementMapper mapper, CostAllocationService allocations,
            PartSettlementApprovalPolicy approval, Clock clock, ObjectMapper json, CurrentUser user) {
        this.mapper = mapper;
        this.allocations = allocations;
        this.approval = approval;
        this.clock = clock;
        this.json = json;
        this.user = user;
    }

    public Scope scope(long partId, String month) {
        return new Scope(CostValues.id(partId, "partId"), CostValues.month(month, clock).toString());
    }

    public SettlementList list(String month) {
        String selected = CostValues.month(month, clock).toString();
        List<PartRow> rows = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        for (Part part : mapper.parts()) {
            Detail detail = monthly(new Scope(part.bsn_part_id(), selected));
            warnings.addAll(detail.warnings());
            rows.add(new PartRow(part.bsn_part_id(), part.bsn_part_nm(), detail.settlement(),
                detail.provisional(), detail.confirmation_available(), detail.warnings()));
        }
        return new SettlementList(selected, reference(), closed(selected), "원", List.copyOf(warnings), List.copyOf(rows));
    }

    private void requirePart(Scope scope) {
        if (mapper.part(scope.partId()) == null) throw ApiException.notFound("영업 파트가 없습니다.");
    }

    public Detail detail(Scope requested, String view) {
        Scope scope = scope(requested.partId(), requested.month());
        if (view != null && !Set.of("MONTH", "TREND").contains(view)) throw BillingValues.invalid("view");
        requirePart(scope);
        Detail result = monthly(scope);
        if (!"TREND".equals(view)) return result;
        List<Detail> months = new ArrayList<>();
        YearMonth through = YearMonth.parse(scope.month());
        for (int i = 1; i <= through.getMonthValue(); i++) {
            months.add(monthly(new Scope(scope.partId(), YearMonth.of(through.getYear(), i).toString())));
        }
        Set<String> warnings = new LinkedHashSet<>(result.warnings());
        months.forEach(m -> warnings.addAll(m.warnings()));
        return new Detail(result.calculation_month(), result.reference_month(), result.closed(), result.unit(),
            List.copyOf(warnings), result.settlement(), result.items(), PartSettlementCalculator.trend(months),
            result.history(), result.standard_history(), result.provisional(), result.confirmation_available());
    }

    private Detail monthly(Scope scope) {
        boolean closed = closed(scope.month());
        Settlement stored = mapper.settlement(scope);
        List<Standard> standards = mapper.standards(scope.partId());
        boolean confirmed = stored != null && "CONFIRM".equals(stored.stlm_stts_cd());
        List<Item> items;
        List<String> warnings = new ArrayList<>();
        Settlement settlement;
        if (confirmed) {
            settlement = stored;
            items = mapper.details(stored.stlm_id());
        } else {
            Standard standard = PartSettlementCalculator.effective(standards, scope.month());
            if (standard == null) warnings.add("파트 기준 없음: " + scope.month());
            if (!approval.approved()) warnings.add("D-31/D-32 운영 기준 승인 대기: 잠정 계산이며 정산 확정은 보류됩니다.");
            var people = mapper.people(scope);
            List<CostAllocationModels.AllocationRow> source = List.of();
            if (!people.isEmpty()) {
                try {
                    var allocation = allocations.calculate(scope.month(), null);
                    source = allocation.items();
                    warnings.addAll(allocation.warnings());
                } catch (ApiException ex) {
                    if (closed && ex.status() == 404) throw missing("마감 배분자료 없음", "month");
                    throw ex;
                }
            } else warnings.add("급여 미업로드 또는 해당 월 소속 인력 없음: " + scope.month());
            List<Item> calculated = new ArrayList<>();
            for (Person person : people) {
                if (closed && (person.real_pay_amt() != 0 || person.rtrpay_acml_amt() != 0)
                        && source.stream().noneMatch(a -> Objects.equals(a.dev_id(), person.dev_id()))) {
                    throw missing("마감 배분자료 없음", "month");
                }
                if (person.first_start() == null) warnings.add("MM 없음: dev_id=" + person.dev_id());
                calculated.add(PartSettlementCalculator.person(person, scope.month(), standard, source,
                    mapper.projectNames(new PersonMonth(person.dev_id(), scope.month()))));
            }
            items = List.copyOf(calculated);
            settlement = PartSettlementCalculator.summarize(scope, stored, standard, items);
        }
        List<History> history = stored == null ? List.of() : mapper.history(stored.stlm_id());
        boolean available = !confirmed && !closed && currentYear(scope.month()) && approval.approved()
            && settlement.part_std_hist_id() != null && warnings.isEmpty();
        return new Detail(scope.month(), reference(), closed, "원", List.copyOf(warnings), settlement,
            items, List.of(), history, standards, !confirmed, available);
    }

    private String reference() { return YearMonth.from(ProjectValues.today(clock)).toString(); }
    private boolean currentYear(String month) { return YearMonth.parse(month).getYear() == ProjectValues.today(clock).getYear(); }
    private boolean closed(String month) { return "CLOSED".equals(mapper.monthStatus(month)); }
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    private static ApiException missing(String message, String field) {
        return ApiException.conflict("PART_STANDARD_MISSING", message, field);
    }
    private static ApiException stale() {
        return ApiException.conflict("STALE_SETTLEMENT", "정산 상태가 변경되었습니다. 다시 조회하세요.", null);
    }
    private void changed(int count) { if (count != 1) throw stale(); }

    private Settlement locked(Scope scope) {
        if (mapper.lockPart(scope.partId()) == null) throw ApiException.notFound("영업 파트가 없습니다.");
        mapper.lockSettlement(scope);
        if ("CLOSED".equals(mapper.lockMonth(scope.month())) || !currentYear(scope.month())) {
            throw ApiException.conflict("CLOSED_MONTH", "마감 월 또는 지난 연도는 변경할 수 없습니다.", "month");
        }
        return mapper.settlement(scope);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail card(Scope requested, Card card) {
        Scope scope = scope(requested.partId(), requested.month());
        if (card == null || card.corp_card_amt() < 0) throw BillingValues.invalid("corp_card_amt");
        if (card.rmrk_desc() != null && card.rmrk_desc().length() > 500) throw BillingValues.invalid("rmrk_desc");
        Settlement before = locked(scope);
        if (before != null && "CONFIRM".equals(before.stlm_stts_cd())) {
            throw ApiException.conflict("PART_ALREADY_CONFIRMED", "확정 정산은 수정할 수 없습니다.", null);
        }
        long id = mapper.saveCard(new CardWrite(scope, card, user.userId()));
        if (id <= 0) throw stale();
        mapper.audit(new Audit(id, "corp_card_amt", snapshot(before == null ? null
            : new Card(before.corp_card_amt(), before.rmrk_desc())), snapshot(card), "법인카드 저장", user.userId()));
        return monthly(scope);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail confirm(Scope requested) {
        Scope scope = scope(requested.partId(), requested.month());
        Settlement before = locked(scope);
        if (before != null && "CONFIRM".equals(before.stlm_stts_cd())) throw stale();
        if (!approval.approved()) throw missing("D-31/D-32 운영 기준 승인 대기로 정산 확정을 보류합니다.", "month");
        Detail calculated = monthly(scope);
        if (calculated.settlement().part_std_hist_id() == null || !calculated.warnings().isEmpty()) {
            throw missing("파트 기준 또는 원천 자료를 확인하세요.", "month");
        }
        long id = before == null ? mapper.saveCard(new CardWrite(scope, new Card(0, null), user.userId())) : before.stlm_id();
        changed(mapper.snapshot(new SnapshotWrite(id, calculated.settlement(), user.userId(), now())));
        mapper.deleteDetails(id);
        for (Item item : calculated.items()) mapper.insertDetail(new ItemWrite(id, item));
        Transition transition = new Transition(id, "DRAFT", "CONFIRM", null, user.userId(), now());
        changed(mapper.transition(transition));
        mapper.historyWrite(transition);
        mapper.audit(new Audit(id, "stlm_stts_cd", snapshot(before), snapshot(mapper.settlement(scope)), "정산 확정", user.userId()));
        mapper.access(new Access("SETTLE_CONFIRM", scope.month(), snapshot(scope), user.userId()));
        return monthly(scope);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail cancel(Scope requested, String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) throw BillingValues.invalid("reason");
        Scope scope = scope(requested.partId(), requested.month());
        Settlement before = locked(scope);
        if (before == null || !"CONFIRM".equals(before.stlm_stts_cd())) throw stale();
        Transition transition = new Transition(before.stlm_id(), "CONFIRM", "DRAFT", reason.trim(), user.userId(), now());
        changed(mapper.transition(transition));
        mapper.historyWrite(transition);
        mapper.audit(new Audit(before.stlm_id(), "stlm_stts_cd", snapshot(before), snapshot(mapper.settlement(scope)), reason.trim(), user.userId()));
        mapper.access(new Access("SETTLE_CANCEL", scope.month(), snapshot(scope), user.userId()));
        return monthly(scope);
    }

    private String snapshot(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
