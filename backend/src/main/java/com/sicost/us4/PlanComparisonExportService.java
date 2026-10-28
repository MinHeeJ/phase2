package com.sicost.us4;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.CurrentUser;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.us4.PlanComparisonModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PlanComparisonExportService {
    private final PlanComparisonService service;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final Clock clock;
    public PlanComparisonExportService(PlanComparisonService service, ExcelExportService excel,
                                       CurrentUser user, Clock clock) {
        this.service = service;
        this.excel = excel;
        this.user = user;
        this.clock = clock;
    }
    public ExportFile export(Map<String, String> values) {
        Query query = PlanComparisonInput.query(values, true);
        Snapshot snapshot = service.exportRows(query);
        List<ExportColumn<Row>> columns = List.of(
            new ExportColumn<>("prj_nm", "프로젝트", false, false, Row::prj_nm),
            new ExportColumn<>("job_nm", "직무", false, false, Row::job_nm),
            new ExportColumn<>("planMm", query.year() + "년 계획 MM", false, true, Row::planMm),
            new ExportColumn<>("actualMm", "실제·예정 MM", false, true, Row::actualMm),
            new ExportColumn<>("gap", "차이", false, false, row -> row.gap().equals("MATCH") ? "맞음"
                : (row.gap().equals("SHORT") ? "부족 " : "초과 ") + row.gapMm().abs().toPlainString()));
        byte[] original = excel.create(values.toString(), user.name(), LocalDateTime.now(clock), columns, snapshot.data().rows());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(original));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var numberStyle = workbook.createCellStyle();
            numberStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            for (int i = 4; i <= sheet.getLastRowNum(); i++) {
                sheet.getRow(i).getCell(2).setCellStyle(numberStyle);
                sheet.getRow(i).getCell(3).setCellStyle(numberStyle);
            }
            var footer = sheet.createRow(4 + snapshot.data().rows().size());
            footer.createCell(0).setCellValue("합계");
            footer.createCell(2).setCellValue(snapshot.data().totals().planMm().doubleValue());
            footer.createCell(3).setCellValue(snapshot.data().totals().actualMm().doubleValue());
            footer.getCell(2).setCellStyle(numberStyle);
            footer.getCell(3).setCellStyle(numberStyle);
            workbook.write(output);
            return new ExportFile("계획대비투입_" + query.year() + "_"
                + snapshot.today().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx", output.toByteArray());
        } catch (IOException ex) { throw new IllegalStateException("엑셀 파일 생성에 실패했습니다.", ex); }
    }
}
