package com.sicost.common;

import static org.assertj.core.api.Assertions.*;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.Sensitive;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.util.DefaultTempFileCreationStrategy;
import org.apache.poi.util.TempFile;
import org.apache.poi.util.TempFileCreationStrategy;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("org.apache.poi.util.TempFile")
class ExcelExportServiceTest {
    private final ExcelExportService service = new ExcelExportService();
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 23, 0, 1);
    @TempDir Path directory;
    private final List<Path> temporaryFiles = new ArrayList<>();

    record ExportRow(String name, String businessNumber, @Sensitive String account, long amount) {}

    @BeforeEach void trackStreamingFiles() {
        TempFile.setTempFileCreationStrategy(new TempFileCreationStrategy() {
            @Override public File createTempFile(String prefix, String suffix) throws IOException {
                Path file = Files.createTempFile(directory, prefix, suffix);
                temporaryFiles.add(file);
                return file.toFile();
            }
            @Override public File createTempDirectory(String prefix) throws IOException {
                return Files.createTempDirectory(directory, prefix).toFile();
            }
        });
    }

    @AfterEach void restoreTemporaryFileStrategy() {
        TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy());
    }

    private List<ExportColumn<ExportRow>> columns() {
        return List.of(
            new ExportColumn<>("name", "상호", false, false, ExportRow::name),
            new ExportColumn<>("businessNumber", "사업자번호", false, false, ExportRow::businessNumber),
            new ExportColumn<>("account", "계좌번호", true, false, ExportRow::account),
            new ExportColumn<>("amount", "영업비(1인·월)", false, true, ExportRow::amount));
    }

    private byte[] export(List<ExportRow> rows) {
        return service.create("구분=BOTH, 사용 여부=전체", "기본 사용자", now, columns(), rows);
    }

    private ExportRow row() {
        return new ExportRow("협력사 B", "123-45-67890", "001-23456789", 1500000L);
    }

    @Test void headingLayoutAndStylesSurviveStreaming() throws Exception {
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(export(List.of(row()))))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(1);
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("목록");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("조회 조건: 구분=BOTH, 사용 여부=전체");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("내려받은 사람: 기본 사용자 · 2026-10-23 00:01");
            assertThat((Object) sheet.getRow(2)).isNull();
            assertThat(sheet.getRow(3).getLastCellNum()).isEqualTo((short) 4);
            for (int column = 0; column < columns().size(); column++) {
                var cell = sheet.getRow(3).getCell(column);
                assertThat(cell.getStringCellValue()).isEqualTo(columns().get(column).title());
                assertThat(cell.getCellStyle().getFillForegroundColor()).isEqualTo(IndexedColors.GREY_25_PERCENT.getIndex());
                assertThat(cell.getCellStyle().getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
                assertThat(book.getFontAt(cell.getCellStyle().getFontIndex()).getBold()).isTrue();
            }
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 4);
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("협력사 B");
        }
    }

    @Test void identifiersAndSensitiveAccountRemainCompleteTextWhileMoneyIsNumeric() throws Exception {
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(export(List.of(row()))))) {
            var data = book.getSheetAt(0).getRow(4);
            assertThat(data.getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(data.getCell(1).getStringCellValue()).isEqualTo("123-45-67890");
            assertThat(data.getCell(1).getCellStyle().getDataFormatString()).isEqualTo("@");
            assertThat(data.getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(data.getCell(2).getStringCellValue()).isEqualTo("001-23456789");
            assertThat(data.getCell(2).getCellStyle().getDataFormatString()).isEqualTo("@");
            assertThat(data.getCell(3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(data.getCell(3).getNumericCellValue()).isEqualTo(1500000d);
            assertThat(data.getCell(3).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
        }
    }

    @Test void callerColumnOrderIsPreserved() throws Exception {
        var requested = List.of(columns().get(2), columns().get(0), columns().get(3));
        byte[] bytes = service.create("전체", "기본 사용자", now, requested, List.of(row()));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(3).getLastCellNum()).isEqualTo((short) 3);
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("계좌번호");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("상호");
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("001-23456789");
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("협력사 B");
            assertThat(sheet.getRow(4).getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
        }
    }

    @Test void everyRowBeyondTheStreamingWindowIsPresentAndOrdered() throws Exception {
        var rows = IntStream.range(0, 301)
            .mapToObj(index -> new ExportRow("거래처 " + index, "123-45-67890", "001-23456789", index))
            .toList();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(export(rows)))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(304);
            for (int index = 0; index < rows.size(); index++) {
                var data = sheet.getRow(index + 4);
                assertThat(data.getCell(0).getStringCellValue()).isEqualTo(rows.get(index).name());
                assertThat(data.getCell(3).getNumericCellValue()).isEqualTo((double) rows.get(index).amount());
            }
        }
    }

    @Test void emptyResultStillProducesHeadersWithoutInventingRows() throws Exception {
        byte[] bytes = export(List.of());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(3);
            assertThat((Object) sheet.getRow(4)).isNull();
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("상호");
        }
    }

    @Test void formulaLookingValuesAreLiteralStringsAndNullIsEmpty() throws Exception {
        var rows = List.of(new ExportRow("=WEBSERVICE(\"https://invalid.test\")", "123-45-67890", null, 0));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(export(rows)))) {
            var data = book.getSheetAt(0).getRow(4);
            assertThat(data.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(data.getCell(0).getStringCellValue()).isEqualTo(rows.get(0).name());
            assertThat(data.getCell(2).getStringCellValue()).isEmpty();
            assertThat(data.getCell(3).getNumericCellValue()).isZero();
            assertThat(book.getExternalLinksTable()).isEmpty();
        }
    }

    @Test void successDisposesStreamingTemporaryFiles() {
        export(List.of(row()));
        assertThat(temporaryFiles).isNotEmpty();
        for (Path file : temporaryFiles) assertThat(file).doesNotExist();
    }

    @Test void valueFailureAfterFlushingDisposesTemporaryFilesAndReturnsNoPartialWorkbook() {
        var failure = new IllegalStateException("파일 생성 중단");
        var failing = List.of(new ExportColumn<Integer>("name", "상호", false, false, index -> {
            if (index == 150) throw failure;
            return "거래처 " + index;
        }));
        assertThatThrownBy(() -> service.create("전체", "기본 사용자", now, failing,
            IntStream.range(0, 301).boxed().toList())).isSameAs(failure);
        assertThat(temporaryFiles).isNotEmpty();
        for (Path file : temporaryFiles) assertThat(file).doesNotExist();
    }
}
