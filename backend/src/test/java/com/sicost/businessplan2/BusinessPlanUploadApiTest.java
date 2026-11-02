package com.sicost.businessplan2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.uploadsops.*;
import java.io.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
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
import static com.sicost.businessplan2.BusinessPlanUploadModels.*;

@WebMvcTest(controllers=BusinessPlanUploadController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,BusinessPlanUploadService.class,
    BusinessPlanUploadForm.class,CurrentUser.class,PermissionService.class,UploadWorkbookWriter.class})
class BusinessPlanUploadApiTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("sicost.upload-directory",()->directory.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BusinessPlanUploadService service;
    @MockBean BusinessPlanUploadMapper mapper;
    @MockBean UploadService uploads;
    @MockBean UploadMapper common;
    static final java.util.concurrent.atomic.AtomicLong sequence=new java.util.concurrent.atomic.AtomicLong(100);
    @BeforeEach void setup() {
        when(mapper.nextUploadId()).thenAnswer(i->sequence.incrementAndGet());
        when(mapper.parts(anyString())).thenReturn(List.of(7L));
        when(mapper.plans(anyString())).thenReturn(List.of());
        when(mapper.closedMonths(anyString())).thenReturn(List.of());
        when(uploads.store(any(),anyString())).thenAnswer(i->{
            var file=i.getArgument(0,org.springframework.web.multipart.MultipartFile.class);
            Path path=directory.resolve(UUID.randomUUID()+".xlsx"); Files.write(path,file.getBytes());return path;
        });
    }
    byte[] workbook(String... lines)throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("사업계획");var title=sheet.createRow(3);
            for(int c=0;c<BusinessPlanUploadForm.HEADERS.size();c++) title.createCell(c).setCellValue(BusinessPlanUploadForm.HEADERS.get(c));
            for(int n=0;n<lines.length;n++) {
                var row=sheet.createRow(n+4);var cells=lines[n].split(",",-1);
                for(int c=0;c<cells.length;c++) row.createCell(c).setCellValue(cells[c]);
            }
            book.write(out);return out.toByteArray();
        }
    }
    ResultActions validate(byte[] bytes,String name)throws Exception {
        return mvc.perform(multipart("/api/uploads/business-plan/validate")
            .file(new MockMultipartFile("file",name,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes)));
    }
    String accepted()throws Exception {
        var response=validate(workbook("2027,1,개발파트,1000000,-100,75.25"),"계획.xlsx")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.error_count").value(0))
            .andExpect(jsonPath("$.data.can_apply").value(true)).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).at("/data/upload_id").asText();
    }
    ResultActions apply(String id,boolean confirm)throws Exception {
        return mvc.perform(post("/api/uploads/business-plan/apply").contentType("application/json")
            .content(json.writeValueAsString(Map.of("upload_id",id,"confirm",confirm))));
    }
    @Test void publicValidateReservesDecimalIdWithoutBusinessWrite()throws Exception {
        mvc.perform(multipart("/api/uploads/business-plan/validate").file(new MockMultipartFile("file","계획.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook("2027,1,개발파트,1000000,-100,75.25"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.upload_id").isString())
            .andExpect(jsonPath("$.data.error_count").value(0)).andExpect(jsonPath("$.data.can_apply").value(true));
        verify(mapper,never()).replace(any());verify(mapper,never()).saveReview(any());
    }
    @Test void previewIsReadOnly()throws Exception {
        apply(accepted(),false).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied_count").value(0));
        verify(mapper,never()).replace(any());verify(mapper,never()).audit(any());verify(mapper,never()).saveReview(any());
    }
    @Test void applyRechecksAndWritesOriginalScopeAndAudit()throws Exception {
        apply(accepted(),true).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied_count").value(1));
        verify(mapper).replace(argThat(w->w.row().month().equals("2027-01")&&w.row().profit()==-100));
        verify(mapper).audit(argThat(a->a.after().contains("1000000")&&a.actor()==null));
        verify(mapper).saveReview(argThat(w->w.status().equals("APPLIED")&&w.review().year().equals("2027")));
    }
    @Test void malformedWorkbookIsRejectedAndRecorded()throws Exception {
        validate("not excel".getBytes(),"계획.xlsx").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.error_count").value(1)).andExpect(jsonPath("$.data.can_apply").value(false));
        verify(mapper).saveReview(argThat(w->w.status().equals("REJECTED")));verify(mapper,never()).replace(any());
    }
    @Test void renamedFileTypeIsValidationError()throws Exception {
        validate(workbook("2027,1,개발파트,1,1,1"),"계획.csv").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("file"));
    }
    @Test void duplicateMonthAndPartRejectWholeFile()throws Exception {
        validate(workbook("2027,1,개발파트,1,1,1","2027,1,개발파트,2,2,2"),"계획.xlsx")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.error_count").value(1));
        verify(mapper,never()).replace(any());
    }
    @Test void formulaIsForbiddenEvenWithCachedValue()throws Exception {
        byte[] bytes=workbook("2027,1,개발파트,1,1,1");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()) {
            book.getSheetAt(0).getRow(4).getCell(3).setCellFormula("1+1");book.write(out);bytes=out.toByteArray();
        }
        validate(bytes,"계획.xlsx").andExpect(status().isOk()).andExpect(jsonPath("$.data.error_count").value(1));
    }
    @Test void fractionalMoneyIsRejected()throws Exception {
        validate(workbook("2027,1,개발파트,1.5,0,50"),"계획.xlsx").andExpect(jsonPath("$.data.items[0].field").value("목표매출"));
    }
    @Test void outOfRangeRateIsRejected()throws Exception {
        validate(workbook("2027,1,개발파트,1,0,101"),"계획.xlsx").andExpect(jsonPath("$.data.items[0].field").value("목표가동률"));
    }
    @Test void unknownPartIsRejected()throws Exception {
        when(mapper.parts(anyString())).thenReturn(List.of());
        validate(workbook("2027,1,없는파트,1,1,1"),"계획.xlsx").andExpect(jsonPath("$.data.error_count").value(1));
    }
    @Test void mixedYearsAreRejected()throws Exception {
        validate(workbook("2027,1,개발파트,1,1,1","2028,2,개발파트,1,1,1"),"계획.xlsx")
            .andExpect(jsonPath("$.data.error_count").value(1));
    }
    @Test void closedMismatchRejectsValidation()throws Exception {
        when(mapper.closedMonths("2027")).thenReturn(List.of("2027-01"));
        validate(workbook("2027,1,개발파트,1,1,1"),"계획.xlsx").andExpect(jsonPath("$.data.error_count").value(1));
    }
    @Test void matchingClosedRowIsPreservedNotRewritten()throws Exception {
        when(mapper.closedMonths("2027")).thenReturn(List.of("2027-01"));
        when(mapper.plans("2027")).thenReturn(List.of(new Plan(3,"2027-01",7L,1000000,-100,new BigDecimal("75.25"))));
        apply(accepted(),true).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied_count").value(0));
        verify(mapper,never()).replace(any());verify(mapper,never()).deleteOpen(any());verify(mapper,never()).audit(any());
    }
    @Test void lateCloseIsConflictAndLeavesAllRowsUnchanged()throws Exception {
        String id=accepted();when(mapper.closedMonths("2027")).thenReturn(List.of("2027-01"));
        apply(id,true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper,never()).replace(any());verify(mapper,never()).deleteOpen(any());verify(mapper,never()).audit(any());
    }
    @Test void unknownUploadIsNotFound()throws Exception { apply("99999999",true).andExpect(status().isNotFound()); }
    @Test void invalidIdentifierAndMissingConfirmationAreValidationErrors()throws Exception {
        apply("../file",true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/uploads/business-plan/apply").contentType("application/json").content("{\"upload_id\":\"1\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("confirm"));
    }
    @Test void rejectedReviewCannotApply()throws Exception {
        var response=validate("bad".getBytes(),"계획.xlsx").andReturn();
        String id=json.readTree(response.getResponse().getContentAsString()).at("/data/upload_id").asText();
        apply(id,true).andExpect(status().isBadRequest());verify(mapper,never()).replace(any());
    }
    @Test void replayDoesNotReplaceAgain()throws Exception {
        String id=accepted();when(mapper.uploadStatus(Long.parseLong(id))).thenReturn("APPLIED");
        apply(id,true).andExpect(status().isOk());verify(mapper,never()).replace(any());
    }
    @Test void errorsAndTemplateAreRealXlsx()throws Exception {
        String id=accepted();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(service.template().bytes()))) {
            assertThat(book.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).isEqualTo("연도");
        }
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(service.errors(Long.parseLong(id)).bytes()))) {
            assertThat(book.getNumberOfSheets()).isPositive();
        }
    }
    @Test void unexpectedFailureDoesNotLeakDatabaseDetails()throws Exception {
        when(mapper.nextUploadId()).thenThrow(new IllegalStateException("private database details"));
        validate(workbook("2027,1,개발파트,1,1,1"),"계획.xlsx").andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
    }
    @Test void emptyFileIsValidationError()throws Exception {
        validate(new byte[0],"계획.xlsx").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file"));
    }
    @Test void missingFileIsValidationError()throws Exception {
        mvc.perform(multipart("/api/uploads/business-plan/validate")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void oversizedFileIsValidationError()throws Exception {
        mvc.perform(multipart("/api/uploads/business-plan/validate").file(new MockMultipartFile("file","계획.xlsx","application/octet-stream",new byte[1]) {
            @Override public long getSize() {return 10L*1024*1024+1;}
        })).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file"));
    }
    @Test void changedOriginalCannotApply()throws Exception {
        String id=accepted();var metadata=json.readTree(Files.readAllBytes(directory.resolve("business-plan-"+id+".json")));
        Files.writeString(Path.of(metadata.get("path").asText()),"changed");
        apply(id,true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file"));
        verify(mapper,never()).replace(any());
    }
    @Test void externalEntitiesAreRejectedWithoutResolvingThem()throws Exception {
        byte[] original=workbook("2027,1,개발파트,1,1,1");
        try(var in=new java.util.zip.ZipInputStream(new ByteArrayInputStream(original));var bytes=new ByteArrayOutputStream();var out=new java.util.zip.ZipOutputStream(bytes)) {
            for(java.util.zip.ZipEntry entry;(entry=in.getNextEntry())!=null;) {
                byte[] data=in.readAllBytes();
                if(entry.getName().equals("xl/workbook.xml")) {
                    String xml=new String(data,java.nio.charset.StandardCharsets.UTF_8);
                    int start=xml.indexOf("?>")+2;
                    xml=xml.substring(0,start)+"<!DOCTYPE workbook [<!ENTITY forbidden SYSTEM 'file:///nonexistent-business-plan-entity'>]>"+xml.substring(start);
                    data=xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));out.write(data);out.closeEntry();
            }
            out.finish();validate(bytes.toByteArray(),"계획.xlsx").andExpect(status().isOk()).andExpect(jsonPath("$.data.error_count").value(1));
        }
    }
    @Test void rowLimitRejectsWholeFile()throws Exception {
        String[] lines=new String[10001];Arrays.fill(lines,"2027,1,개발파트,1,1,1");
        validate(workbook(lines),"계획.xlsx").andExpect(status().isOk()).andExpect(jsonPath("$.data.error_count").value(1))
            .andExpect(jsonPath("$.data.items[0].message").value("데이터는 10,000행 이하만 올릴 수 있습니다."));
        verify(mapper,never()).replace(any());
    }
}
