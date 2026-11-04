package com.sicost.opportunities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.opportunities.OpportunityModels.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class OpportunityService {
    private final OpportunityMapper mapper;
    private final CurrentUser user;
    private final Clock clock;
    private final ObjectMapper json;
    public OpportunityService(OpportunityMapper mapper,CurrentUser user,Clock clock,ObjectMapper json) {
        this.mapper=mapper;this.user=user;this.clock=clock;this.json=json;
    }
    private LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    private BigDecimal criterion(String key) {
        var value=mapper.criterion(new Criteria(key,YearMonth.from(today()).toString()));
        if(value==null)throw OpportunityInput.error(key);return value;
    }
    public Page list(Map<String,String> parameters) {
        var q=OpportunityQuery.of(parameters);var all=mapper.matching(q);
        long weighted=0;for(var r:all)weighted=Math.addExact(weighted,weighted(r));
        long people=0;for(var r:all)people+=mapper.needs(r.oppt_id()).stream().mapToLong(NeedRow::psn_cnt).sum();
        var kpi=new Kpi(all.stream().filter(r->r.oppt_stts_cd().equals("OPEN")).count(),all.stream().mapToLong(Row::expc_amt).sum(),
            weighted,people,all.stream().filter(this::stale).count());
        return new Page(mapper.list(q).stream().map(this::detail).toList(),q.page(),q.size(),mapper.count(q),kpi);
    }
    private boolean stale(Row row) {
        return row.oppt_stts_cd().equals("OPEN")&&ChronoUnit.DAYS.between((row.updated_at()==null?row.created_at():row.updated_at()).toLocalDate(),today())>14;
    }
    /** Unpaged owner read, without quote/detail loading or approval writes. */
    public PipelineRead readPipeline(LocalDate date) {
        var rows=mapper.matching(OpportunityQuery.of(Map.of("oppt_stts_cd","OPEN")));
        var from=YearMonth.from(date).plusMonths(1);
        var to=from.plusMonths(2);
        var upcoming=rows.stream().filter(r->r.bgn_ym()!=null && r.bgn_ym().compareTo(from.toString())>=0
            && r.bgn_ym().compareTo(to.toString())<=0)
            .sorted(Comparator.comparing(Row::bgn_ym).thenComparing(Row::win_prblt_rt,Comparator.reverseOrder())
                .thenComparingLong(Row::oppt_id)).toList();
        long weighted=0;
        for(var r:rows)weighted=Math.addExact(weighted,weighted(r));
        int pending=(int)rows.stream().filter(r->"PENDING".equals(r.approval_state())).count();
        int stale=(int)rows.stream().filter(r->ChronoUnit.DAYS.between(
            (r.updated_at()==null?r.created_at():r.updated_at()).toLocalDate(),date)>14).count();
        return new PipelineRead(rows.size(),weighted,upcoming.size(),upcoming.stream().limit(3).toList(),pending,stale);
    }
    public static long weighted(Row row) {
        return BigDecimal.valueOf(row.expc_amt()).multiply(row.win_prblt_rt()).movePointLeft(2)
            .setScale(0,RoundingMode.DOWN).longValueExact();
    }
    private Row require(long id,boolean lock) {
        OpportunityInput.id(id);Row row=lock?mapper.lock(id):mapper.row(id);
        if(row==null)throw ApiException.notFound("기회가 없습니다.");return row;
    }
    public Detail get(long id){return detail(require(id,false));}
    private Detail detail(Row r) {
        var stored=mapper.needs(r.oppt_id());List<String> warnings=new ArrayList<>();
        boolean unknown=stored.isEmpty()||stored.stream().anyMatch(n->n.psn_mth_cst_amt()==0);
        String costStatus=unknown?"UNKNOWN":"VERIFIED";
        // Proof is retained as a server validation snapshot, never inferred from client text.
        if(unknown&&r.zero_cost_validation()!=null&&"APPROVED".equals(r.approval_state())) {
            var current=calculate(save(r,stored));
            if(!current.unknown()&&r.zero_cost_validation().equals(current.sourceSignature())&&Objects.equals(r.expc_sls_amt(),current.revenue())&&Objects.equals(r.expc_cst_amt(),current.cost()))costStatus="PROVEN_ZERO";
        }
        if(costStatus.equals("UNKNOWN"))warnings.add("원가 미확인: 별도 승인 필요");
        List<Need> needs=stored.stream().map(n->{List<String> w=OpportunityQuoteCalculator.outside(r.bgn_ym(),r.mth_cnt(),n.bgn_ym(),n.mth_cnt())?List.of("기회 기간 밖의 필요 인력"):Collections.emptyList();return new Need(n,w);}).toList();
        if(needs.stream().anyMatch(n->!n.warnings().isEmpty()))warnings.add("필요 인력 기간이 기회 기간 밖입니다. 실제 필요 기간으로 예측합니다.");
        if(stale(r))warnings.add("14일 초과 미갱신");
        long revenue=r.expc_sls_amt()==null?0:r.expc_sls_amt(),cost=r.expc_cst_amt()==null?0:r.expc_cst_amt();
        long difference=Math.subtractExact(revenue,r.expc_amt());
        var quote=new Quote(revenue,cost,r.expc_mrgn_rt(),cost,difference,OpportunityQuoteCalculator.rate(BigDecimal.valueOf(difference),r.expc_amt()),costStatus,r.approval_state(),
            "APPROVED".equals(r.approval_state())?revenue:null,List.copyOf(warnings));
        return new Detail(r.oppt_nm(),r.cust_ptnr_id(),r.bsn_part_id(),r.owner_user_id(),r.bsn_stg_cd(),r.win_prblt_rt(),r.expc_amt(),r.cntrct_ty_cd(),r.bgn_ym(),r.mth_cnt(),r.next_action(),r.next_action_dt(),r.rmrk_desc(),needs,r.amount_difference_reason(),r.oppt_id(),r.oppt_no(),r.oppt_stts_cd(),quote,mapper.approvals(r.oppt_id()),r.win_prj_id(),r.updated_at(),r.created_at(),stale(r),List.copyOf(warnings));
    }
    private Save save(Row r,List<NeedRow> needs) {
        return new Save(r.oppt_nm(),r.cust_ptnr_id(),r.bsn_part_id(),r.owner_user_id(),r.bsn_stg_cd(),r.win_prblt_rt(),r.expc_amt(),r.cntrct_ty_cd(),r.bgn_ym(),r.mth_cnt(),r.next_action(),r.next_action_dt(),r.rmrk_desc(),needs.stream().map(NeedRow::input).toList(),r.amount_difference_reason());
    }
    private Calculation calculate(Save input) {
        Map<String,Long> defaults=new HashMap<>();input.needs().stream().filter(n->n.expc_untprc_amt()==null).forEach(n->defaults.put(n.job_cd(),mapper.defaultPrice(n.job_cd())));
        return OpportunityQuoteCalculator.calculate(input,mapper.costs(today()),defaults,criterion("QUOTE_TARGET_MARGIN"));
    }
    private void choices(Save input) {
        if(!mapper.activeCustomer(input.cust_ptnr_id()))throw OpportunityInput.error("cust_ptnr_id");
        if(input.bsn_part_id()!=null&&!mapper.activePart(input.bsn_part_id()))throw OpportunityInput.error("bsn_part_id");
        if(!mapper.activeCode(new Code("CONTRACT_TYPE",input.cntrct_ty_cd())))throw OpportunityInput.error("cntrct_ty_cd");
        if(input.owner_user_id()!=null&&!Objects.equals(input.owner_user_id(),user.userId()))throw OpportunityInput.error("owner_user_id");
        for(int i=0;i<input.needs().size();i++) {
            var n=input.needs().get(i);
            if(!mapper.activeCode(new Code("JOB",n.job_cd())))throw OpportunityInput.error("needs["+i+"].job_cd");
            if(n.grd_cd()!=null&&!mapper.activeCode(new Code("GRADE",n.grd_cd())))throw OpportunityInput.error("needs["+i+"].grd_cd");
        }
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Detail create(Save input){return persist(null,input);}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Detail update(long id,Save input){return persist(require(id,true),input);}
    private Detail persist(Row before,Save input) {
        if(before!=null&&!"OPEN".equals(before.oppt_stts_cd()))throw ApiException.conflict("OPPORTUNITY_CLOSED","종료된 기회는 수정할 수 없습니다.",null);
        var old=before==null?Collections.<NeedRow>emptyList():mapper.needs(before.oppt_id());
        Set<Long> existing=new HashSet<>();old.forEach(n->existing.add(n.need_id()));
        for(var n:input.needs())if(n.need_id()!=null&&!existing.contains(n.need_id()))throw OpportunityInput.error("needs.need_id");
        choices(input);var calculated=calculate(input);
        if(calculated.revenue()!=input.expc_amt()&&input.amount_difference_reason()==null)throw OpportunityInput.error("amount_difference_reason");
        boolean same=before!=null&&OpportunityQuoteCalculator.sameNeeds(old,calculated.needs())&&Objects.equals(before.expc_sls_amt(),calculated.revenue())&&Objects.equals(before.expc_cst_amt(),calculated.cost());
        boolean retain=before!=null&&OpportunityQuoteCalculator.retains(before,old,calculated,criterion("QUOTE_APPROVAL_TOLERANCE"));
        if(before!=null&&before.zero_cost_validation()!=null&&!before.zero_cost_validation().equals(calculated.sourceSignature()))retain=false;
        boolean pending=calculated.unknown()||calculated.cost()==0||calculated.margin()==null||calculated.margin().compareTo(criterion("QUOTE_APPROVAL_RATE"))<0;
        if(before!=null&&"APPROVED".equals(before.approval_state())&&!retain)pending=true;
        String approval=retain?"APPROVED":pending?"PENDING":"NONE";
        String stage=input.bsn_stg_cd()==null?(before==null?"DISC":before.bsn_stg_cd()):input.bsn_stg_cd();
        if(Set.of("NEGO","PRE").contains(stage)&&!approval.equals("APPROVED")&&pending&&(before==null||!stage.equals(before.bsn_stg_cd())))
            throw ApiException.conflict("QUOTE_APPROVAL_REQUIRED","견적 승인 후 다음 단계로 이동할 수 있습니다.","bsn_stg_cd");
        BigDecimal probability=input.win_prblt_rt()!=null?input.win_prblt_rt():before!=null&&stage.equals(before.bsn_stg_cd())?before.win_prblt_rt():criterion("STAGE_PROB_"+stage);
        var effective=new Save(input.oppt_nm(),input.cust_ptnr_id(),input.bsn_part_id(),input.owner_user_id(),stage,probability,input.expc_amt(),input.cntrct_ty_cd(),input.bgn_ym(),input.mth_cnt(),input.next_action(),input.next_action_dt(),input.rmrk_desc(),input.needs(),input.amount_difference_reason());
        long id=before==null?mapper.nextId():before.oppt_id();
        long difference=Math.subtractExact(calculated.revenue(),input.expc_amt());
        var quote=new Quote(calculated.revenue(),calculated.cost(),calculated.margin(),calculated.cost(),difference,OpportunityQuoteCalculator.rate(BigDecimal.valueOf(difference),input.expc_amt()),calculated.unknown()||calculated.cost()==0?"UNKNOWN":"VERIFIED",approval,retain?calculated.revenue():null,calculated.warnings());
        var write=new Write(id,effective,probability,quote,retain&&same,user.userId());
        if(before==null)mapper.insert(write);else mapper.update(write);
        Set<Long> kept=new HashSet<>();
        for(var n:calculated.needs()) {
            var w=new NeedWrite(id,n.needId(),n.input(),n.cost(),n.target(),n.sequence());
            if(n.needId()==null)mapper.insertNeed(w);else {kept.add(n.needId());mapper.updateNeed(w);}
        }
        for(var n:old)if(!kept.contains(n.need_id())){mapper.releaseNeed(n.need_id());mapper.retireNeed(n.need_id());}
        if(approval.equals("PENDING")&&(before==null||!"PENDING".equals(before.approval_state())||!same))mapper.requestApproval(new Request(id,calculated.margin(),calculated.revenue(),user.userId()));
        mapper.appendHistory(new History(Long.toString(id),before==null?"INSERT":"UPDATE",before==null?null:serialize(save(before,old)),serialize(effective),user.userId()));
        return get(id);
    }
    private String serialize(Object value) {
        try{return json.writeValueAsString(value).replaceAll("(?<![0-9])[0-9]{2,3}-[0-9]{3,4}-[0-9]{4}","***-****-****").replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","***@***");}
        catch(JsonProcessingException e){throw new IllegalStateException("변경 이력을 기록할 수 없습니다.",e);}
    }
}
