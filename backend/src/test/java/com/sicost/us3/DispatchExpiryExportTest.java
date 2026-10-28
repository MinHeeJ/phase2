package com.sicost.us3;

import static com.sicost.us3.DispatchExpiryRules.*;
import static org.assertj.core.api.Assertions.*;
import com.sicost.common.excel.ExcelExportService;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import java.util.stream.LongStream;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class DispatchExpiryExportTest {
    final LocalDate today = LocalDate.parse("2026-10-14");
    final Parameters params = new Parameters(24, List.of(60, 30));
    final DispatchExpiryExportService exporter = new DispatchExpiryExportService();

    @Test void expiryIsTypedExcelDateForEveryExportedRowAndNonDpIsBlank() throws Exception {
        var ids = LongStream.rangeClosed(1, 105).boxed().toList();
        Map<Long, Display> displays = new LinkedHashMap<>();
        for (long id : ids) displays.put(id, display(id == 105 ? "MO" : "DP", LocalDate.parse("2024-11-20"),
            LocalDate.parse("2026-12-31"), today, params));
        var columns = List.<ExcelExportService.ExportColumn<Long>>of(
            new ExcelExportService.ExportColumn<>("asgn_id", "투입ID", false, false, id -> "A-" + id),
            new ExcelExportService.ExportColumn<>("dispatch_expiry_dt", "파견 만료일", false, false,
                id -> displays.get(id).dispatch_expiry_dt()));
        byte[] original = new ExcelExportService().create("dispatchWithin60=false", "시험 사용자",
            today.atStartOfDay(), columns, ids);
        byte[] bytes = exporter.formatDates(original, ids, Long::longValue,
            new DispatchExpiryService.Snapshot(today, displays), 1);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(108);
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("파견 만료일");
            for (int index = 4; index < 108; index++) {
                var cell = sheet.getRow(index).getCell(1);
                assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
                assertThat(DateUtil.isCellDateFormatted(cell)).isTrue();
                assertThat(new DataFormatter().formatCellValue(cell)).isEqualTo("2026-11-19");
            }
            assertThat(sheet.getRow(108).getCell(1).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(sheet.getRow(108).getCell(0).getStringCellValue()).isEqualTo("A-105");
        }
    }

    @Test void emptyExportRetainsHeadersAndMetadata() throws Exception {
        var columns = List.<ExcelExportService.ExportColumn<Long>>of(
            new ExcelExportService.ExportColumn<>("dispatch_expiry_dt", "파견 만료일", false, false, id -> null));
        var original = new ExcelExportService().create("dispatchWithin60=true", "시험 사용자",
            today.atStartOfDay(), columns, List.of());
        var bytes = exporter.formatDates(original, List.<Long>of(), Long::longValue,
            new DispatchExpiryService.Snapshot(today, Map.of()), 0);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(3);
            assertThat(book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).contains("dispatchWithin60=true");
        }
    }
}
