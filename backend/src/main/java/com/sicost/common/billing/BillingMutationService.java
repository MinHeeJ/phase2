package com.sicost.common.billing;

import com.sicost.common.api.ApiException;
import com.sicost.common.persistence.AssignmentConcurrencyService;
import com.sicost.common.security.CurrentUser;
import com.sicost.shared.SharedReadMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.common.billing.BillingSupportModels.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class BillingMutationService {
    private final BillingMutationMapper mapper;
    private final AssignmentConcurrencyService locks;
    private final SharedReadMapper shared;
    private final CurrentUser user;
    private final Clock clock;

    public BillingMutationService(BillingMutationMapper mapper, AssignmentConcurrencyService locks,
            SharedReadMapper shared, CurrentUser user, Clock clock) {
        this.mapper = mapper;
        this.locks = locks;
        this.shared = shared;
        this.user = user;
        this.clock = clock;
    }

    public void lockProject(long projectId) { locks.lockProject(projectId); }

    public State lock(long billId) {
        if (billId < 1) throw BillingRules.invalid("id");
        State reference = mapper.state(billId);
        if (reference == null) throw ApiException.notFound("청구가 없습니다.");
        locks.lockProject(reference.prj_id());
        if (reference.orgnl_bill_id() != null) {
            State original = mapper.lockBill(reference.orgnl_bill_id());
            if (original == null || original.prj_id() != reference.prj_id()) throw BillingRules.stale();
        }
        State locked = mapper.lockBill(billId);
        if (locked == null || locked.prj_id() != reference.prj_id()) throw BillingRules.stale();
        return locked;
    }

    public void requireOpen(YearMonth month, String field) {
        if (month == null) throw BillingRules.invalid(field);
        var rows = shared.monthClose(month.toString(), month.toString());
        for (var row : rows) BillingRules.requireOpen(month, row.status(), field);
    }

    public void requireOpen(State state) { requireOpen(state.effectiveMonth(), "month"); }

    public void requireReceiptMonths(LocalDate oldDate, LocalDate newDate) {
        if (oldDate == null && newDate == null) throw BillingRules.invalid("rcpt_dt");
        if (oldDate != null) requireOpen(YearMonth.from(oldDate), "rcpt_dt");
        if (newDate != null) requireOpen(YearMonth.from(newDate), "rcpt_dt");
    }

    public String nextNumber() {
        String prefix = BillingRules.numberPrefix(clock);
        mapper.numberLock(prefix);
        long sequence = BillingRules.add(mapper.maximumNumber(prefix), 1, "bill_no");
        return BillingRules.number(prefix, sequence);
    }

    public void audit(Audit change) {
        if (change == null || !Set.of("bill.bill", "bill.bill_dtl", "bill.rcpt", "bill.dspch_atnd")
                .contains(change.table())) throw BillingRules.invalid("table");
        if (change.type() == null || !Set.of("INSERT", "UPDATE", "DELETE").contains(change.type())) {
            throw BillingRules.invalid("type");
        }
        if (change.id() == null || change.id().isBlank()) throw BillingRules.invalid("id");
        mapper.audit(new Audit(change.table(), BillingRules.text(change.id(), 50, "id"), change.type(),
            BillingRules.text(change.field(), 50, "field"), BillingRules.text(change.before(), 4000, "before"),
            BillingRules.text(change.after(), 4000, "after"), BillingRules.text(change.reason(), 500, "reason"),
            user.userId()));
    }
}
