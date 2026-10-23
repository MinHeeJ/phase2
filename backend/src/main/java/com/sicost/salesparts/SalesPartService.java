package com.sicost.salesparts;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.List;
import java.text.NumberFormat;
import java.util.Locale;
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
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    public PageResult<SalesPartListItem> listSalesParts(PageRequest page) {
        permissions.can("UI-15.read");
        SalesPartQuery q=SalesPartQuery.of(page,today());
        var items=mapper.parts(q).stream().map(p->{
            var standards=mapper.standards(new PartRead(Long.valueOf(p.bsn_part_id()),q.month(),q.today()));
            return new SalesPartListItem(p.bsn_part_id(),p.bsn_part_nm(),p.hq_yn(),p.use_yn(),p.sort_seq(),q.month(),current(standards),scheduled(standards),p.peopleCount(),p.projectCount());
        }).toList();
        return new PageResult<>(items,mapper.count(),q.page(),q.size());
    }
    public SalesPartDetail getSalesPart(String id) {
        permissions.can("UI-15.read");
        LocalDate date=today();
        PartRead q=new PartRead(SalesPartInput.id(id),YearMonth.from(date).toString(),date);
        PartRow row=mapper.part(q);
        if(row==null) throw ApiException.notFound("파트가 없습니다.");
        var standards=mapper.standards(q);
        String closed=mapper.lastClosed();
        return new SalesPartDetail(row.bsn_part_id(),row.bsn_part_nm(),row.hq_yn(),row.use_yn(),row.sort_seq(),q.month(),current(standards),scheduled(standards),row.peopleCount(),row.projectCount(),standards,closed,closed==null?null:YearMonth.parse(closed).plusMonths(1).toString());
    }
    private static SalesPartStandard current(List<SalesPartStandard> rows) { return rows.stream().filter(s->s.status().equals("적용 중")).findFirst().orElse(null); }
    private static List<SalesPartStandard> scheduled(List<SalesPartStandard> rows) { return rows.stream().filter(s->s.status().equals("예정")).toList(); }
    @Transactional
    public SalesPartDetail createSalesPart(SalesPartCreate input) {
        permissions.can("UI-15.create");
        mapper.lockParts();
        if(mapper.nameExists(input.bsn_part_nm())) throw duplicate();
        String closed=mapper.lastClosed();
        if(closed!=null && input.aply_bgn_ym().compareTo(closed)<=0) throw ApiException.conflict("CLOSED_MONTH","적용 시작월은 마감된 달("+closed+") 다음 달부터 고를 수 있습니다.","aply_bgn_ym");
        Long id;
        try { id=mapper.insertPart(new PartWrite(input,user.userId())); }
        catch(DuplicateKeyException ex) { throw duplicate(); }
        Long standardId=mapper.insertStandard(new StandardWrite(id,input,user.userId()));
        mapper.appendHistory(new PartHistory("mst.bsn_part",id.toString(),input.bsn_part_nm(),user.userId()));
        String summary=input.aply_bgn_ym()+"부터 · 파트장 "+input.ldr_nm()+" · "+input.prft_dstrb_rt().stripTrailingZeros().toPlainString()+"% · "+NumberFormat.getIntegerInstance(Locale.KOREA).format(input.bsn_fee_amt())+"원";
        mapper.appendHistory(new PartHistory("mst.bsn_part_std_hist",standardId.toString(),summary,user.userId()));
        return getSalesPart(id.toString());
    }
    private static ApiException duplicate() { return ApiException.conflict("DUPLICATE_PART_NAME","같은 이름의 파트가 이미 있습니다.","bsn_part_nm"); }
}
