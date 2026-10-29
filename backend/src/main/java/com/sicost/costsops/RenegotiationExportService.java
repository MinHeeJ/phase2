package com.sicost.costsops;

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
import static com.sicost.costsops.RenegotiationModels.*;

@Service
public class RenegotiationExportService {
    private final RenegotiationService service;
    private final RenegotiationMapper mapper;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public RenegotiationExportService(RenegotiationService service, RenegotiationMapper mapper,
            ExcelExportService excel, CurrentUser user, PermissionService permissions, Clock clock) {
        this.service = service;
        this.mapper = mapper;
        this.excel = excel;
        this.user = user;
        this.permissions = permissions;
        this.clock = clock;
    }
    // No HTTP binding. The integration owner must supply the approved ordered column mapping.
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public DownloadArtifact create(Query query, List<ExportColumn<Row>> approvedColumns) {
        if (approvedColumns == null || approvedColumns.isEmpty()) {
            throw new IllegalArgumentException("승인된 다운로드 열 매핑이 필요합니다.");
        }
        permissions.can(CostActions.key("UI-32.download"));
        var result = service.unpaged(query);
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        byte[] bytes = excel.create(query.toString(),user.name(),now,approvedColumns,result.items());
        String filename = "단가재협상_" + result.month() + ".xlsx";
        try {
            var metadata = new LinkedHashMap<String,Object>();
            metadata.put("filename",filename);
            metadata.put("conditions",query);
            metadata.put("rowCount",result.total());
            metadata.put("columns",approvedColumns.stream().map(c -> Map.of("key",c.key(),"sensitive",c.sensitive())).toList());
            mapper.access(new DownloadAccess(filename,new ObjectMapper().writeValueAsString(metadata),user.userId()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("다운로드 기록을 만들 수 없습니다.",ex);
        }
        return DownloadArtifact.workbook(filename,bytes);
    }
}
