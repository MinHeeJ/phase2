package com.sicost.dashboardops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sicost.common.api.ApiException;
import com.sicost.common.dashboard.*;
import com.sicost.common.security.*;
import java.time.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DashboardExportService {
    private final ObjectProvider<DashboardReadSource> source;
    private final DashboardWorkbook workbook;
    private final DashboardAuditService audit;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public DashboardExportService(ObjectProvider<DashboardReadSource> source,DashboardWorkbook workbook,
            DashboardAuditService audit,CurrentUser user,PermissionService permissions,Clock clock) {
        this.source=source;this.workbook=workbook;this.audit=audit;this.user=user;this.permissions=permissions;this.clock=clock;
    }
    public record Download(String filename,@Sensitive byte[] bytes) {}

    @Sensitive
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Download download(String month) {
        var context=DashboardReadContext.capture(month,clock);
        permissions.can(DashboardActions.DOWNLOAD);
        var reader=source.getIfAvailable();
        if (reader==null)
            throw new ApiException(500,"DASHBOARD_SOURCE_UNAVAILABLE","대시보드 조회 원천이 연결되지 않았습니다.",null);
        JsonNode data=reader.read(context);
        if (data==null || !data.isObject()
                || !context.month().toString().equals(data.path("basisMonth").asText())
                || !context.asOf().toInstant().equals(OffsetDateTime.parse(data.path("asOf").asText()).toInstant()))
            throw new IllegalStateException("대시보드 조회 기준이 일치하지 않습니다.");
        String filename="dashboard_"+context.month()+".xlsx";
        byte[] bytes=workbook.create(data,user.name());
        var metadata=JsonNodeFactory.instance.objectNode();
        metadata.put("basisMonth",context.month().toString()); metadata.put("asOf",context.asOf().toString());
        metadata.put("columns","path,value,type,availability,reason");
        var sheets=metadata.putArray("sheets");DashboardWorkbook.sheetNames().forEach(sheets::add);
        audit.record(filename,metadata.toString());
        return new Download(filename,bytes);
    }
}
