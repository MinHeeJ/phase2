package com.sicost.us5;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.us5.MonthlyShortageModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MonthlyShortageExportService {
    private final MonthlyShortageService service;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public MonthlyShortageExportService(MonthlyShortageService service, ExcelExportService excel,
            CurrentUser user, PermissionService permissions, Clock clock) {
        this.service = service; this.excel = excel; this.user = user; this.permissions = permissions; this.clock = clock;
    }
    public ExportFile export(Query query) {
        permissions.can("UI-11.download");
        var columns = List.<ExcelExportService.ExportColumn<Row>>of(
            new ExcelExportService.ExportColumn<>("prj_nm", "프로젝트", false, false, Row::prj_nm),
            new ExcelExportService.ExportColumn<>("job_nm", "직무", false, false, Row::job_nm),
            new ExcelExportService.ExportColumn<>("plan_ym", "월", false, false, Row::plan_ym),
            new ExcelExportService.ExportColumn<>("planMm", "계획 MM", false, true, Row::planMm),
            new ExcelExportService.ExportColumn<>("actualMm", "실제·예정 MM", false, true, Row::actualMm),
            new ExcelExportService.ExportColumn<>("shortageMm", "부족", false, true, Row::shortageMm));
        byte[] original = excel.create("연도=" + query.year() + ", 프로젝트=" + query.projectId() + ", 직무=" + query.job(),
            user.name(), LocalDateTime.now(clock), columns, service.rows(query));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(original)); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var mmStyle = workbook.createCellStyle();
            mmStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            for (int r = 4; r <= sheet.getLastRowNum(); r++) {
                for (int c = 3; c <= 5; c++) sheet.getRow(r).getCell(c).setCellStyle(mmStyle);
            }
            workbook.write(out);
            var today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
            return new ExportFile("미충원직무_" + query.year() + "_" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx", out.toByteArray());
        } catch (IOException ex) { throw new IllegalStateException("엑셀 파일 생성에 실패했습니다.", ex); }
    }
}
