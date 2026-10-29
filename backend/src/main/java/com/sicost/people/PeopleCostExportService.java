package com.sicost.people;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.people.PeopleCostModels.*;

@Service
public class PeopleCostExportService {
    private final PeopleCostService people;
    private final ExcelExportService excel;
    private final PeopleCostMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public PeopleCostExportService(PeopleCostService people,ExcelExportService excel,PeopleCostMapper mapper,
            CurrentUser user,PermissionService permissions,Clock clock) {
        this.people=people; this.excel=excel; this.mapper=mapper;
        this.user=user; this.permissions=permissions; this.clock=clock;
    }
    // Service-only extension point: HTTP binding and ordered B~W mapping must be approved by the contract owner.
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public DownloadArtifact export(Map<String,String> input,List<ExportColumn<Row>> approvedColumns) {
        permissions.can(CostActions.key("UI-03.download"));
        if(approvedColumns==null || approvedColumns.size()!=22 || approvedColumns.stream().map(ExportColumn::key).distinct().count()!=22) {
            throw ApiException.conflict("VALIDATION_ERROR","승인된 B~W 열 매핑이 필요합니다.",null);
        }
        LocalDateTime now=LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var query=PeopleCostQuery.parse(input,YearMonth.from(now));
        var prepared=people.prepare(query);
        byte[] bytes=excel.create(new TreeMap<>(input).toString(),user.name(),now,approvedColumns,prepared.rows());
        String filename="개인원가수익표_"+query.year()+".xlsx";
        Map<String,Object> metadata=new LinkedHashMap<>();
        metadata.put("filename",filename); metadata.put("conditions",input);
        metadata.put("columns",approvedColumns.stream().map(c->Map.of("key",c.key(),"sensitive",c.sensitive())).toList());
        metadata.put("rowCount",prepared.rows().size());
        try { mapper.access(new DownloadAccess(filename,new ObjectMapper().writeValueAsString(metadata),user.userId())); }
        catch(JsonProcessingException ex) { throw new IllegalStateException("다운로드 기록을 만들 수 없습니다.",ex); }
        return DownloadArtifact.workbook(filename,bytes);
    }
}
