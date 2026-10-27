package com.sicost.assignmentsops;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.validation.ProjectValues;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.sicost.assignmentsops.AssignmentOperationModels.*;

@Service
public class AssignmentExportService {
    private final AssignmentOperationService service;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final Clock clock;
    public AssignmentExportService(AssignmentOperationService service, ExcelExportService excel, CurrentUser user, Clock clock) {
        this.service = service;
        this.excel = excel;
        this.user = user;
        this.clock = clock;
    }
    public ExportFile export(Map<String, String> parameters) {
        var allowed = Set.of("year", "projectId", "job", "warning", "q", "sort");
        parameters.keySet().forEach(key -> { if (!allowed.contains(key)) throw AssignmentOperationInput.error(key); });
        int year;
        try { year = Integer.parseInt(parameters.getOrDefault("year", "")); }
        catch (NumberFormatException e) { throw AssignmentOperationInput.error("year"); }
        if (year < 1 || year > 9999) throw AssignmentOperationInput.error("year");
        Long project = null;
        if (parameters.containsKey("projectId")) {
            try { project = Long.valueOf(parameters.get("projectId")); }
            catch (NumberFormatException e) { throw AssignmentOperationInput.error("projectId"); }
            if (project < 1) throw AssignmentOperationInput.error("projectId");
        }
        String job = optional(parameters, "job", 30), warning = optional(parameters, "warning", 30);
        if (warning != null && !Set.of("OVER_MM", "PRE_CONTRACT", "ON_LEAVE", "DISPATCH_NONREG").contains(warning)) {
            throw AssignmentOperationInput.error("warning");
        }
        String sort = parameters.getOrDefault("sort", "prj_nm,asc;bgn_dt,asc");
        AssignmentOperationService.order(sort);
        var today = ProjectValues.today(clock);
        var query = new ExportQuery(year, project, job, warning, optional(parameters, "q", 200), sort,
            today, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        var columns = List.<ExcelExportService.ExportColumn<Detail>>of(
            new ExcelExportService.ExportColumn<>("asgn_no", "투입ID", false, false, d -> d.row().asgn_no()),
            new ExcelExportService.ExportColumn<>("prj_nm", "프로젝트", false, false, d -> d.row().prj_nm()),
            new ExcelExportService.ExportColumn<>("dev_nm", "이름", false, false, d -> d.row().dev_nm()),
            new ExcelExportService.ExportColumn<>("mng_no", "관리번호", false, false, d -> d.row().mng_no()),
            new ExcelExportService.ExportColumn<>("job_cd", "직무", false, false, d -> d.row().job_cd()),
            new ExcelExportService.ExportColumn<>("bgn_dt", "투입일", false, false, d -> d.row().bgn_dt()),
            new ExcelExportService.ExportColumn<>("end_dt", "철수일", false, false, d -> d.row().end_dt()),
            new ExcelExportService.ExportColumn<>("asgn_rt", "투입률", false, true, d -> d.row().asgn_rt()),
            new ExcelExportService.ExportColumn<>("asgn_se_cd", "투입 구분", false, false, d -> d.row().asgn_se_cd()),
            new ExcelExportService.ExportColumn<>("ext_psblty_cd", "연장 가능성", false, false, d -> d.row().ext_psblty_cd()),
            new ExcelExportService.ExportColumn<>("current_month_mm_qty", "이번달 MM", false, true, d -> d.row().current_month_mm_qty()),
            new ExcelExportService.ExportColumn<>("year_mm_qty", "연 MM", false, true, d -> d.row().year_mm_qty()));
        return new ExportFile("투입배정_" + year + "_" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx",
            excel.create(parameters.toString(), user.name(), LocalDateTime.now(clock), columns, service.exportRows(query)));
    }
    private static String optional(Map<String, String> parameters, String key, int limit) {
        String value = parameters.get(key);
        if (value == null || value.isBlank()) return null;
        if (value.length() > limit) throw AssignmentOperationInput.error(key);
        return value.trim();
    }
}
