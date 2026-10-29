package com.sicost.billingsops;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.sicost.billingsops.BillingModels.*;

@RestController
public class BillingExportController {
    private final BillingGapService gaps;
    private final BillingReceivableService receivables;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final BillingMapper mapper;
    private final Clock clock;
    public BillingExportController(BillingGapService gaps,BillingReceivableService receivables,
            ExcelExportService excel,CurrentUser user,BillingMapper mapper,Clock clock) {
        this.gaps=gaps;this.receivables=receivables;this.excel=excel;this.user=user;this.mapper=mapper;this.clock=clock;
    }
    @GetMapping("/api/billings/gaps/export")
    @Transactional
    public ResponseEntity<byte[]> exportBillingGaps(@RequestParam Map<String,String> parameters) {
        var q=BillingInput.gaps(parameters);
        var columns=List.<ExportColumn<Gap>>of(
            new ExportColumn<>("project_name","프로젝트",false,false,Gap::project_name),
            new ExportColumn<>("month","귀속월",false,false,Gap::month),
            new ExportColumn<>("basis_supply_amt","투입 기준 금액",false,true,Gap::basis_supply_amt),
            new ExportColumn<>("confirmed_supply_amt","청구 공급가",false,true,Gap::confirmed_supply_amt),
            new ExportColumn<>("difference_amt","차이",false,true,Gap::difference_amt),
            new ExportColumn<>("judgment","판정",false,false,Gap::judgment),
            new ExportColumn<>("explanation","설명",false,false,Gap::explanation));
        return download("청구누락.xlsx",parameters,columns,gaps.filtered(q));
    }
    @GetMapping("/api/billings/overdue/export")
    @Transactional
    public ResponseEntity<byte[]> exportBillingOverdue(@RequestParam Map<String,String> parameters) {
        var q=BillingInput.overdue(parameters,clock);
        var columns=List.<ExportColumn<Overdue>>of(
            new ExportColumn<>("customer_name","고객사",false,false,r->r.billing().customer_name()),
            new ExportColumn<>("project_name","프로젝트",false,false,r->r.billing().project_name()),
            new ExportColumn<>("bill_no","청구번호",false,false,r->r.billing().bill_no()),
            new ExportColumn<>("receivable_amt","미수금",false,true,r->r.billing().receivable_amt()),
            new ExportColumn<>("rcpt_plan_dt","입금 예정일",false,false,r->r.billing().rcpt_plan_dt()),
            new ExportColumn<>("overdue_days","지난 일수",false,true,Overdue::overdue_days),
            new ExportColumn<>("stage","단계",false,true,Overdue::stage),
            new ExportColumn<>("recipient_roles","수신 역할",false,false,r->String.join(" · ",r.recipient_roles())),
            new ExportColumn<>("next_notification_date","다음 알림",false,false,Overdue::next_notification_date));
        return download("미수알림.xlsx",parameters,columns,receivables.filtered(q));
    }
    private <T> ResponseEntity<byte[]> download(String name,Map<String,String> parameters,
            List<ExportColumn<T>> columns,List<T> rows) {
        byte[] bytes=excel.create(parameters.toString(),user.name(),LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),columns,rows);
        mapper.download(new Download(name,"{\"rows\":"+rows.size()+"}",user.userId()));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString()).body(bytes);
    }
    public record Download(String name,String description,Long actor) {}
}
