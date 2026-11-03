package com.sicost.common;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.excel.ExcelExportService.ExportSheet;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ExcelExportServiceTest {
    final ExcelExportService excel=new ExcelExportService();
    final LocalDateTime now=LocalDateTime.of(2026,10,29,9,30);
    record Metric(Long amount,BigDecimal mm,Boolean shortage,String state,String message) {}

    List<ExportColumn<Metric>> columns() {
        return List.of(
            new ExportColumn<>("amount","금액",true,true,Metric::amount),
            new ExportColumn<>("mm","MM",false,true,Metric::mm),
            new ExportColumn<>("shortage","부족",false,false,Metric::shortage),
            new ExportColumn<>("state","상태",false,false,Metric::state),
            new ExportColumn<>("message","안내",false,false,Metric::message));
    }

    @Test void existingGenericCreateRetainsListSheetLayoutFormattingAndNullText() throws Exception {
        byte[] bytes=excel.create("기준월=2026-10","기본 사용자",now,columns(),
            List.of(new Metric(1234L,new BigDecimal("2.40"),false,"READY",null)));
        try (var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(1);
            var sheet=book.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("목록");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("조회 조건: 기준월=2026-10");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("내려받은 사람: 기본 사용자 · 2026-10-29 09:30");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("금액");
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)4);
            var row=sheet.getRow(4);
            assertThat(row.getCell(0).getNumericCellValue()).isEqualTo(1234);
            assertThat(row.getCell(0).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("false");
            assertThat(row.getCell(4).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(4).getStringCellValue()).isEmpty();
        }
    }

    @Test void multipleTypedSheetsKeepOrderSharedBasisNullZeroStatusAndFractionalMm() throws Exception {
        var missing=new Metric(null,null,null,"MISSING","조회 불가: 마감 자료 확인 필요");
        var ready=new Metric(0L,new BigDecimal("2.40"),false,"READY",null);
        var basis=new ExportSheet<>("기준",List.of(new ExportColumn<String>("basis","기준",false,false,v->v)),
            List.of("선택월 2026-10 · 오늘 기준 예측 · VAT 포함"));
        byte[] bytes=excel.createSheets("기준월=2026-10","기본 사용자",now,List.of(
            basis,new ExportSheet<>("KPI",columns(),List.of(missing,ready))));
        try (var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(2);
            assertThat(book.getSheetName(0)).isEqualTo("기준");
            assertThat(book.getSheetName(1)).isEqualTo("KPI");
            for (var sheet:book) {
                assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("조회 조건: 기준월=2026-10");
                assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).endsWith("2026-10-29 09:30");
            }
            var sheet=book.getSheetAt(1);
            for (int index=0;index<3;index++)
                assertThat(sheet.getRow(4).getCell(index).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(sheet.getRow(4).getCell(3).getStringCellValue()).isEqualTo(missing.state());
            assertThat(sheet.getRow(4).getCell(4).getStringCellValue()).isEqualTo(missing.message());
            assertThat(sheet.getRow(5).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(5).getCell(0).getNumericCellValue()).isZero();
            assertThat(sheet.getRow(5).getCell(1).getNumericCellValue()).isEqualTo(2.4);
            assertThat(sheet.getRow(5).getCell(1).getCellStyle().getDataFormatString()).contains(".");
            assertThat(sheet.getRow(5).getCell(2).getCellType()).isEqualTo(CellType.BOOLEAN);
            assertThat(sheet.getRow(5).getCell(2).getBooleanCellValue()).isFalse();
        }
    }

    @ParameterizedTest @ValueSource(strings={"=SUM(A1:A2)","+1+1","-1+1","@SUM(A1:A2)","https://example.invalid/private"})
    void callerTextIsNeverFormulaOrExternalLink(String value) throws Exception {
        var definition=new ExportSheet<>("문자열",List.of(new ExportColumn<String>("text","값",true,false,v->v)),List.of(value));
        try (var book=new XSSFWorkbook(new ByteArrayInputStream(excel.createSheets("조건","기본 사용자",now,List.of(definition))))) {
            var cell=book.getSheetAt(0).getRow(4).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).isEqualTo(value);
            assertThat(cell.getHyperlink()).isNull();
            assertThat(book.getExternalLinksTable()).isEmpty();
        }
    }

    @Test void emptyResultKeepsSheetHeadersWithoutInventingRows() throws Exception {
        try (var book=new XSSFWorkbook(new ByteArrayInputStream(excel.createSheets("조건","기본 사용자",now,
                List.of(new ExportSheet<Metric>("기회",columns(),List.of())))))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(3);
            assertThat((Object)book.getSheetAt(0).getRow(4)).isNull();
        }
    }

    @ParameterizedTest @ValueSource(strings={"같은이름","SAME"})
    void duplicateSheetNamesRejectEntireWorkbook(String name) {
        var sheet=new ExportSheet<Metric>(name,columns(),List.of());
        assertThatIllegalArgumentException().isThrownBy(()->excel.createSheets("조건","기본 사용자",now,List.of(sheet,sheet)));
    }

    @ParameterizedTest @ValueSource(strings={"bad/name","bad[name]","12345678901234567890123456789012"})
    void invalidSheetNamesAreNotSilentlyRenamedOrTruncated(String name) {
        assertThatIllegalArgumentException().isThrownBy(()->excel.createSheets("조건","기본 사용자",now,
            List.of(new ExportSheet<Metric>(name,columns(),List.of()))));
    }

    @Test void workbookRequiresAtLeastOneSheet() {
        assertThatIllegalArgumentException().isThrownBy(()->excel.createSheets("조건","기본 사용자",now,List.of()));
        assertThatIllegalArgumentException().isThrownBy(()->excel.createSheets("조건","기본 사용자",now,null));
    }

    @Test void projectionFailureDoesNotReturnPartialWorkbook() {
        var column=new ExportColumn<String>("value","값",false,false,v->{throw new IllegalStateException("원천 투영 실패");});
        assertThatThrownBy(()->excel.createSheets("조건","기본 사용자",now,
            List.of(new ExportSheet<>("KPI",List.of(column),List.of("원천")))))
            .isInstanceOf(IllegalStateException.class).hasMessage("원천 투영 실패");
    }
}
