package com.sicost.salesparts;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.salesparts.SalesPartModels.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class SalesPartService {
    private final SalesPartMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public SalesPartService(SalesPartMapper mapper,CurrentUser user,PermissionService permissions,Clock clock) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;
    }
    private SalesPartQuery query(PageRequest page) { return SalesPartQuery.of(page,LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))); }
    public PageResult<SalesPartListItem> listSalesParts(PageRequest page) {
        permissions.can("UI-15.read");
        var q=query(page);
        var rows=mapper.list(q).stream().map(p->{
            var standards=mapper.standards(p.bsn_part_id(),q.month());
            return new SalesPartListItem(p.bsn_part_id(),p.bsn_part_nm(),p.hq_yn(),p.use_yn(),p.sort_seq(),q.month(),current(standards),scheduled(standards),p.peopleCount(),p.projectCount());
        }).toList();
        return new PageResult<>(rows,mapper.count(),q.page(),q.size());
    }
    public SalesPartDetail getSalesPart(String id) {
        permissions.can("UI-15.read");
        if(id==null || !id.matches("[1-9][0-9]{0,18}") || new java.math.BigInteger(id).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))>0) throw ApiException.notFound("파트가 없습니다.");
        var q=query(new PageRequest(1,50,null));
        var p=mapper.part(id,q);
        if(p==null) throw ApiException.notFound("파트가 없습니다.");
        var standards=mapper.standards(id,q.month());
        String closed=mapper.lastClosed();
        return new SalesPartDetail(p.bsn_part_id(),p.bsn_part_nm(),p.hq_yn(),p.use_yn(),p.sort_seq(),q.month(),current(standards),scheduled(standards),p.peopleCount(),p.projectCount(),standards,closed,firstAllowed(closed,q.month()));
    }
    private static SalesPartStandard current(List<SalesPartStandard> rows) { return rows.stream().filter(s->"적용 중".equals(s.status())).findFirst().orElse(null); }
    private static List<SalesPartStandard> scheduled(List<SalesPartStandard> rows) { return rows.stream().filter(s->"예정".equals(s.status())).toList(); }
    private static String firstAllowed(String closed,String month) { return closed==null?month:YearMonth.parse(closed).plusMonths(1).toString(); }
    @Transactional
    public SalesPartDetail createSalesPart(SalesPartCreate input) {
        permissions.can("UI-15.create");
        String closed=mapper.lastClosed();
        String first=firstAllowed(closed,query(new PageRequest(1,50,null)).month());
        if(input.aply_bgn_ym().compareTo(first)<0) throw ApiException.conflict("CLOSED_MONTH",closed==null?"적용 시작월은 현재 월부터 고를 수 있습니다.":"적용 시작월은 마감된 달("+closed+") 다음 달부터 고를 수 있습니다.","aply_bgn_ym");
        if(mapper.nameExists(input.bsn_part_nm())) throw duplicate();
        String id;
        try { id=mapper.insertPart(new PartWrite(input,user.userId())); }
        catch(DuplicateKeyException ex) { throw duplicate(); }
        String standardId=mapper.insertStandard(new StandardWrite(id,input,user.userId()));
        mapper.appendHistory(new PartHistory("mst.bsn_part",id,input.bsn_part_nm(),user.userId()));
        String summary=input.aply_bgn_ym()+"부터 · 파트장 "+input.ldr_nm()+" · "+input.prft_dstrb_rt().stripTrailingZeros().toPlainString()+"% · "+String.format(java.util.Locale.KOREA,"%,d",input.bsn_fee_amt())+"원";
        mapper.appendHistory(new PartHistory("mst.bsn_part_std_hist",standardId,summary,user.userId()));
        return getSalesPart(id);
    }
    private static ApiException duplicate() { return ApiException.conflict("DUPLICATE_PART_NAME","같은 이름의 파트가 이미 있습니다.","bsn_part_nm"); }
}
