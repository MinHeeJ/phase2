package com.sicost.board;

import com.sicost.common.api.DownloadArtifact;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.CurrentUser;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.board.WorkforceBoardModels.*;

@Service
public class WorkforceBoardExportService {
    private final WorkforceBoardService service;
    private final WorkforceBoardMapper mapper;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final Clock clock;
    public WorkforceBoardExportService(WorkforceBoardService service, WorkforceBoardMapper mapper,
            ExcelExportService excel, CurrentUser user, Clock clock) {
        this.service=service; this.mapper=mapper; this.excel=excel; this.user=user; this.clock=clock;
    }
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public DownloadArtifact exportWorkforceBoard(Map<String,String> values) {
        var query=WorkforceBoardInput.parse(values,true);
        var snapshot=service.snapshot(query);
        var columns=new ArrayList<ExportColumn<BoardRow>>();
        columns.add(new ExportColumn<>("dev_nm","이름",false,false,BoardRow::dev_nm));
        columns.add(new ExportColumn<>("mng_no","관리번호",false,false,BoardRow::mng_no));
        columns.add(new ExportColumn<>("job_cd","직무",false,false,BoardRow::job_cd));
        columns.add(new ExportColumn<>("grd_cd","등급",false,false,BoardRow::grd_cd));
        for(int i=0;i<12;i++) {
            int index=i;
            columns.add(new ExportColumn<>("month_"+(i+1),(i+1)+"월",false,false,r -> display(r.months().get(index))));
        }
        var seoul=clock.withZone(ZoneId.of("Asia/Seoul"));
        String filename="인력운영보드_"+LocalDate.now(seoul).format(DateTimeFormatter.BASIC_ISO_DATE)+".xlsx";
        byte[] bytes=excel.create(values.toString(),user.name(),LocalDateTime.now(seoul),columns,snapshot.rows());
        mapper.download(new BoardDownload(filename,values.toString(),user.userId()));
        return DownloadArtifact.workbook(filename,bytes);
    }
    private static String display(Cell c) {
        return c.status()+" · 확정 "+c.confirmed_mm()+" MM · 가배정 "+c.temporary_mm()+" MM · "+c.assignment_count()+"건"
            +(c.withdrawal_date()==null?"":" · 철수 "+c.withdrawal_date())
            +(c.warnings().isEmpty()?"":" · "+String.join(" / ",c.warnings()));
    }
}
