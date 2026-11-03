package com.sicost.opportunitiesops;

import com.sicost.common.api.DownloadArtifact;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.CurrentUser;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.opportunitiesops.OpportunityLifecycleModels.*;

@Service
public class OpportunityExportService {
    private final OpportunityLifecycleMapper mapper;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final Clock clock;
    public OpportunityExportService(OpportunityLifecycleMapper mapper,ExcelExportService excel,CurrentUser user,Clock clock){this.mapper=mapper;this.excel=excel;this.user=user;this.clock=clock;}
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public DownloadArtifact export(Map<String,String> filters){
        var query=OpportunityLifecycleInput.export(filters);var rows=mapper.exportRows(query);
        List<ExportColumn<ExportRow>> columns=List.of(
            new ExportColumn<>("oppt_no","기회번호",false,false,ExportRow::oppt_no),new ExportColumn<>("oppt_nm","기회명",false,false,ExportRow::oppt_nm),
            new ExportColumn<>("customer","고객사",false,false,ExportRow::customer),new ExportColumn<>("part","영업 파트",false,false,ExportRow::part),
            new ExportColumn<>("bsn_stg_cd","단계",false,false,ExportRow::bsn_stg_cd),new ExportColumn<>("win_prblt_rt","수주 확률(%)",false,true,ExportRow::win_prblt_rt),
            new ExportColumn<>("expc_amt","예상 금액(공급가)",false,true,ExportRow::expc_amt),new ExportColumn<>("needed_people","필요 인력",false,true,ExportRow::needed_people),
            new ExportColumn<>("cntrct_ty_cd","계약유형",false,false,ExportRow::cntrct_ty_cd),new ExportColumn<>("bgn_ym","시작 예정월",false,false,ExportRow::bgn_ym),
            new ExportColumn<>("mth_cnt","개월",false,true,ExportRow::mth_cnt),new ExportColumn<>("next_action","다음 할 일",false,false,ExportRow::next_action),
            new ExportColumn<>("next_action_dt","다음 할 일 기한",false,false,ExportRow::next_action_dt),new ExportColumn<>("approval_state","견적 승인",false,false,ExportRow::approval_state),
            new ExportColumn<>("last_update","최근 갱신",false,false,ExportRow::last_update),new ExportColumn<>("oppt_stts_cd","상태",false,false,ExportRow::oppt_stts_cd),new ExportColumn<>("project","연결 프로젝트",false,false,ExportRow::project));
        var seoul=clock.withZone(ZoneId.of("Asia/Seoul"));String filename="영업파이프라인_"+LocalDate.now(seoul).format(DateTimeFormatter.BASIC_ISO_DATE)+".xlsx";
        byte[] bytes=excel.create(query.toString(),user.name(),LocalDateTime.now(seoul),columns,rows);
        mapper.download(new Download(filename,"조건 전체 · 표시열 "+columns.stream().map(ExportColumn::key).toList(),user.userId()));
        return DownloadArtifact.workbook(filename,bytes);
    }
}
