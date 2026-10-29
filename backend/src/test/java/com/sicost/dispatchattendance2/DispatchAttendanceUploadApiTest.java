package com.sicost.dispatchattendance2;

import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.billing.BillingMutationService;
import com.sicost.common.security.*;
import com.sicost.uploadsops.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@WebMvcTest(controllers=UploadController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,UploadService.class,DispatchAttendanceUploadService.class,
    DispatchAttendanceUploadForm.class,UploadInspectionContexts.class,UploadWorkbookWriter.class,
    CurrentUser.class,PermissionService.class,DispatchAttendanceUploadApiTest.Time.class})
class DispatchAttendanceUploadApiTest {
    @TestConfiguration static class Time {@Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));}}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired DispatchAttendanceUploadService feature;
    @MockBean UploadMapper legacy;@MockBean PersonnelUploadMapper uploads;@MockBean PartnerUploadForm partner;
    @MockBean DispatchAttendanceUploadMapper mapper;@MockBean BillingMutationService mutations;
    @MockBean PlatformTransactionManager manager;
    static final String BASE="/api/uploads/dispatch-attendance";
    String[] values;static final java.util.concurrent.atomic.AtomicLong sequence=new java.util.concurrent.atomic.AtomicLong();
    @BeforeEach void setup(){
        values=new String[]{"2026-10","1","DEV-1","홍길동","10","80","","20"};
        when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
        when(uploads.nextUploadId()).thenAnswer(i->sequence.incrementAndGet());
        when(mapper.project(1)).thenReturn(new Project("DP","DAY","Y"));
        when(mapper.assignments(any())).thenReturn(List.of(new Assignment(5,7,"DEV-1","홍길동",new BigDecimal("0.50"),true)));
        when(mapper.existing(any())).thenReturn(List.of(new Attendance(20L,5,7,BigDecimal.ONE,null,BigDecimal.ZERO,BigDecimal.TEN)));
        when(mapper.insert(any())).thenReturn(30L);
    }
    byte[] workbook(int count,boolean formula,boolean missingHeader)throws Exception{
        try(var b=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var s=b.createSheet("파견 근태");var h=s.createRow(3);
            for(int c=0;c<8;c++)h.createCell(c).setCellValue(missingHeader&&c==7?"다른 제목":DispatchAttendanceUploadForm.HEADERS.get(c));
            for(int r=0;r<count;r++){var row=s.createRow(5+r);for(int c=0;c<8;c++)row.createCell(c).setCellValue(values[c]);if(formula)row.getCell(4).setCellFormula("1+1");}
            b.write(out);return out.toByteArray();
        }
    }
    ResultActions validate(byte[] bytes,String name)throws Exception{return mvc.perform(multipart(BASE+"/validate").file(new MockMultipartFile("file",name,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes)).param("month","2026-10").param("projectId","1"));}
    long inspect()throws Exception{var result=validate(workbook(1,false,false),"근태.xlsx").andExpect(status().isOk()).andReturn();return json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("upload_id").asLong();}
    ResultActions apply(long id)throws Exception{return mvc.perform(post(BASE+"/apply").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("upload_id",id,"confirm",true))));}
    @Test void anonymousValidationPreviewsRealRowsAndNeverWritesAttendance()throws Exception{
        long id=inspect();mvc.perform(get("/api/uploads/{id}/rows",id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].row").value(6)).andExpect(jsonPath("$.data.items[0].calculated_mm").value(0.5)).andExpect(jsonPath("$.data.items[0].attendance.ovtm_tm_qty").value(0));
        verify(mapper,never()).delete(any());verify(mapper,never()).insert(any());verify(mapper,never()).review(any());verifyNoInteractions(mutations);
    }
    @Test void anonymousApplyReplacesWholeScopeAuditsAndReturnsNumericId()throws Exception{
        long id=inspect();apply(id).andExpect(status().isOk()).andExpect(jsonPath("$.data.form_type").value("B.3")).andExpect(jsonPath("$.data.upload_id").value(id)).andExpect(jsonPath("$.data.applied_count").value(1));
        var order=inOrder(mutations,mapper);order.verify(mutations).lockProject(1);order.verify(mapper).billed(any());order.verify(mapper).project(1);order.verify(mapper).assignments(any());order.verify(mapper).existing(any());order.verify(mapper).review(any());order.verify(mapper).delete(any());
        verify(mutations).audit(argThat(a->a.type().equals("DELETE")&&a.before().contains("20")));
        verify(mutations).audit(argThat(a->a.type().equals("INSERT")&&a.after().contains("10.0")));
        verify(mapper).insert(argThat(w->w.scope().month().equals("2026-10")&&w.scope().projectId()==1&&w.actor()==null));
        apply(id).andExpect(status().isOk());verify(mapper,times(1)).delete(any());
    }
    @ParameterizedTest @ValueSource(strings={"","0","32","-1","20.01","10000"})
    void monthlyDenominatorRuleRejectsAllRows(String value)throws Exception{values[7]=value;long id=inspect();apply(id).andExpect(status().isBadRequest());verify(mapper,never()).delete(any());verify(mapper).review(argThat(w->!w.result().can_apply()));}
    @ParameterizedTest @ValueSource(strings={"bad number","UNKNOWN",""})
    void invalidManagementNumberRejects(String number)throws Exception{values[2]=number;long id=inspect();apply(id).andExpect(status().isBadRequest());verify(mapper,never()).delete(any());}
    @ParameterizedTest @ValueSource(strings={"4","5","6"})
    void negativeQuantitiesRejected(String field)throws Exception{values[Integer.parseInt(field)]="-1";long id=inspect();apply(id).andExpect(status().isBadRequest());}
    @Test void dayRequiresWorkDays()throws Exception{values[4]="";long id=inspect();apply(id).andExpect(status().isBadRequest());}
    @Test void hourRequiresRegularHours()throws Exception{when(mapper.project(1)).thenReturn(new Project("DP","HOUR","Y"));values[5]="";long id=inspect();apply(id).andExpect(status().isBadRequest());}
    @Test void hourWithoutDaysHasNullableMmAndExplanation()throws Exception{when(mapper.project(1)).thenReturn(new Project("DP","HOUR","Y"));values[4]="";long id=inspect();mvc.perform(get("/api/uploads/{id}/rows",id)).andExpect(jsonPath("$.data.items[0].calculated_mm").isEmpty()).andExpect(jsonPath("$.data.items[0].warnings[0]").isNotEmpty());apply(id).andExpect(status().isOk());}
    @Test void missingPriceOnlyWarnsAndAllowsAttendance()throws Exception{when(mapper.assignments(any())).thenReturn(List.of(new Assignment(5,7,"DEV-1","홍길동",null,false)));long id=inspect();apply(id).andExpect(status().isOk());}
    @Test void duplicateRowsRejectEntireFile()throws Exception{var response=validate(workbook(2,false,false),"근태.xlsx").andExpect(jsonPath("$.data.can_apply").value(false)).andReturn();long id=json.readTree(response.getResponse().getContentAsByteArray()).path("data").path("upload_id").asLong();apply(id).andExpect(status().isBadRequest());verify(mapper,never()).delete(any());}
    @Test void formulaIsRowErrorAndErrorDownloadIsRealXlsx()throws Exception{var result=validate(workbook(1,true,false),"근태.xlsx").andExpect(jsonPath("$.data.can_apply").value(false)).andReturn();long id=json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("upload_id").asLong();var download=mvc.perform(get("/api/uploads/{id}/errors",id)).andExpect(status().isOk()).andReturn();try(var book=new XSSFWorkbook(new ByteArrayInputStream(download.getResponse().getContentAsByteArray()))){assertThat(book.getSheet("오류 목록").getRow(1).getCell(1).getNumericCellValue()).isEqualTo(6);}}
    @Test void missingHeaderIsFileError()throws Exception{validate(workbook(1,false,true),"근태.xlsx").andExpect(status().isBadRequest());}
    @Test void malformedFileRejected()throws Exception{validate(new byte[]{1,2,3},"근태.xlsx").andExpect(status().isBadRequest());}
    @Test void renamedFileRejected()throws Exception{validate(workbook(1,false,false),"근태.csv").andExpect(status().isBadRequest());}
    @Test void oversizedFileRejected()throws Exception{validate(new byte[10*1024*1024+1],"근태.xlsx").andExpect(status().isPayloadTooLarge());}
    @Test void moreThanTenThousandRowsRejected()throws Exception{validate(workbook(10001,false,false),"근태.xlsx").andExpect(status().isBadRequest());}
    @Test void noEffectiveAssignmentRejects()throws Exception{when(mapper.assignments(any())).thenReturn(List.of());long id=inspect();apply(id).andExpect(status().isBadRequest());}
    @ParameterizedTest @ValueSource(strings={"MONTH","TK","IN"})
    void unsupportedProjectRejected(String basis)throws Exception{when(mapper.project(1)).thenReturn(new Project(basis.equals("MONTH")?"DP":basis,basis,"Y"));validate(workbook(1,false,false),"근태.xlsx").andExpect(status().isBadRequest());}
    @Test void validateClosedMonthIs409()throws Exception{when(uploads.monthStatus("2026-10")).thenReturn("CLOSED");validate(workbook(1,false,false),"근태.xlsx").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));}
    @Test void validateConfirmedLinkIs409()throws Exception{when(mapper.billed(any())).thenReturn(true);validate(workbook(1,false,false),"근태.xlsx").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTENDANCE_BILLED"));}
    @Test void applyRechecksClosedMonthBeforeDeleting()throws Exception{long id=inspect();when(uploads.monthStatus("2026-10")).thenReturn("CLOSED");apply(id).andExpect(status().isConflict());verify(mapper,never()).delete(any());}
    @Test void applyRechecksConfirmedLinkBeforeDeleting()throws Exception{long id=inspect();when(mapper.billed(any())).thenReturn(true);apply(id).andExpect(status().isConflict());verify(mapper,never()).delete(any());}
    @Test void changedInspectionRejectedBeforeDeleting()throws Exception{long id=inspect();when(mapper.assignments(any())).thenReturn(List.of(new Assignment(6,7,"DEV-1","홍길동",BigDecimal.ONE,true)));apply(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VALIDATION"));verify(mapper,never()).delete(any());}
    @Test void supersededReviewRejected()throws Exception{long id=inspect();mvc.perform(post("/api/uploads/{id}/revalidate",id)).andExpect(status().isOk());apply(id).andExpect(status().isConflict());verify(mapper,never()).delete(any());}
    @ParameterizedTest @ValueSource(strings={"review","insert","audit","access"})
    void writeFailureRollsBackAndKeepsInspectionRetryable(String stage)throws Exception{long id=inspect();switch(stage){case "review"->doThrow(new IllegalStateException()).when(mapper).review(any());case "insert"->when(mapper.insert(any())).thenThrow(new IllegalStateException());case "audit"->doThrow(new IllegalStateException()).when(mutations).audit(any());default->doThrow(new IllegalStateException()).when(legacy).access(any());}clearInvocations(manager);apply(id).andExpect(status().isInternalServerError());verify(manager).rollback(any());verify(manager,never()).commit(any());assertThat(feature.rows(id,null,new DeveloperPaging(0,50,"row,asc")).total()).isEqualTo(1);}
    @Test void applyRequiresExplicitConfirmation()throws Exception{mvc.perform(post(BASE+"/apply").contentType(MediaType.APPLICATION_JSON).content("{\"upload_id\":1,\"confirm\":false}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("confirm"));}
    @Test void templateHasAllEightHeaders()throws Exception{var r=mvc.perform(get("/api/uploads/templates/B.3")).andExpect(status().isOk()).andReturn();try(var b=new XSSFWorkbook(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))){assertThat(b.getSheetAt(0).getRow(3).getLastCellNum()).isEqualTo((short)8);}}
}
