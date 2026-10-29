package com.sicost.billingsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.DownloadArtifact;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.billingsops.BillingReviewModels.*;

@Service
public class BillingReviewDownloadService {
    private final BillingReviewService review;
    private final BillingReviewMapper mapper;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public BillingReviewDownloadService(BillingReviewService review,BillingReviewMapper mapper,
            ExcelExportService excel,CurrentUser user,PermissionService permissions,Clock clock) {
        this.review=review;this.mapper=mapper;this.excel=excel;this.user=user;this.permissions=permissions;this.clock=clock;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public DownloadArtifact gaps(Map<String,String> filters) {
        permissions.can(BillingActions.downloadKey());
        var query=new HashMap<>(filters);query.put("size","100");query.put("page","0");
        var rows=new ArrayList<GapRow>();long total;
        do {var page=review.gaps(query);rows.addAll(page.items());total=page.total();
            query.put("page",Integer.toString(Integer.parseInt(query.get("page"))+1));
        } while(rows.size()<total);
        List<ExportColumn<GapRow>> columns=List.of(
            new ExportColumn<>("project_name","프로젝트",false,false,GapRow::project_name),
            new ExportColumn<>("month","귀속월",false,false,GapRow::month),
            new ExportColumn<>("basis_supply_amt","투입 기준 금액",false,true,GapRow::basis_supply_amt),
            new ExportColumn<>("confirmed_supply_amt","청구 공급가",false,true,GapRow::confirmed_supply_amt),
            new ExportColumn<>("difference_amt","차이",false,true,GapRow::difference_amt),
            new ExportColumn<>("judgment","판정",false,false,GapRow::judgment),
            new ExportColumn<>("explanation","설명",false,false,GapRow::explanation));
        return workbook("청구누락점검.xlsx",filters,columns,rows);
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public DownloadArtifact overdue(Map<String,String> filters) {
        permissions.can(BillingActions.downloadKey());
        var query=new HashMap<>(filters);query.put("size","100");query.put("page","0");
        var rows=new ArrayList<OverdueRow>();long total;
        do {var page=review.overdue(query);rows.addAll(page.items());total=page.total();
            query.put("page",Integer.toString(Integer.parseInt(query.get("page"))+1));
        } while(rows.size()<total);
        List<ExportColumn<OverdueRow>> columns=List.of(
            new ExportColumn<>("customer_name","고객사",false,false,r->r.billing().customer_name()),
            new ExportColumn<>("project_name","프로젝트",false,false,r->r.billing().project_name()),
            new ExportColumn<>("bill_no","청구번호",false,false,r->r.billing().bill_no()),
            new ExportColumn<>("receivable_amt","미수금",false,true,r->r.billing().receivable_amt()),
            new ExportColumn<>("rcpt_plan_dt","입금 예정일",false,false,r->r.billing().rcpt_plan_dt()),
            new ExportColumn<>("overdue_days","지난 일수",false,true,OverdueRow::overdue_days),
            new ExportColumn<>("stage","단계",false,true,OverdueRow::stage),
            new ExportColumn<>("recipient_roles","수신 역할",false,false,r->String.join(" / ",r.recipient_roles())),
            new ExportColumn<>("next_notification_date","다음 알림",false,false,OverdueRow::next_notification_date));
        return workbook("미수알림.xlsx",filters,columns,rows);
    }
    private <T> DownloadArtifact workbook(String filename,Map<String,String> filters,List<ExportColumn<T>> columns,List<T> rows) {
        var now=LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        byte[] bytes=excel.create(filters.toString(),user.name(),now,columns,rows);
        try {
            String metadata=new ObjectMapper().writeValueAsString(Map.of("conditions",filters,"rowCount",rows.size(),
                "columns",columns.stream().map(c->Map.of("key",c.key(),"sensitive",c.sensitive())).toList()));
            mapper.access(new BillingReviewMapper.ReviewDownload(filename,metadata,user.userId()));
        }catch(JsonProcessingException e){throw new IllegalStateException("다운로드 기록을 저장할 수 없습니다.",e);}
        return DownloadArtifact.workbook(filename,bytes);
    }
}
