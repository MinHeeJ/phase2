package com.sicost.projects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.api.DownloadArtifact;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.projects.ProjectProfitModels.*;

@Service
public class ProjectProfitExportService {
    private final ProjectProfitService projects;
    private final ProjectProfitMapper mapper;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public ProjectProfitExportService(ProjectProfitService projects, ProjectProfitMapper mapper,
            ExcelExportService excel, CurrentUser user, PermissionService permissions, Clock clock) {
        this.projects=projects;
        this.mapper=mapper;
        this.excel=excel;
        this.user=user;
        this.permissions=permissions;
        this.clock=clock;
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public List<Row> rows(Map<String,String> filters) {
        var query=new HashMap<>(filters);
        query.put("size","100");
        query.put("page","0");
        var result=new ArrayList<Row>();
        var first=projects.list(query);
        result.addAll(first.items());
        for(int page=1; (long)page*100<first.total(); page++) {
            query.put("page",Integer.toString(page));
            result.addAll(projects.list(query).items());
        }
        return List.copyOf(result);
    }
    // No HTTP binding: the contract owner must supply the approved column mapping first.
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public DownloadArtifact workbook(Map<String,String> filters, List<ExportColumn<Row>> approvedColumns) {
        permissions.can(CostActions.key("UI-04.download"));
        if (approvedColumns == null || approvedColumns.isEmpty()) {
            throw new ApiException(400,"VALIDATION_ERROR","다운로드 열 매핑 승인 대기 중입니다.",null);
        }
        var now=LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var rows=rows(filters);
        byte[] bytes=excel.create(filters.toString(),user.name(),now,approvedColumns,rows);
        String filename="프로젝트원가분석_"+now.toLocalDate().toString().replace("-","")+".xlsx";
        var metadata=Map.of("filename",filename,"conditions",filters,"rowCount",rows.size(),
            "columns",approvedColumns.stream().map(c->Map.of("key",c.key(),"sensitive",c.sensitive())).toList());
        try {
            mapper.download(new Download(filename,new ObjectMapper().writeValueAsString(metadata),user.userId()));
        } catch (JsonProcessingException ex) { throw new IllegalStateException("다운로드 기록을 만들 수 없습니다.",ex); }
        return DownloadArtifact.workbook(filename,bytes);
    }
}
