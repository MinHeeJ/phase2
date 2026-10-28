package com.sicost.billingsops;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.validation.*;
import com.sicost.billings.BillingModels.BillingFilter;
import com.sicost.billings.BillingModels.BillingRow;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service
@Transactional(readOnly = true)
public class BillingExportService {
    private final com.sicost.billings.BillingMapper mapper;
    private final BillingReceivableService receivables;
    private final BillingGapService gaps;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final Clock clock;
    public BillingExportService(com.sicost.billings.BillingMapper mapper, BillingReceivableService receivables,
            BillingGapService gaps, ExcelExportService excel, CurrentUser user, Clock clock) {
        this.mapper=mapper;this.receivables=receivables;this.gaps=gaps;this.excel=excel;this.user=user;this.clock=clock;
    }
    private static <T> ExportColumn<T> column(String key,String title,boolean numeric,Function<T,Object> value) {
        return new ExportColumn<>(key,title,false,numeric,value);
    }
    @Transactional
    public byte[] gaps(GapRequest request) {
        var rows=gaps.all(request);
        return workbook("청구 누락 점검 · "+gaps.month(request.month()),List.of(
            column("project_name","프로젝트",false,GapRow::project_name),
            column("month","귀속월",false,GapRow::month),
            column("basis_supply_amt","투입 기준 금액",true,GapRow::basis_supply_amt),
            column("confirmed_supply_amt","청구 공급가",true,GapRow::confirmed_supply_amt),
            column("difference_amt","차이",true,GapRow::difference_amt),
            column("judgment","판정",false,GapRow::judgment),
            column("explanation","설명",false,GapRow::explanation)),rows);
    }
    @Transactional
    public byte[] overdue(OverdueRequest request) {
        var rows=receivables.all(request);
        return workbook("미수 알림",List.of(
            column("bill_no","청구번호",false,r->r.billing().bill_no()),
            column("project_name","프로젝트",false,r->r.billing().project_name()),
            column("customer_name","고객사",false,r->r.billing().customer_name()),
            column("receivable_amt","미수금",true,r->r.billing().receivable_amt()),
            column("rcpt_plan_dt","입금 예정일",false,r->r.billing().rcpt_plan_dt()),
            column("overdue_days","지난 일수",true,OverdueRow::overdue_days),
            column("stage","단계",true,OverdueRow::stage),
            column("recipient_roles","수신 역할",false,r->String.join(", ",r.recipient_roles())),
            column("next_notification_date","다음 알림",false,OverdueRow::next_notification_date)),rows);
    }
    @Transactional
    public byte[] billings(Map<String,String> query) {
        int year;
        try {year=BillingQuery.year(query.containsKey("year")?Integer.valueOf(query.get("year")):null,clock);}
        catch(NumberFormatException ex){throw BillingValues.invalid("year");}
        String status=blank(query.get("status")),type=blank(query.get("type"));
        if(status!=null)BillingRules.status(status);
        if(type!=null)BillingRules.requireBillable(type);
        Long customer;
        try {customer=blank(query.get("customerId"))==null?null:Long.valueOf(query.get("customerId"));}
        catch(NumberFormatException ex){throw BillingValues.invalid("customerId");}
        if(customer!=null && customer<1)throw BillingValues.invalid("customerId");
        String search=blank(query.get("q"));
        if(search!=null && search.length()>200)throw BillingValues.invalid("q");
        var page=BillingValues.paging(0,50,query.get("sort"),BillingQuery.sorts(
            "bill_no","bill_dt","atrb_ym","project_name","sply_amt","vat_amt",
            "tot_amt","bill_stts_cd","rcpt_plan_dt","receivable_amt"),"bill_no,desc");
        String[] order=page.sort().split(",");
        var filtered=mapper.export(new BillingFilter(year,status,type,customer,search,0,50,order[0],order[1]));
        return workbook("청구 목록 · "+year,List.of(
            column("bill_no","청구번호",false,BillingRow::bill_no),column("project_name","프로젝트",false,BillingRow::project_name),
            column("contract_type","계약유형",false,BillingRow::contract_type),column("atrb_ym","귀속월",false,BillingRow::atrb_ym),
            column("bill_dt","청구일",false,BillingRow::bill_dt),column("sply_amt","공급가",true,BillingRow::sply_amt),
            column("vat_amt","부가세",true,BillingRow::vat_amt),column("tot_amt","합계",true,BillingRow::tot_amt),
            column("received_amt","입금액",true,BillingRow::received_amt),column("receivable_amt","미수금",true,BillingRow::receivable_amt),
            column("bill_stts_cd","상태",false,BillingRow::bill_stts_cd)),filtered);
    }
    private static String blank(String value) {
        return value==null || value.isBlank()?null:value;
    }
    private <T> byte[] workbook(String description,List<ExportColumn<T>> columns,List<T> rows) {
        byte[] bytes=excel.create(description,user.name(),LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),columns,rows);
        mapper.downloadAccess(description);
        return bytes;
    }
}
