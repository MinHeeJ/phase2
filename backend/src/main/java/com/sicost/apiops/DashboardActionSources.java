package com.sicost.apiops;

import com.sicost.assignments.AssignmentService;
import com.sicost.billingsops.BillingGapService;
import com.sicost.billingsops.BillingModels.GapQuery;
import com.sicost.common.api.BillingPaging;
import com.sicost.contractchanges.ProjectContractChangeService;
import com.sicost.costsops.RenegotiationService;
import com.sicost.withdrawalsops.NextSlotService;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Independent owner transactions: an unavailable source is absent, never a fabricated zero. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class DashboardActionSources {
    private final AssignmentService assignments;
    private final NextSlotService slots;
    private final BillingGapService gaps;
    private final ClosingQueryService closing;
    private final RenegotiationService renegotiation;
    private final ProjectContractChangeService contracts;
    public DashboardActionSources(AssignmentService assignments,NextSlotService slots,BillingGapService gaps,
            ClosingQueryService closing,RenegotiationService renegotiation,ProjectContractChangeService contracts) {
        this.assignments=assignments;this.slots=slots;this.gaps=gaps;this.closing=closing;
        this.renegotiation=renegotiation;this.contracts=contracts;
    }
    public record Counts(Map<String,Integer> counts,Map<String,String> months,Integer withoutNext,Integer recommended) {}
    private static <T> T read(Supplier<T> source) {
        try {return source.get();} catch(RuntimeException ex) {return null;}
    }
    public Counts read(DashboardInput.Context c) {
        var counts=new HashMap<String,Integer>();var months=new HashMap<String,String>();
        var assignment=read(()->assignments.readWarnings(c.today()));
        if(assignment!=null) {
            counts.put("MM_EXCESS",assignment.excessPeople());
            counts.put("UNCONTRACTED_ASSIGNMENT",assignment.precontractAssignments());
        }
        months.put("MM_EXCESS",c.currentMonth().toString());months.put("UNCONTRACTED_ASSIGNMENT",c.currentMonth().toString());
        var recommendation=read(()->slots.readSummary(c.today()));
        if(recommendation!=null)counts.put("WITHDRAWAL",recommendation.withoutNext());
        String prior=c.currentMonth().minusMonths(1).toString();
        var state=read(()->closing.readState(prior));
        if(state!=null) {
            counts.put("UNCLOSED_PREVIOUS_MONTH","CLOSED".equals(state.status())?0:1);
            months.put("UNCLOSED_PREVIOUS_MONTH",prior);
            var missing=read(()->gaps.filtered(new GapQuery(prior,null,null,new BillingPaging(0,50,"project_id,asc"))));
            if(missing!=null) {
                counts.put("BILLING_GAP",(int)missing.stream().filter(g->!Set.of("맞음","맞음(무상)").contains(g.judgment()))
                    .map(g->g.project_id()).distinct().count());
                months.put("BILLING_GAP",prior);
            }
        }
        Integer low=read(()->renegotiation.readLowMarginCount(c.today()));
        if(low!=null)counts.put("LOW_MARGIN",low);
        Integer pending=read(contracts::readPendingAdditional);
        if(pending!=null)counts.put("UNCONFIRMED_ADDITIONAL_CONTRACT",pending);
        return new Counts(Map.copyOf(counts),Map.copyOf(months),recommendation==null?null:recommendation.withoutNext(),
            recommendation==null?null:recommendation.withRecommendation());
    }
}
