package com.sicost.dispatchattendance2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.uploadsops.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=DispatchAttendanceUploadController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,DispatchAttendanceUploadService.class,
    DispatchAttendanceUploadForm.class,UploadWorkbookWriter.class,CurrentUser.class,PermissionService.class})
class DispatchAttendanceUploadTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DispatchAttendanceUploadService service;
    @MockBean DispatchAttendanceUploadMapper mapper;
    @MockBean UploadService uploads;
    @MockBean UploadMapper common;
    @TempDir Path directory;
    AtomicLong sequence=new AtomicLong(100);
    Map<Long,String> statuses=new HashMap<>();
    Source source=new Source(11,21,"D-100","홍길동",new BigDecimal("0.50"),true);
    @BeforeEach void setup() throws Exception {
        statuses.clear();
        ReflectionTestUtils.setField(service,"directory",directory.toString());
        when(mapper.project(1)).thenReturn(new Project("DP","DAY","Y"));
        when(mapper.lockProject(1)).thenReturn(new Project("DP","DAY","Y"));
        when(mapper.nextUploadId()).thenAnswer(i->sequence.incrementAndGet());
        when(mapper.sources(any())).thenReturn(List.of(source));
        when(mapper.lockAttendance(any())).thenReturn(List.of(new Stored(5,22,12L,
            BigDecimal.ONE,null,BigDecimal.ZERO,new BigDecimal("20.0"),null)));
        when(mapper.insertAttendance(any())).thenReturn(7L);
        when(mapper.uploadStatus(anyLong())).thenAnswer(i->statuses.get(i.getArgument(0,Long.class)));
        doAnswer(i->{var write=i.getArgument(0,ReviewWrite.class);statuses.put(write.review().id(),write.status());return null;})
            .when(mapper).insertReview(any());
        when(uploads.store(any(),anyString())).thenAnswer(i->{
            var file=i.getArgument(0,org.springframework.web.multipart.MultipartFile.class);
            Path path=directory.resolve(UUID.randomUUID()+".xlsx");
            Files.write(path,file.getBytes());return path;
        });
    }
    ResultActions validate(MockMultipartFile file) throws Exception {
        return mvc.perform(multipart("/api/uploads/dispatch-attendance/validate").file(file)
            .param("month","2026-10").param("projectId","1"));
    }
    long review() throws Exception {
        return json.readTree(validate(AttendanceWorkbooks.file(AttendanceWorkbooks.values()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/upload_id").asLong();
    }
    ResultActions apply(long id) throws Exception {
        return mvc.perform(post("/api/uploads/dispatch-attendance/apply").contentType("application/json")
            .content("{\"upload_id\":"+id+",\"confirm\":true}"));
    }
    void untouched() {
        verify(mapper,never()).deleteAttendance(any());
        verify(mapper,never()).insertAttendance(any());
        verify(mapper,never()).audit(any());
    }
    @Test void anonymousValidationHasCommonEnvelopeRealRowsAndDoesNotWriteBusinessData() throws Exception {
        var response=mvc.perform(multipart("/api/uploads/dispatch-attendance/validate")
            .file(AttendanceWorkbooks.file(AttendanceWorkbooks.values()))
            .param("month","2026-10").param("projectId","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.form_type").value("B.3"))
            .andExpect(jsonPath("$.data.can_apply").value(true))
            .andExpect(jsonPath("$.data.error_count").value(0)).andReturn();
        long id=json.readTree(response.getResponse().getContentAsByteArray()).at("/data/upload_id").asLong();
        var result=service.result(id);
        assertThat(result.can_apply()).isTrue();assertThat(result.form_type()).isEqualTo("B.3");
        var preview=service.rows(id,null,new DeveloperPaging(0,50,"row,asc")).items().get(0);
        assertThat(preview.row()).isEqualTo(6);
        assertThat(preview.attendance().calculated_mm()).isEqualByComparingTo("0.50");
        assertThat(preview.attendance().ovtm_tm_qty()).isEqualByComparingTo("0");
        assertThat(preview.attendance().asgn_id()).isEqualTo(11);
        verify(mapper,never()).insertReview(any());untouched();
    }
    @Test void anonymousApplyReplacesWholeSelectedScopeAndAuditsOldAndNew() throws Exception {
        long id=review();
        apply(id).andExpect(status().isOk()).andExpect(jsonPath("$.data.form_type").value("B.3"))
            .andExpect(jsonPath("$.data.applied_count").value(1));
        var ordered=inOrder(mapper);
        ordered.verify(mapper).lockProject(1);
        ordered.verify(mapper).lockAttendance(any());
        ordered.verify(mapper).insertReview(argThat(w->w.status().equals("APPLIED")&&w.actor()==null));
        ordered.verify(mapper).deleteAttendance(argThat(s->s.projectId()==1&&s.month().equals("2026-10")));
        verify(mapper).audit(argThat(a->a.type().equals("DELETE")&&a.before().contains("22")&&a.after()==null));
        verify(mapper).audit(argThat(a->a.type().equals("INSERT")&&a.before()==null&&a.after().contains("21")));
        verify(mapper).access(argThat(a->a.type().equals("UPLOAD")&&a.actor()==null));
        assertThat(service.result(id).can_apply()).isFalse();
        apply(id).andExpect(status().isOk());verify(mapper,times(1)).deleteAttendance(any());
    }
    @ParameterizedTest @CsvSource({"관리번호,bad!","해당 월 근무일수,''","해당 월 근무일수,0",
        "해당 월 근무일수,32","근무일수,-1","근무일수,1.25","근무일수,''","연월,2026-11",
        "프로젝트ID,2","이름,다른이름","정규 근무시간,10000"})
    void rowValidationRejectsEntireWorkbook(String column,String value) throws Exception {
        var values=AttendanceWorkbooks.values();values.put(column,value);
        var response=validate(AttendanceWorkbooks.file(values)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(false)).andReturn();
        long id=json.readTree(response.getResponse().getContentAsString()).at("/data/upload_id").asLong();
        assertThat(service.issues(id,"ERROR",null,new DeveloperPaging(0,50,"row,asc")).items())
            .anyMatch(i->i.row()==6&&i.column().equals(column));
        apply(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(mapper).insertReview(argThat(w->w.status().equals("REJECTED")));untouched();
    }
    @Test void duplicateRowsRejectWholeFile() throws Exception {
        validate(AttendanceWorkbooks.file(AttendanceWorkbooks.values(),2,false,false)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void missingTitleIsFileIssue() throws Exception {
        validate(AttendanceWorkbooks.file(AttendanceWorkbooks.values(),1,true,false))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.error_count").value(1));untouched();
    }
    @Test void formulaIsRejectedWithoutEvaluationAndErrorDownloadIsRealExcel() throws Exception {
        long id=json.readTree(validate(AttendanceWorkbooks.file(AttendanceWorkbooks.values(),1,false,true))
            .andReturn().getResponse().getContentAsString()).at("/data/upload_id").asLong();
        try (var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(service.errors(id).bytes()))) {
            assertThat(book.getSheet("오류 목록")).isNotNull();
            assertThat(book.getSheetAt(0).getRow(5).getCell(4).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
        }
        assertThat(service.result(id).can_apply()).isFalse();untouched();
    }
    @Test void malformedRenamedFileCannotApply() throws Exception {
        validate(new MockMultipartFile("file","bad.xlsx","application/octet-stream","not an xlsx".getBytes()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void tooManyRowsAreAFileError() throws Exception {
        validate(AttendanceWorkbooks.file(AttendanceWorkbooks.values(),10001,false,false))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void oversizedFileIs400AndNeverStored() throws Exception {
        validate(new MockMultipartFile("file","big.xlsx","application/octet-stream",new byte[10*1024*1024+1]))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(uploads,never()).store(any(),any());untouched();
    }
    @ParameterizedTest @CsvSource({"MO,DAY","TK,DAY","DP,MONTH"})
    void wrongProjectBasisRejects(String type,String basis) throws Exception {
        when(mapper.project(1)).thenReturn(new Project(type,basis,"Y"));
        validate(AttendanceWorkbooks.file(AttendanceWorkbooks.values())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void absentAssignmentIsAnError() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of());
        validate(AttendanceWorkbooks.file(AttendanceWorkbooks.values())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void ambiguousAssignmentsAreNeverChosenArbitrarily() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of(source,new Source(12,21,"D-100","홍길동",null,true)));
        validate(AttendanceWorkbooks.file(AttendanceWorkbooks.values())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void hourWithoutWorkDaysHasNullMmAndPriceWarningButCanApply() throws Exception {
        when(mapper.project(1)).thenReturn(new Project("DP","HOUR","Y"));
        when(mapper.sources(any())).thenReturn(List.of(new Source(11,21,"D-100","홍길동",null,false)));
        var values=AttendanceWorkbooks.values();values.put("근무일수","");
        long id=json.readTree(validate(AttendanceWorkbooks.file(values)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(true)).andReturn().getResponse().getContentAsString())
            .at("/data/upload_id").asLong();
        assertThat(service.rows(id,null,new DeveloperPaging(0,50,null)).items().get(0).attendance().calculated_mm()).isNull();
        assertThat(service.result(id).warning_count()).isEqualTo(1);
    }
    @Test void hourRequiresRegularTime() throws Exception {
        when(mapper.project(1)).thenReturn(new Project("DP","HOUR","Y"));
        var values=AttendanceWorkbooks.values();values.put("정규 근무시간","");
        validate(AttendanceWorkbooks.file(values)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void validationOfClosedMonthDoesNotMutate() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        validate(AttendanceWorkbooks.file(AttendanceWorkbooks.values())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void validationOfConfirmedUseDoesNotMutate() throws Exception {
        when(mapper.confirmedUse(any())).thenReturn(true);
        validate(AttendanceWorkbooks.file(AttendanceWorkbooks.values())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(false));untouched();
    }
    @Test void closeAppearingAfterReviewIs409() throws Exception {
        long id=review();when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        apply(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));untouched();
    }
    @Test void confirmationAppearingAfterReviewIs409() throws Exception {
        long id=review();when(mapper.confirmedUse(any())).thenReturn(true);
        apply(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTENDANCE_BILLED"));untouched();
    }
    @Test void changedSourcesInvalidateReviewBeforeDeletingAnything() throws Exception {
        long id=review();when(mapper.sources(any())).thenReturn(List.of(new Source(12,21,"D-100","홍길동",null,true)));
        apply(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));untouched();
    }
    @Test void changedWorkbookIsRejectedBeforeDeletingAnything() throws Exception {
        long id=review();var metadata=json.readTree(Files.readAllBytes(directory.resolve("dispatch-"+id+".json")));
        Files.writeString(Path.of(metadata.get("path").asText()),"changed");
        apply(id).andExpect(status().isBadRequest());untouched();
    }
    @Test void revalidationUsesSavedScopeAndRetiresPreviousReview() throws Exception {
        long id=review();long next=service.revalidate(id).upload_id();
        assertThat(next).isNotEqualTo(id);assertThat(service.result(id).can_apply()).isFalse();
        assertThat(service.result(next).month()).isEqualTo("2026-10");
        apply(id).andExpect(status().isBadRequest());untouched();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"upload_id\":1,\"confirm\":false}","{\"upload_id\":1.1,\"confirm\":true}","{\"upload_id\":0,\"confirm\":true}","[]","{bad"})
    void applyBoundaryRequiresNumericIdAndExplicitConfirmation(String body) throws Exception {
        mvc.perform(post("/api/uploads/dispatch-attendance/apply").contentType("application/json").content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));untouched();
    }
}
