package com.sicost.salesparts;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.salesparts.SalesPartModels.*;

@Service
@Transactional(readOnly=true)
public class SalesPartService {
    private final SalesPartMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public SalesPartService(SalesPartMapper mapper,CurrentUser user,PermissionService permissions,Clock clock) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;
    }
    private LocalDate today() {return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    public PageResult<SalesPartListItem> list(PageRequest page) {
        permissions.can("UI-15.read");
        LocalDate date=today();String month=YearMonth.from(date).toString();
        SalesPartQuery query=SalesPartQuery.of(page,month,date);
        var rows=mapper.parts(query).stream().map(row->item(row,month,mapper.standards(Long.parseLong(row.bsn_part_id()),month))).toList();
        return new PageResult<>(rows,mapper.partCount(),query.page(),query.size());
    }
    public SalesPartDetail get(String id) {
        permissions.can("UI-15.read");
        long key;
        try {if(!id.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();key=Long.parseLong(id);}
        catch(NumberFormatException ex) {throw ApiException.notFound("영업 파트가 없습니다.");}
        LocalDate date=today();String month=YearMonth.from(date).toString();
        PartRow row=mapper.part(key,date);if(row==null) throw ApiException.notFound("영업 파트가 없습니다.");
        var standards=mapper.standards(key,month);var item=item(row,month,standards);String closed=mapper.lastClosed();
        return new SalesPartDetail(item.bsn_part_id(),item.bsn_part_nm(),item.hq_yn(),item.use_yn(),item.sort_seq(),month,item.currentStandard(),item.scheduledChanges(),item.peopleCount(),item.projectCount(),standards,closed,closed==null?null:YearMonth.parse(closed).plusMonths(1).toString());
    }
    private SalesPartListItem item(PartRow row,String month,List<SalesPartStandard> standards) {
        var current=standards.stream().filter(s->s.aply_bgn_ym().compareTo(month)<=0 && (s.aply_end_ym()==null || s.aply_end_ym().compareTo(month)>=0)).findFirst().orElse(null);
        var future=standards.stream().filter(s->s.aply_bgn_ym().compareTo(month)>0).toList();
        return new SalesPartListItem(row.bsn_part_id(),row.bsn_part_nm(),row.hq_yn(),row.use_yn(),row.sort_seq(),month,current,future,row.peopleCount(),row.projectCount());
    }
    @Transactional
    public SalesPartDetail create(SalesPartCreate input) {
        permissions.can("UI-15.create");
        String closed=mapper.lastClosed();
        if(closed!=null && input.aply_bgn_ym().compareTo(closed)<=0) throw ApiException.conflict("CLOSED_MONTH","적용 시작월은 마감된 달("+closed+") 다음 달부터 고를 수 있습니다.","aply_bgn_ym");
        if(mapper.nameExists(input.bsn_part_nm())) throw duplicate();
        Long id;
        try {id=mapper.insertPart(new PartWrite(input,user.userId()));}
        catch(DuplicateKeyException ex) {throw duplicate();}
        Long standard=mapper.insertStandard(new StandardWrite(id,input,user.userId()));
        mapper.appendHistory(new PartHistory("mst.bsn_part",id.toString(),input.bsn_part_nm(),user.userId()));
        String summary="적용 시작월: "+input.aply_bgn_ym()+", 파트장: "+input.ldr_nm()+", 배분율: "+input.prft_dstrb_rt().toPlainString()+", 영업비: "+input.bsn_fee_amt();
        mapper.appendHistory(new PartHistory("mst.bsn_part_std_hist",standard.toString(),summary,user.userId()));
        return get(id.toString());
    }
    private ApiException duplicate() {return ApiException.conflict("DUPLICATE_PART_NAME","같은 이름의 파트가 이미 있습니다.","bsn_part_nm");}
}
