package com.sicost.financeentries;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.entries.*;
import com.sicost.uploadsops.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.entries.FinanceEntryModels.*;
import static com.sicost.uploadsops.UploadModels.*;

@WebMvcTest(controllers=FinanceEntriesUploadController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,FinanceEntriesUploadService.class,
    FinanceEntriesUploadForm.class,CurrentUser.class,PermissionService.class,UploadWorkbookWriter.class})
class FinanceEntriesUploadApiTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("sicost.upload-directory",()->directory.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FinanceEntriesUploadService service;
    @MockBean FinanceEntriesUploadMapper mapper;
    @MockBean FinanceEntryMapper entries;
    @MockBean UploadService uploads;
    @MockBean UploadMapper common;
    @MockBean Clock clock;
    static final java.util.concurrent.atomic.AtomicLong sequence=new java.util.concurrent.atomic.AtomicLong(100);
    @BeforeEach void setup() {
        when(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        when(mapper.nextUploadId()).thenAnswer(i->sequence.incrementAndGet());
        when(mapper.codes()).thenReturn(List.of(new UploadCode("FIN_CATEGORY","SGA","판관비"),new UploadCode("FIN_CATEGORY","NOI","영업외수익"),new UploadCode("FIN_CATEGORY","NOE","영업외비용")));
        when(mapper.mappings()).thenReturn(List.of(new UploadMapping("FIN_CATEGORY","관리비","SGA")));
        when(entries.list(anyString())).thenReturn(List.of());
        when(mapper.insert(any())).thenReturn(45L);
        when(entries.delete(any())).thenReturn(1);
        when(uploads.store(any(),anyString())).thenAnswer(i->{
            var file=i.getArgument(0,org.springframework.web.multipart.MultipartFile.class);
            Path path=directory.resolve(UUID.randomUUID()+".xlsx");Files.write(path,file.getBytes());return path;
        });
    }
    byte[] workbook(String... lines)throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("재무 항목");var title=sheet.createRow(3);
            for(int c=0;c<FinanceEntriesUploadForm.HEADERS.size();c++)title.createCell(c).setCellValue(FinanceEntriesUploadForm.HEADERS.get(c));
            for(int n=0;n<lines.length;n++) {var row=sheet.createRow(n+4);var cells=lines[n].split(",",-1);
                for(int c=0;c<cells.length;c++)row.createCell(c).setCellValue(cells[c]);}
            book.write(out);return out.toByteArray();
        }
    }
    ResultActions validate(byte[] bytes,String name)throws Exception {
        return mvc.perform(multipart("/api/uploads/finance-entries/validate").file(new MockMultipartFile("file",name,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes)));
    }
    String accepted()throws Exception {
        var response=validate(workbook("2026-10,판관비,임대료,1000000,0,2026-10-31,Y,비고"),"재무.xlsx")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.error_count").value(0)).andExpect(jsonPath("$.data.can_apply").value(true)).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).at("/data/upload_id").asText();
    }
    ResultActions apply(String id,boolean confirm)throws Exception {
        return mvc.perform(post("/api/uploads/finance-entries/apply").contentType("application/json")
            .content(json.writeValueAsString(Map.of("upload_id",id,"confirm",confirm))));
    }
    void unchanged() {verify(mapper,never()).insert(any());verify(entries,never()).delete(any());verify(entries,never()).audit(any());}
    @Test void validateReservesDecimalIdAndDoesNotWriteBusinessData()throws Exception {
        mvc.perform(multipart("/api/uploads/finance-entries/validate").file(new MockMultipartFile("file","재무.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook("2026-10,판관비,임대료,1000000,0,2026-10-31,Y,비고"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.upload_id").isString())
            .andExpect(jsonPath("$.data.error_count").value(0)).andExpect(jsonPath("$.data.can_apply").value(true));
        unchanged();verify(mapper,never()).saveReview(any());
    }
    @Test void previewIsReadOnly()throws Exception {
        apply(accepted(),false).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied_count").value(0));unchanged();verify(mapper,never()).saveReview(any());
    }
    @Test void confirmedApplySoftDeletesOnlyScopeAndAppendsBothSnapshots()throws Exception {
        String id=accepted();
        var old=new FinanceEntry(9L,"2026-10","SGA","통신비",200,20,null,"N","N",null,8L,"Y",false);
        when(entries.list("2026-10")).thenReturn(List.of(old));
        apply(id,true).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied_count").value(1));
        verify(mapper).lockScope();verify(entries).delete(new EntryRemoval(9,null));
        verify(mapper).insert(argThat(w->w.input().entry_ym().equals("2026-10")&&w.input().entry_amt()==1000000&&w.input().vat_amt()==0&&w.input().copy_entry_id()==null&&w.uploadId()==Long.parseLong(id)));
        verify(entries).audit(argThat(a->a.type().equals("DELETE")&&a.before().contains("통신비")&&a.after()==null));
        verify(entries).audit(argThat(a->a.id().equals("45")&&a.type().equals("INSERT")&&a.after().contains("임대료")));
        verify(mapper).saveReview(argThat(w->w.status().equals("APPLIED")&&w.review().month().equals("2026-10")));
        verify(mapper).access(any());
    }
    @Test void malformedWorkbookIsRejected()throws Exception {
        validate("bad".getBytes(),"재무.xlsx").andExpect(status().isOk()).andExpect(jsonPath("$.data.error_count").value(1));
        verify(mapper).saveReview(argThat(w->w.status().equals("REJECTED")));unchanged();
    }
    @Test void renamedFileIsValidationError()throws Exception {validate(workbook("2026-10,SGA,임대료,0"),"재무.csv").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));unchanged();}
    @Test void formulaIsForbidden()throws Exception {
        byte[] bytes=workbook("2026-10,SGA,임대료,1");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()) {
            book.getSheetAt(0).getRow(4).getCell(3).setCellFormula("1+1");book.write(out);bytes=out.toByteArray();}
        validate(bytes,"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();
    }
    @Test void duplicateRowsRejectWholeFile()throws Exception {
        validate(workbook("2026-10,SGA,임대료,0","2026-10,SGA,임대료,0"),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"-1","1.5","9223372036854775808"})
    void invalidMoneyRejects(String amount)throws Exception {validate(workbook("2026-10,SGA,임대료,"+amount),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();}
    @Test void zeroAndOptionalColumnsAreAccepted()throws Exception {validate(workbook("2026-10,SGA,임대료,0"),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(0));}
    @Test void mixedMonthsReject()throws Exception {validate(workbook("2026-10,SGA,임대료,0","2026-09,SGA,통신비,0"),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();}
    @Test void unknownCategoryRejects()throws Exception {validate(workbook("2026-10,OTHER,임대료,0"),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));}
    @Test void codeMappingProducesWarning()throws Exception {validate(workbook("2026-10,관리비,임대료,0"),"재무.xlsx").andExpect(jsonPath("$.data.warning_count").value(1)).andExpect(jsonPath("$.data.rows[0].entry.fin_se_cd").value("SGA"));}
    @Test void closedMonthRejectsInspection()throws Exception {when(entries.monthStatus("2026-10")).thenReturn("CLOSED");validate(workbook("2026-10,SGA,임대료,0"),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();}
    @Test void lateCloseIsConflict()throws Exception {String id=accepted();when(entries.monthStatus("2026-10")).thenReturn("CLOSED");apply(id,true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));unchanged();}
    @Test void closedCashDateAlsoBlocksReplacement()throws Exception {String id=accepted();when(entries.list("2026-10")).thenReturn(List.of(new FinanceEntry(9L,"2026-10","SGA","이전",1,0,LocalDate.of(2026,9,30),"N","N",null,null,"N",false)));when(entries.monthStatus("2026-09")).thenReturn("CLOSED");apply(id,true).andExpect(status().isConflict());unchanged();}
    @Test void futureMonthRejected()throws Exception {validate(workbook("2026-11,SGA,임대료,0"),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();}
    @Test void missingReviewIs404()throws Exception {apply("999999999",true).andExpect(status().isNotFound());unchanged();}
    @Test void malformedApplyIs400()throws Exception {apply("../file",true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("upload_id"));unchanged();}
    @Test void confirmationIsRequired()throws Exception {mvc.perform(post("/api/uploads/finance-entries/apply").contentType("application/json").content("{\"upload_id\":\"1\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("confirm"));}
    @Test void appliedReplayDoesNotReplaceAgain()throws Exception {String id=accepted();when(mapper.uploadStatus(Long.parseLong(id))).thenReturn("APPLIED");apply(id,true).andExpect(status().isOk());unchanged();}
    @Test void emptyFileIs400()throws Exception {validate(new byte[0],"재무.xlsx").andExpect(status().isBadRequest());}
    @Test void missingFileIs400()throws Exception {mvc.perform(multipart("/api/uploads/finance-entries/validate")).andExpect(status().isBadRequest());}
    @Test void oversizedFileIs400()throws Exception {validate(new byte[10*1024*1024+1],"재무.xlsx").andExpect(status().isBadRequest());}
    @Test void unexpectedFailureIsSafe()throws Exception {when(mapper.nextUploadId()).thenThrow(new IllegalStateException("private database details"));validate(workbook("2026-10,SGA,임대료,0"),"재무.xlsx").andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));}
    @Test void templateAndErrorsContainRealWorkbookBytes()throws Exception {String id=accepted();try(var book=new XSSFWorkbook(new ByteArrayInputStream(service.template().bytes()))){assertThat(book.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).isEqualTo("연월");}try(var book=new XSSFWorkbook(new ByteArrayInputStream(service.errors(Long.parseLong(id)).bytes()))){assertThat(book.getNumberOfSheets()).isPositive();}}
    @Test void changedOriginalCannotApply()throws Exception {
        String id=accepted();var review=json.readTree(Files.readAllBytes(directory.resolve("finance-entries-"+id+".json")));
        Files.writeString(Path.of(review.get("path").asText()),"changed");
        apply(id,true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file"));unchanged();
    }
    @Test void retiredReviewCannotApply()throws Exception {
        String id=accepted();var next=service.revalidate(Long.parseLong(id));
        assertThat(next.upload_id()).isNotEqualTo(Long.parseLong(id));
        apply(id,true).andExpect(status().isBadRequest());unchanged();
    }
    @Test void rejectedReviewCannotApply()throws Exception {
        var response=validate(workbook("2026-10,SGA,임대료,-1"),"재무.xlsx").andReturn();
        String id=json.readTree(response.getResponse().getContentAsString()).at("/data/upload_id").asText();
        apply(id,true).andExpect(status().isBadRequest());unchanged();
    }
    @Test void invalidVatIsRejected()throws Exception {validate(workbook("2026-10,SGA,임대료,1,-1"),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();}
    @Test void amountPlusVatOverflowIsRejected()throws Exception {validate(workbook("2026-10,SGA,임대료,9223372036854775807,1"),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();}
    @Test void invalidDateIsRejected()throws Exception {validate(workbook("2026-10,SGA,임대료,1,0,2026-02-30"),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();}
    @Test void invalidRecurringFlagIsRejected()throws Exception {validate(workbook("2026-10,SGA,임대료,1,0,,TRUE"),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();}
    @Test void blankItemIsRejected()throws Exception {validate(workbook("2026-10,SGA,,1"),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();}
    @Test void excessiveItemIsRejected()throws Exception {validate(workbook("2026-10,SGA,"+"가".repeat(101)+",1"),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();}
    @Test void excessiveRemarkIsRejected()throws Exception {validate(workbook("2026-10,SGA,임대료,1,0,,,"+"가".repeat(501)),"재무.xlsx").andExpect(jsonPath("$.data.error_count").value(1));unchanged();}
    @Test void rowLimitRejectsWholeFile()throws Exception {
        String[] lines=new String[10001];Arrays.fill(lines,"2026-10,SGA,임대료,1");
        validate(workbook(lines),"재무.xlsx").andExpect(jsonPath("$.data.items[0].message").value("데이터는 10,000행 이하만 올릴 수 있습니다."));unchanged();
    }
    @Test void externalEntitiesAreRejectedWithoutResolvingThem()throws Exception {
        byte[] original=workbook("2026-10,SGA,임대료,1");
        try(var in=new java.util.zip.ZipInputStream(new ByteArrayInputStream(original));var bytes=new ByteArrayOutputStream();var out=new java.util.zip.ZipOutputStream(bytes)) {
            for(java.util.zip.ZipEntry entry;(entry=in.getNextEntry())!=null;) {
                byte[] data=in.readAllBytes();
                if(entry.getName().equals("xl/workbook.xml")) {
                    String xml=new String(data,java.nio.charset.StandardCharsets.UTF_8);int start=xml.indexOf("?>")+2;
                    xml=xml.substring(0,start)+"<!DOCTYPE workbook [<!ENTITY forbidden SYSTEM 'file:///nonexistent-finance-entry-entity'>]>"+xml.substring(start);
                    data=xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));out.write(data);out.closeEntry();
            }
            out.finish();validate(bytes.toByteArray(),"재무.xlsx").andExpect(status().isOk()).andExpect(jsonPath("$.data.error_count").value(1));unchanged();
        }
    }
}
