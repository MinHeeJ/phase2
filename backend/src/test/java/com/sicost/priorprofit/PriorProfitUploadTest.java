package com.sicost.priorprofit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.uploadsops.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.priorprofit.PriorProfitUploadModels.*;

@WebMvcTest(controllers=PriorProfitUploadController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,PriorProfitUploadService.class,
    PriorProfitUploadForm.class,UploadWorkbookWriter.class,CurrentUser.class,PermissionService.class})
class PriorProfitUploadTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PriorProfitUploadService service;
    @MockBean PriorProfitUploadMapper mapper;
    @MockBean UploadService uploads;
    @MockBean UploadMapper common;
    @TempDir Path directory;
    AtomicLong sequence=new AtomicLong(100);
    Map<Long,String> statuses=new HashMap<>();
    @BeforeEach void setup() throws Exception {
        ReflectionTestUtils.setField(service,"directory",directory.toString());
        when(mapper.nextUploadId()).thenAnswer(i->sequence.incrementAndGet());
        when(mapper.uploadStatus(anyLong())).thenAnswer(i->statuses.get(i.getArgument(0,Long.class)));
        when(mapper.snapshots()).thenReturn(List.of());
        when(mapper.details()).thenReturn(List.of());
        when(mapper.codes()).thenReturn(List.of(new UploadModels.UploadCode("SGA_ITEM","RENT","임대료")));
        when(mapper.mappings()).thenReturn(List.of(new UploadModels.UploadMapping("SGA_ITEM","사무실","RENT")));
        doAnswer(i->{var w=i.getArgument(0,ReviewWrite.class);statuses.put(w.review().id(),w.status());return null;}).when(mapper).saveReview(any());
        when(uploads.store(any(),anyString())).thenAnswer(i->{
            var file=i.getArgument(0,org.springframework.web.multipart.MultipartFile.class);
            Path path=directory.resolve(UUID.randomUUID()+".xlsx");Files.write(path,file.getBytes());return path;
        });
    }
    byte[] workbook(String month,int count,boolean formula,String detail) throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("월별 손익");var title=sheet.createRow(3);
            for(int c=0;c<PriorProfitUploadForm.HEADERS.size();c++) title.createCell(c).setCellValue(PriorProfitUploadForm.HEADERS.get(c));
            for(int m=0;m<count;m++) {
                var row=sheet.createRow(m+4);row.createCell(0).setCellValue(month==null?String.format("2025-%02d",m+1):month);
                for(int c=1;c<PriorProfitUploadForm.HEADERS.size();c++) row.createCell(c).setCellValue(c==5?100:0);
                if(formula) row.getCell(1).setCellFormula("1+1");
            }
            if(detail!=null) {
                var s=book.createSheet("판관비 상세");var h=s.createRow(3);
                h.createCell(0).setCellValue("연월");h.createCell(1).setCellValue("항목");h.createCell(2).setCellValue("금액");
                for(int m=1;m<=12;m++) {var r=s.createRow(m+3);r.createCell(0).setCellValue(String.format("2025-%02d",m));r.createCell(1).setCellValue(detail);r.createCell(2).setCellValue(100);}
            }
            book.write(out);return out.toByteArray();
        }
    }
    ResultActions validate(byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/uploads/prior-profit/validate").file(new MockMultipartFile("file","손익.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes)));
    }
    long review() throws Exception {
        var response=validate(workbook(null,12,false,"임대료")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.upload_id").isString()).andExpect(jsonPath("$.data.error_count").value(0))
            .andExpect(jsonPath("$.data.can_apply").value(true)).andReturn();
        return Long.parseLong(json.readTree(response.getResponse().getContentAsString()).at("/data/upload_id").asText());
    }
    ResultActions apply(long id,boolean confirm) throws Exception {
        return mvc.perform(post("/api/uploads/prior-profit/apply").contentType("application/json")
            .content("{\"upload_id\":\""+id+"\",\"confirm\":"+confirm+"}"));
    }
    void untouched() {verify(mapper,never()).deleteImports();verify(mapper,never()).insertSnapshot(any());verify(mapper,never()).insertDetail(any());verify(mapper,never()).audit(any());}
    @Test void anonymousValidateReadsFilePeriodAndCommonCodeMappingWithoutBusinessWrites() throws Exception {
        mvc.perform(multipart("/api/uploads/prior-profit/validate").file(new MockMultipartFile("file","손익.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook(null,12,false,"임대료"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.form_type").value("B.11"))
            .andExpect(jsonPath("$.data.error_count").value(0)).andExpect(jsonPath("$.data.can_apply").value(true));
        long id=review();assertThat(service.supports(id)).isTrue();assertThat(service.supportsForm("B.11")).isTrue();
        assertThat(service.result(id).form_type()).isEqualTo("B.11");
        assertThat(service.permissionKey("upload")).isEqualTo("UI-08.read");assertThat(service.permissionKey("download")).isEqualTo("UI-08.download");
        verify(mapper,never()).saveReview(any());untouched();
    }
    @Test void confirmFalseRechecksButDoesNotWriteAnything() throws Exception {
        long id=review();apply(id,false).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied_count").value(0));
        verify(mapper,never()).saveReview(any());verify(mapper,never()).access(any());untouched();
    }
    @Test void confirmedReplacementWritesHeaderBeforeDetailsAndAuditAndIsIdempotent() throws Exception {
        long id=review();apply(id,true).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied_count").value(12));
        var order=inOrder(mapper);order.verify(mapper).lockScope();order.verify(mapper).deleteImports();
        order.verify(mapper).insertSnapshot(argThat(w->w.row().month().equals("2025-01")&&w.uploadId()==id&&w.actor()==null));
        verify(mapper,times(12)).insertSnapshot(any());verify(mapper,times(12)).insertDetail(any());
        verify(mapper,times(24)).audit(any());verify(mapper).access(any());
        apply(id,true).andExpect(status().isOk());verify(mapper,times(1)).deleteImports();
    }
    @Test void closeAnywhereInYearRejectsValidationAndEntireApply() throws Exception {
        when(mapper.hasClose()).thenReturn(true);
        validate(workbook(null,12,false,"임대료")).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void closeAfterValidationIsConflictEvenForPreview() throws Exception {
        long id=review();when(mapper.hasClose()).thenReturn(true);
        apply(id,false).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        apply(id,true).andExpect(status().isConflict());untouched();
    }
    @Test void otherYearIsRejected() throws Exception {validate(workbook("2026-01",1,false,null)).andExpect(jsonPath("$.data.can_apply").value(false));untouched();}
    @Test void duplicateMonthsRejectEntireFile() throws Exception {validate(workbook("2025-01",12,false,null)).andExpect(jsonPath("$.data.can_apply").value(false));untouched();}
    @Test void missingMonthRejectsAnnualReplacement() throws Exception {validate(workbook(null,11,false,null)).andExpect(jsonPath("$.data.can_apply").value(false));untouched();}
    @Test void formulaIsNeverEvaluatedAndDownloadIsRealXlsx() throws Exception {
        var r=validate(workbook(null,12,true,null)).andExpect(jsonPath("$.data.can_apply").value(false)).andReturn();
        long id=Long.parseLong(json.readTree(r.getResponse().getContentAsString()).at("/data/upload_id").asText());
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(service.errors(id).bytes()))) {assertThat(book.getNumberOfSheets()).isPositive();assertThat(book.getSheetAt(0).getRow(4).getCell(1).getCellType()).isNotEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);}
        apply(id,true).andExpect(status().isBadRequest());untouched();
    }
    @Test void renamedMalformedFileIsRejected() throws Exception {validate("not xlsx".getBytes()).andExpect(status().isOk()).andExpect(jsonPath("$.data.error_count").value(1));untouched();}
    @Test void unknownDetailCodeIsRejected() throws Exception {validate(workbook(null,12,false,"없는 항목")).andExpect(jsonPath("$.data.can_apply").value(false));untouched();}
    @Test void commonMappingIsUsed() throws Exception {validate(workbook(null,12,false,"사무실")).andExpect(jsonPath("$.data.error_count").value(0));}
    @Test void originalFileChangedAfterValidationCannotApply() throws Exception {
        long id=review();try(var files=Files.list(directory)) {for(Path p:files.filter(p->p.toString().endsWith(".xlsx")).toList()) Files.writeString(p,"changed");}
        apply(id,true).andExpect(status().isBadRequest());untouched();
    }
    @Test void missingReviewIs404() throws Exception {apply(999,true).andExpect(status().isNotFound());untouched();}
    @ParameterizedTest @ValueSource(strings={"{}","{\"upload_id\":123,\"confirm\":true}","{\"upload_id\":\"1\"}","{\"upload_id\":\"9223372036854775808\",\"confirm\":true}","{\"upload_id\":\"1\",\"confirm\":\"true\"}","{} {}"})
    void malformedApplyIsValidationError(String body) throws Exception {
        mvc.perform(post("/api/uploads/prior-profit/apply").contentType("application/json").content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));untouched();
    }
    @Test void missingFileIs400() throws Exception {mvc.perform(multipart("/api/uploads/prior-profit/validate")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file"));}
    @Test void oversizedFileIs400BeforeStorage() throws Exception {validate(new byte[10*1024*1024+1]).andExpect(status().isBadRequest());verify(uploads,never()).store(any(),any());}
    @Test void internalFailureDoesNotLeakDetails() throws Exception {
        when(mapper.nextUploadId()).thenThrow(new IllegalStateException("private database detail"));
        validate(workbook(null,12,false,null)).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
    }
    byte[] edit(java.util.function.Consumer<XSSFWorkbook> edit) throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(workbook(null,12,false,"임대료")));var out=new ByteArrayOutputStream()) {
            edit.accept(book);book.write(out);return out.toByteArray();
        }
    }
    @ParameterizedTest @CsvSource({"1,-1","1,1.5","1,9223372036854775808","8,9223372036854775808","11,-1","1,''"})
    void invalidMoneyRejectsEveryRowRatherThanPartiallyApplying(int column,String value) throws Exception {
        validate(edit(b->b.getSheetAt(0).getRow(4).getCell(column).setCellValue(value)))
            .andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void negativeAdjustmentAndZeroInputsRemainExact() throws Exception {
        validate(edit(b->b.getSheetAt(0).getRow(4).getCell(8).setCellValue("-100")))
            .andExpect(jsonPath("$.data.error_count").value(0)).andExpect(jsonPath("$.data.rows[0].profit.adjustment").value(-100));
    }
    @Test void computedMoneyOverflowIsValidationNotDatabaseError() throws Exception {
        validate(edit(b->{b.getSheetAt(0).getRow(4).getCell(2).setCellValue(Long.toString(Long.MAX_VALUE));b.getSheetAt(0).getRow(4).getCell(3).setCellValue("1");}))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void duplicateDetailRowsRejectWholeFile() throws Exception {
        validate(edit(b->{var r=b.getSheetAt(1).createRow(16);r.createCell(0).setCellValue("2025-01");r.createCell(1).setCellValue("RENT");r.createCell(2).setCellValue(100);}))
            .andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void detailTotalMismatchCannotBeStored() throws Exception {
        validate(edit(b->b.getSheetAt(1).getRow(4).getCell(2).setCellValue(99)))
            .andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void duplicateHeadersAreRejected() throws Exception {
        validate(edit(b->b.getSheetAt(0).getRow(3).createCell(13).setCellValue("연월")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void changedCodeMappingIsRecheckedInsideApply() throws Exception {
        long id=review();when(mapper.codes()).thenReturn(List.of());
        apply(id,true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));untouched();
    }
    @Test void templateContainsRealXlsxAndBothInputSheets() throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(service.template().bytes()))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(2);assertThat(book.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).isEqualTo("연월");
            assertThat(book.isMacroEnabled()).isFalse();
        }
    }
    @Test void macroRenamedAsXlsxIsRejected() throws Exception {
        var response=validate(edit(b->b.setWorkbookType(org.apache.poi.xssf.usermodel.XSSFWorkbookType.XLSM)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.can_apply").value(false)).andReturn();
        long id=Long.parseLong(json.readTree(response.getResponse().getContentAsString()).at("/data/upload_id").asText());
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(service.errors(id).bytes()))) {assertThat(book.isMacroEnabled()).isFalse();}
        untouched();
    }
    @Test void tooManyRowsAreRejected() throws Exception {
        validate(workbook("2025-01",10001,false,null)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
}
