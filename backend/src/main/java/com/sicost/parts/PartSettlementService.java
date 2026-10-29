package com.sicost.parts;

import com.sicost.apiops.ProjectCostService;
import com.sicost.common.api.ApiException;
import com.sicost.common.costs.CostAllocationService;
import com.sicost.common.security.*;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.parts.PartSettlementModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PartSettlementService {
    private final PartSettlementMapper mapper;
    private final ProjectCostService costs;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final PartSettlementApproval approval;

    public PartSettlementService(PartSettlementMapper mapper, ProjectCostService costs,
            CurrentUser user, PermissionService permissions, Clock clock, PartSettlementApproval approval) {
        this.mapper = mapper;
        this.costs = costs;
        this.user = user;
        this.permissions = permissions;
        this.clock = clock;
        this.approval = approval;
    }
    public static Key key(String partId, String month) {
        CostAllocationService.month(month);
        try {
            if (partId == null || !partId.matches("[0-9]{1,19}")) throw new NumberFormatException();
            long id = Long.parseLong(partId);
            if (id < 1) throw new NumberFormatException();
            return new Key(id, month);
        } catch (NumberFormatException ex) {
            throw invalid("파트 번호를 확인하세요.", "partId");
        }
    }
    static ApiException invalid(String message, String field) {
        return new ApiException(400, "VALIDATION_ERROR", message, field);
    }
    private Part require(Key key, boolean lock) {
        Part part = lock ? mapper.lockPart(key.partId()) : mapper.part(key.partId());
        if (part == null) throw ApiException.notFound("본사 외 영업 파트가 없습니다.");
        return part;
    }
    public List<Summary> list(String month) {
        CostAllocationService.month(month);
        permissions.can(CostActions.key("listPartSettlements"));
        return mapper.parts().stream().map(p -> new Summary(p, calculate(new Key(p.bsn_part_id(), month)))).toList();
    }
    public Detail get(Key key, String view) {
        permissions.can(CostActions.key("getPartSettlement"));
        if (view == null) view = "MONTH";
        if (!Set.of("MONTH", "TREND").contains(view)) throw invalid("조회 구분을 확인하세요.", "view");
        Part part = require(key, false);
        var months = new ArrayList<Month>();
        int end = YearMonth.parse(key.month()).getMonthValue();
        int start = "TREND".equals(view) ? 1 : end;
        for (int m = start; m <= end; m++) {
            months.add(calculate(new Key(key.partId(), YearMonth.of(YearMonth.parse(key.month()).getYear(), m).toString())));
        }
        return new Detail(part, view, List.copyOf(months));
    }
    private Month calculate(Key key) {
        Header header = mapper.header(key);
        boolean closed = "CLOSED".equals(mapper.monthStatus(key.month()));
        long card = header == null ? 0 : header.corp_card_amt();
        String remark = header == null ? null : header.card_remark();
        var history = header == null ? Collections.<History>emptyList() : mapper.history(header.stlm_id());
        if (header != null && "CONFIRM".equals(header.stlm_stts_cd())) {
            var frozen = new Standard(header.part_std_hist_id() == null ? 0 : header.part_std_hist_id(),
                null, header.prft_dstrb_rt(), header.bsn_fee_amt() == null ? 0 : header.bsn_fee_amt());
            return new Month(key.month(), "CONFIRM", closed, false, frozen, card, remark,
                header.part_prft_amt(), mapper.details(header.stlm_id()), Collections.emptyList(), history);
        }
        Standard standard = mapper.standard(key);
        var warnings = new ArrayList<String>();
        warnings.add("D-31/D-32 운영 승인 대기 — 잠정 계산이며 정산 확정은 보류됩니다.");
        if (standard == null) warnings.add("파트 기준 없음 — 확정 불가");
        var salary = mapper.salaries(key);
        var allocation = costs.allocation(key.month(), null);
        allocation.warnings().forEach(w -> warnings.add(w.message()));
        var people = salary.stream().map(s -> PartSettlementCalculator.person(YearMonth.parse(key.month()),
            s, standard, allocation.items(), warnings)).toList();
        return new Month(key.month(), "DRAFT", closed, true, standard, card, remark,
            standard == null ? null : PartSettlementCalculator.partProfit(people, card), people, List.copyOf(warnings), history);
    }
    public void unavailable(Key key, String operation) {
        permissions.can(CostActions.key(operation));
        require(key, false);
        if ("CLOSED".equals(mapper.monthStatus(key.month()))) {
            throw ApiException.conflict("CLOSED_MONTH", "마감한 달은 변경할 수 없습니다.", "month");
        }
        Header header = mapper.header(key);
        if ("updatePartSettlementCard".equals(operation) && header != null && "CONFIRM".equals(header.stlm_stts_cd())) {
            throw ApiException.conflict("PART_ALREADY_CONFIRMED", "확정된 정산의 카드액은 변경할 수 없습니다.", null);
        }
        throw invalid("요청 본문·결과 스키마 승인 전에는 이 작업을 사용할 수 없습니다. 입력값은 보존됩니다.", null);
    }
    public void pendingConfirmation(Key key) {
        permissions.can(CostActions.key("confirmPartSettlement"));
        throw ApiException.conflict("VALIDATION_ERROR", "D-31/D-32 운영 승인 대기 — 정산 확정은 보류됩니다.", null);
    }
    private Header writable(Key key) {
        require(key, true);
        Header header = mapper.lockHeader(key);
        if ("CLOSED".equals(mapper.lockMonth(key.month()))) {
            throw ApiException.conflict("CLOSED_MONTH", "마감한 달은 변경할 수 없습니다.", "month");
        }
        if (YearMonth.parse(key.month()).getYear() != YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).getYear()) {
            throw ApiException.conflict("CLOSED_MONTH", "지난 해 정산은 조회만 가능합니다.", "month");
        }
        return header;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void saveCard(Key key, long amount, String remark) {
        if (amount < 0) throw invalid("카드액은 0 이상 원 단위여야 합니다.", null);
        if (remark != null && remark.length() > 500) throw invalid("비고는 500자 이하여야 합니다.", null);
        Header before = writable(key);
        if (before != null && "CONFIRM".equals(before.stlm_stts_cd())) {
            throw ApiException.conflict("PART_ALREADY_CONFIRMED", "확정된 정산은 수정할 수 없습니다.", null);
        }
        long id = before == null ? mapper.insertDraft(key) : before.stlm_id();
        long previous = before == null ? 0 : before.corp_card_amt();
        String oldRemark = before == null ? null : before.card_remark();
        if (before != null && previous == amount && Objects.equals(oldRemark, remark)) return;
        if (mapper.card(new CardWrite(id, amount, user.userId())) != 1) stale();
        mapper.audit(new Audit(id, "corp_card_amt", Long.toString(previous), Long.toString(amount), remark, user.userId()));
        if (!Objects.equals(oldRemark, remark)) {
            mapper.audit(new Audit(id, "card_remark", oldRemark, remark, remark, user.userId()));
        }
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void confirmCalculated(Key key) {
        if (!approval.approved()) pendingConfirmation(key);
        Header before = writable(key);
        if (before != null && "CONFIRM".equals(before.stlm_stts_cd())) stale();
        Month month = calculate(key);
        if (month.standard() == null) {
            throw ApiException.conflict("PART_STANDARD_MISSING", "해당 월 파트 기준이 없습니다.", null);
        }
        if (month.people().stream().anyMatch(p -> p.prft_amt() == null)) throw invalid("정산 원천 자료를 확인하세요.", null);
        long id = before == null ? mapper.insertDraft(key) : before.stlm_id();
        var snapshot = PartSettlementCalculator.snapshot(key, month.standard(), month.people(), month.corp_card_amt(), user.userId());
        mapper.deleteDetails(id);
        for (var person : month.people()) mapper.insertDetail(new DetailWrite(id, person));
        if (mapper.confirm(snapshot) != 1) stale();
        mapper.event(new Event(id, "CONFIRM", null, user.userId()));
        mapper.audit(new Audit(id, "stlm_stts_cd", "DRAFT", "CONFIRM", null, user.userId()));
        mapper.access(new Event(id, "SETTLE_CONFIRM", null, user.userId()));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancelCalculated(Key key, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw invalid("취소 사유는 1~500자 필수입니다.", null);
        Header before = writable(key);
        if (before == null) throw ApiException.notFound("정산 내역이 없습니다.");
        if (!"CONFIRM".equals(before.stlm_stts_cd())) stale();
        if (mapper.cancel(new CancelWrite(before.stlm_id(), reason, user.userId())) != 1) stale();
        mapper.event(new Event(before.stlm_id(), "CANCEL", reason, user.userId()));
        mapper.audit(new Audit(before.stlm_id(), "stlm_stts_cd", "CONFIRM", "DRAFT", reason, user.userId()));
        mapper.access(new Event(before.stlm_id(), "SETTLE_CANCEL", reason, user.userId()));
    }
    private static void stale() {
        throw ApiException.conflict("STALE_SETTLEMENT", "다른 요청에서 정산 상태가 변경되었습니다. 다시 조회하세요.", null);
    }
}
