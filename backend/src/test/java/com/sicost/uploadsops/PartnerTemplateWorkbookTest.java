package com.sicost.uploadsops;

import static com.sicost.uploadsops.UploadModels.*;
import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("org.apache.poi.util.TempFile")
class PartnerTemplateWorkbookTest {
    final UploadWorkbookWriter writer=new UploadWorkbookWriter();
    @TempDir Path directory;
    final List<Path> temporaryFiles=new ArrayList<>();
    final List<UploadCode> codes=List.of(
        new UploadCode("PARTNER_KIND","CUST","고객사"),new UploadCode("PARTNER_KIND","VEND","협력사"),
        new UploadCode("PARTNER_KIND","BOTH","둘 다"),new UploadCode("INDUSTRY","SI","SI"),
        new UploadCode("BANK","KB","국민은행"));

    @BeforeEach void trackStreamingFiles() {
        TempFile.setTempFileCreationStrategy(new TempFileCreationStrategy() {
            public File createTempFile(String prefix,String suffix)throws IOException {
                Path file=Files.createTempFile(directory,prefix,suffix);temporaryFiles.add(file);return file.toFile();
            }
            public File createTempDirectory(String prefix)throws IOException {return Files.createTempDirectory(directory,prefix).toFile();}
        });
    }
    @AfterEach void restoreTemporaryFileStrategy() {TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy());}

    @Test void threeSheetsKeepInstructionsRequiredHeadersGreyExampleAndFreezePane()throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(writer.template(codes)))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(3);
            assertThat(book.getSheetName(0)).isEqualTo("거래처");
            assertThat(book.getSheetName(1)).isEqualTo("입력 방법");
            assertThat(book.getSheetName(2)).isEqualTo("목록");
            var sheet=book.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("* 표시는 필수입니다. 4행 제목은 바꾸지 마세요.");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("고객사·둘 다: 세금계산서 이메일 필수 / 협력사·둘 다: 은행·계좌번호·예금주 필수");
            assertThat((Object)sheet.getRow(2)).isNull();
            assertThat(sheet.getRow(3).getLastCellNum()).isEqualTo((short)12);
            for(int column=0;column<12;column++) {
                var title=sheet.getRow(3).getCell(column);
                assertThat(title.getStringCellValue()).isEqualTo(PartnerUploadForm.HEADERS.get(column)+(column<3?" *":""));
                assertThat(book.getFontAt(title.getCellStyle().getFontIndex()).getBold()).isTrue();
                var example=sheet.getRow(4).getCell(column).getCellStyle();
                assertThat(example.getFillForegroundColor()).isEqualTo(IndexedColors.GREY_25_PERCENT.getIndex());
                assertThat(example.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
            }
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).startsWith("예시)");
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)4);
            assertThat(book.getSheetAt(1).getRow(0).getCell(0).getStringCellValue()).contains("예시)","제외");
        }
    }
    @Test void identifiersAndContactUseTextFormatWithoutLockingTheInputSheet()throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(writer.template(codes)))) {
            var sheet=book.getSheetAt(0);
            for(int column:new int[]{2,6,9}) {
                assertThat(sheet.getColumnStyle(column).getDataFormatString()).isEqualTo("@");
                assertThat(sheet.getRow(4).getCell(column).getCellStyle().getDataFormatString()).isEqualTo("@");
            }
            assertThat(sheet.getProtect()).isFalse();
            assertThat(book.getSheet("목록").getProtect()).isTrue();
        }
    }
    @Test void eachDropdownReferencesItsOwnDatabaseCodeNamesThroughNamedRanges()throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(writer.template(codes)))) {
            var validations=book.getSheetAt(0).getDataValidations();
            assertThat(validations).hasSize(3);
            List<String> groups=List.of("PARTNER_KIND","INDUSTRY","BANK");int[] columns={0,4,8};
            for(int i=0;i<groups.size();i++) {
                var validation=validations.get(i);var range=validation.getRegions().getCellRangeAddresses()[0];
                assertThat(validation.getValidationConstraint().getFormula1()).isEqualTo("Upload_"+groups.get(i));
                assertThat(range.getFirstColumn()).isEqualTo(columns[i]);assertThat(range.getLastColumn()).isEqualTo(columns[i]);
                assertThat(range.getFirstRow()).isEqualTo(4);assertThat(range.getLastRow()).isEqualTo(10004);
                assertThat(validation.getShowErrorBox()).isTrue();
                String group=groups.get(i);var options=codes.stream().filter(c->c.group().equals(group)).toList();
                String letter=String.valueOf((char)('A'+i));
                assertThat(book.getName("Upload_"+groups.get(i)).getRefersToFormula()).isEqualTo("'목록'!$"+letter+"$2:$"+letter+"$"+(options.size()+1));
                for(int row=0;row<options.size();row++)assertThat(book.getSheet("목록").getRow(row+1).getCell(i).getStringCellValue()).isEqualTo(options.get(row).name());
            }
        }
    }


    @Test void longUnequalCodeListsSurviveStreamingFlushWithoutMissingHeadersOrRows()throws Exception {
        var options=new ArrayList<>(codes);
        for(int i=0;i<301;i++)options.add(new UploadCode("INDUSTRY","I"+i,"업종 "+i));
        for(int i=0;i<120;i++)options.add(new UploadCode("BANK","B"+i,"은행 "+i));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(writer.template(options)))) {
            var list=book.getSheet("목록");
            assertThat(list.getRow(0).getCell(0).getStringCellValue()).isEqualTo("PARTNER_KIND");
            assertThat(list.getRow(0).getCell(1).getStringCellValue()).isEqualTo("INDUSTRY");
            assertThat(list.getRow(0).getCell(2).getStringCellValue()).isEqualTo("BANK");
            for(int i=0;i<301;i++)assertThat(list.getRow(i+2).getCell(1).getStringCellValue()).isEqualTo("업종 "+i);
            for(int i=0;i<120;i++)assertThat(list.getRow(i+2).getCell(2).getStringCellValue()).isEqualTo("은행 "+i);
            assertThat(book.getName("Upload_INDUSTRY").getRefersToFormula()).isEqualTo("'목록'!$B$2:$B$303");
            assertThat(list.getProtect()).isTrue();
        }
        assertThat(temporaryFiles).isNotEmpty();for(Path file:temporaryFiles)assertThat(file).doesNotExist();
    }
    @Test void emptyDatabaseCodesDoNotCreateInventedChoicesOrBrokenRanges()throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(writer.template(List.of())))) {
            assertThat(book.getSheet("목록").getLastRowNum()).isZero();
            assertThat(book.getSheetAt(0).getDataValidations()).isEmpty();
            assertThat(book.getAllNames()).isEmpty();
            assertThat(book.getSheet("목록").getProtect()).isTrue();
        }
    }
    @Test void formulaLookingCodeNamesRemainLiteralStringsWithoutExternalLinks()throws Exception {
        String name="=WEBSERVICE(\"https://invalid.test\")";
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(writer.template(List.of(new UploadCode("BANK","VALUE",name)))))) {
            var cell=book.getSheet("목록").getRow(1).getCell(2);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);assertThat(cell.getStringCellValue()).isEqualTo(name);
            assertThat(book.getExternalLinksTable()).isEmpty();
        }
    }
    @Test void generationFailureDisposesStreamingFilesWithoutReturningPartialWorkbook() {
        assertThatThrownBy(()->writer.template(Arrays.asList((UploadCode)null))).isInstanceOf(NullPointerException.class);
        assertThat(temporaryFiles).isNotEmpty();for(Path file:temporaryFiles)assertThat(file).doesNotExist();
    }
}
