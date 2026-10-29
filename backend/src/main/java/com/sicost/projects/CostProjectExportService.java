package com.sicost.projects;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.projects.CostProjectModels.*;

@Service
public class CostProjectExportService {
    private final CostProjectService projects;
    private final CostProjectMapper mapper;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public CostProjectExportService(CostProjectService projects,CostProjectMapper mapper,
            ExcelExportService excel,CurrentUser user,PermissionService permissions,Clock clock) {
        this.projects=projects;this.mapper=mapper;this.excel=excel;
        this.user=user;this.permissions=permissions;this.clock=clock;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public byte[] download(Query query) {
        permissions.can(CostActions.PROJECT_DOWNLOAD);
        var columns=new ArrayList<ExportColumn<Row>>();
        for (var component:Row.class.getRecordComponents()) {
            columns.add(new ExportColumn<>(component.getName(),component.getName(),
                component.isAnnotationPresent(Sensitive.class),!component.getType().equals(String.class),row->{
                    try { return component.getAccessor().invoke(row); }
                    catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
                }));
        }
        var rows=projects.filtered(query).stream().map(Detail::project).toList();
        byte[] bytes=excel.create(query.toString(),user.name(),LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),columns,rows);
        mapper.download(new Download("프로젝트원가분석.xlsx","{\"rows\":"+rows.size()+"}",user.userId()));
        return bytes;
    }
}
