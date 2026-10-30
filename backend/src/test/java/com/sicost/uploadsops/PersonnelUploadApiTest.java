package com.sicost.uploadsops;

import static com.sicost.uploadsops.UploadModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.apiops.*;
import com.sicost.shared.SharedReadService;
import com.sicost.shared.SharedReadModels.OperatingParam;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@WebMvcTest(controllers={UploadController.class,UploadResultController.class})
@Import({RequestIdFilter.class,ApiExceptionHandler.class,UploadService.class,PartnerUploadForm.class,UploadWorkbookWriter.class,PersonnelUploadService.class,PersonnelUploadForm.class,UploadResultService.class,UploadInspectionContexts.class,CurrentUser.class,PermissionService.class,UploadApiTest.Time.class})
class PersonnelUploadApiTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired PersonnelUploadService service;@Autowired UploadApiTest.MutableClock clock;
    @MockBean UploadMapper legacy;@MockBean PersonnelUploadMapper mapper;@MockBean SharedReadService shared;@MockBean PlatformTransactionManager transactions;
    @MockBean UploadResultMapper results;
    static final AtomicLong IDS=new AtomicLong(100);
    @BeforeEach void setup() {
        clock.now=Instant.parse("2026-10-22T00:00:00Z");
        when(mapper.nextUploadId()).thenAnswer(i->IDS.incrementAndGet());
        when(mapper.nextDeveloperId()).thenReturn(1L,2L,10000L);
        when(transactions.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
        when(mapper.codes()).thenReturn(List.of(new UploadCode("EMP_TYPE","FT","정규직"),new UploadCode("EMP_TYPE","OS","외주"),new UploadCode("EMP_TYPE","FL","프리랜서"),new UploadCode("EMP_TYPE","CT","계약직"),new UploadCode("JOB","DEV","개발"),new UploadCode("WORK_STATUS","RESIGN","퇴사"),new UploadCode("POSITION","TL","팀장")));
        when(mapper.mappings()).thenReturn(List.of());when(mapper.allowance("TL")).thenReturn("200000");
        when(mapper.preview(any())).thenReturn(Map.of("indv_lbr_cst_amt",4970000L,"real_pay_amt",4200000L));
        when(mapper.insertSalary(any())).thenReturn(Map.of("mth_cst_id",1L,"cst_ym","2026-10","indv_lbr_cst_amt",4970000L,"real_pay_amt",4200000L));
        when(shared.effectiveParam(eq("VAT_RATE"),anyString(),eq(DataScope.ALL))).thenReturn(new OperatingParam("VAT_RATE","2026-01","VAT",BigDecimal.TEN,null,null,null));
        when(mapper.developer(anyString())).thenReturn(null);
    }
    static Map<String,String> developer(){return new LinkedHashMap<>(Map.of("이름","검사 개발자","직원구분","FT","주 직무","DEV"));}
    static Map<String,String> salary(){return new LinkedHashMap<>(Map.of("관리번호","K-0101","이름","검사 개발자","월 급여","4000000","4대보험(회사)","420000"));}
    void actor(){when(mapper.developer("K-0101")).thenReturn(new LinkedHashMap<>(Map.of("dev_id",101L,"dev_nm","검사 개발자","emp_se_cd","FT","duty_se_cd","DIRECT","pstn_cd","TL","use_yn","Y","affiliation","자사")));}
    static byte[] workbook(String type,List<Map<String,String>> rows)throws Exception{try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){Sheet sheet=book.createSheet("업로드");var headers=PersonnelUploadForm.headers(type);Row title=sheet.createRow(3);for(int i=0;i<headers.size();i++)title.createCell(i).setCellValue(headers.get(i));for(int n=0;n<rows.size();n++){Row row=sheet.createRow(n+4);for(int c=0;c<headers.size();c++)row.createCell(c).setCellValue(rows.get(n).getOrDefault(headers.get(c),""));}book.write(out);return out.toByteArray();}}
    ResultActions validate(String type,String month,List<Map<String,String>> rows)throws Exception{var request=multipart("/api/uploads/validate").file(new MockMultipartFile("file","검사.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook(type,rows))).param("form_type",type);if(month!=null)request.param("month",month);return mvc.perform(request);}
    JsonNode data(ResultActions action)throws Exception{return json.readTree(action.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");}
    long validateDeveloper(Map<String,String> row)throws Exception{return data(validate("B.8",null,List.of(row))).get("upload_id").asLong();}
    void noBusiness(){verify(mapper,never()).insertDeveloper(any());verify(mapper,never()).insertSalary(any());verify(mapper,never()).deleteSalaries(any());verify(mapper,never()).deleteExpenses(any());verify(mapper,never()).history(any());}
    @Test void anonymousHappyValidationAndRowsHaveNoBusinessSideEffects()throws Exception {
        long id=data(mvc.perform(multipart("/api/uploads/validate")
            .file(new MockMultipartFile("file","검사.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook("B.8",List.of(developer(),developer(),developer()))))
            .param("form_type","B.8"))
            .andExpect(jsonPath("$.data.form_type").value("B.8"))
            .andExpect(jsonPath("$.data.row_count").value(3))
            .andExpect(jsonPath("$.data.error_count").value(0))
            .andExpect(jsonPath("$.data.can_apply").value(true))).get("upload_id").asLong();
        mvc.perform(get("/api/uploads/{uploadId}/rows",id).param("severity","NORMAL"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.items[0].row").value(5))
            .andExpect(jsonPath("$.data.items[0].developer.main_job_cd").value("DEV"));
        mvc.perform(get("/api/uploads/{uploadId}",id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.row_count").value(3))
            .andExpect(jsonPath("$.data.can_apply").value(true));
        noBusiness();verify(mapper,never()).insertReview(any());verify(legacy,never()).insertIssue(any());
    }
    @ParameterizedTest @ValueSource(strings={"이름","직원구분","주 직무"}) void mandatoryDeveloperFieldsBlockAllWrites(String field)throws Exception{var row=developer();row.put(field,"");long id=validateDeveloper(row);assertThat(service.result(id).can_apply()).isFalse();mvc.perform(get("/api/uploads/"+id+"/issues")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].column").value(field));mvc.perform(post("/api/uploads/"+id+"/apply")).andExpect(status().isBadRequest());noBusiness();}
    @Test void duplicateOnlySecondAndFilteredCountsAndEmptyPage()throws Exception{var row=developer();row.put("관리번호","MANUAL-1");long id=data(validate("B.8",null,List.of(row,row))).get("upload_id").asLong();mvc.perform(get("/api/uploads/"+id+"/issues").param("severity","ERROR").param("column","관리번호")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].row").value(6)).andExpect(jsonPath("$.data.items[0].message").value("파일 안에서 5행과 관리번호가 같습니다."));mvc.perform(get("/api/uploads/"+id+"/issues").param("severity","WARNING")).andExpect(jsonPath("$.data.total").value(0));mvc.perform(get("/api/uploads/"+id+"/rows").param("severity","ERROR").param("page","1")).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items").isEmpty());}
    @Test void anonymousHappyApplyPersistsDeveloperIdsAndHistorySideEffectsAndReplayIsNoop()throws Exception {
        long id=data(validate("B.8",null,List.of(developer(),developer(),developer()))).get("upload_id").asLong();
        mvc.perform(post("/api/uploads/{uploadId}/apply",id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applied_count").value(3))
            .andExpect(jsonPath("$.data.developer_ids[2]").value(10000));
        mvc.perform(post("/api/uploads/{uploadId}/apply",id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applied_count").value(3));
        verify(mapper,times(3)).insertDeveloper(any());
        verify(mapper).insertDeveloper(argThat(w->w.id()==10000&&w.number().equals("K-10000")&&w.upload()==id));
        verify(mapper,times(3)).history(argThat(w->w.table().equals("mst.dev")&&w.type().equals("INSERT")&&w.reason().equals("엑셀 업로드 #"+id)));
        verify(mapper).access(argThat(w->w.type().equals("UPLOAD")));verify(transactions).commit(any());
        mvc.perform(get("/api/uploads/{uploadId}",id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.row_count").value(3))
            .andExpect(jsonPath("$.data.can_apply").value(false));
    }
    @Test void applySideEffectFailureRollsBackAndPreservesApplicability()throws Exception {
        long id=validateDeveloper(developer());doThrow(new IllegalStateException("audit")).when(mapper).history(any());
        mvc.perform(post("/api/uploads/{uploadId}/apply",id)).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("SERVER_ERROR"));
        verify(transactions).rollback(any());verify(transactions,never()).commit(any());verify(mapper,never()).access(any());
        mvc.perform(get("/api/uploads/{uploadId}",id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(true));
    }
    @Test void uniqueAllocationCollisionReturns409()throws Exception{long id=validateDeveloper(developer());doThrow(new org.springframework.dao.DuplicateKeyException("collision")).when(mapper).insertDeveloper(any());mvc.perform(post("/api/uploads/"+id+"/apply")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_MNG_NO"));verify(transactions).rollback(any());}
    @Test void normalizationAndAffiliationWarningsAllowApply()throws Exception{var row=developer();row.put("직원구분","정직원");row.put("소속","외부 표기");long id=validateDeveloper(row);assertThat(service.result(id).warning_count()).isEqualTo(1);mvc.perform(get("/api/uploads/"+id+"/issues").param("severity","WARNING")).andExpect(jsonPath("$.data.total").value(2));mvc.perform(post("/api/uploads/"+id+"/apply")).andExpect(status().isOk());verify(mapper).insertDeveloper(argThat(w->w.input().get("emp_se_cd").equals("FT")&&w.input().get("vndr_ptnr_id")==null));}
    @Test void anonymousHappyRevalidateReplacesIssuesWithoutBusinessSideEffects()throws Exception {
        var row=developer();row.put("주 직무","새 표기");long id=validateDeveloper(row);
        when(mapper.mappings()).thenReturn(List.of(new UploadMapping("JOB","새 표기","DEV")));
        long next=data(mvc.perform(post("/api/uploads/{uploadId}/revalidate",id))
            .andExpect(jsonPath("$.data.can_apply").value(true))
            .andExpect(jsonPath("$.data.error_count").value(0))).get("upload_id").asLong();
        assertThat(next).isNotEqualTo(id);
        mvc.perform(get("/api/uploads/{uploadId}",id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(false));
        mvc.perform(get("/api/uploads/{uploadId}",next)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.can_apply").value(true));
        mvc.perform(get("/api/uploads/{uploadId}/issues",next)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].normalized_value").value("DEV"))
            .andExpect(jsonPath("$.data.items[0].severity").value("WARNING"));
        mvc.perform(post("/api/uploads/{uploadId}/apply",id)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STALE_VALIDATION"));
        noBusiness();verify(mapper,never()).insertReview(any());verify(legacy,never()).insertIssue(any());
    }
    @Test void existingManagementNumberRemainsRejectedOnRevalidation()throws Exception{var row=developer();row.put("관리번호","EXISTS");when(mapper.developer("EXISTS")).thenReturn(Map.of("dev_id",1));long id=validateDeveloper(row);long next=data(mvc.perform(post("/api/uploads/"+id+"/revalidate"))).get("upload_id").asLong();assertThat(service.result(next).can_apply()).isFalse();mvc.perform(get("/api/uploads/"+next+"/issues")).andExpect(jsonPath("$.data.items[0].message").value("이미 있는 관리번호입니다."));noBusiness();}
    @ParameterizedTest @CsvSource({"rows,severity,UNKNOWN","rows,size,10","rows,page,-1","rows,sort,other;drop","issues,column,missing","issues,severity,NORMAL"}) void badFiltersUseApiError(String endpoint,String field,String value)throws Exception{long id=validateDeveloper(developer());mvc.perform(get("/api/uploads/"+id+"/"+endpoint).param(field,value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());}
    @ParameterizedTest @ValueSource(strings={"revalidate","apply","issues","rows","errors"}) void invalidIdHasField(String endpoint)throws Exception{var request=Set.of("apply","revalidate").contains(endpoint)?post("/api/uploads/0/"+endpoint):get("/api/uploads/0/"+endpoint);mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("uploadId")).andExpect(jsonPath("$.requestId").isNotEmpty());noBusiness();}
    @Test void anonymousHappyErrorsWorkbookPreservesRowColumnReasonWithoutBusinessSideEffects()throws Exception {
        var row=developer();row.put("주 직무","");long id=validateDeveloper(row);
        var response=mvc.perform(get("/api/uploads/{uploadId}/errors",id)).andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition")).contains("UTF-8");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            Row issue=book.getSheet("오류 목록").getRow(1);
            assertThat(issue.getCell(1).getNumericCellValue()).isEqualTo(5);
            assertThat(issue.getCell(2).getStringCellValue()).isEqualTo("주 직무");
            assertThat(issue.getCell(4).getStringCellValue()).contains("비어");
        }
        noBusiness();
    }
    @Test void expiryAndUnknownContextsAre410()throws Exception{long id=validateDeveloper(developer());clock.now=clock.now.plusSeconds(1800);mvc.perform(get("/api/uploads/"+id+"/rows")).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("VALIDATION_EXPIRED"));}
    @Test void rowsAndIssuesReadSideEffectsLeaveBusinessAndReviewWritesAbsent()throws Exception {
        var row=developer();row.put("주 직무","");long id=validateDeveloper(row);
        mvc.perform(get("/api/uploads/{uploadId}/rows",id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].row").value(5));
        mvc.perform(get("/api/uploads/{uploadId}/issues",id).param("severity","ERROR"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].column").value("주 직무"));
        noBusiness();verify(mapper,never()).insertReview(any());verify(legacy,never()).insertIssue(any());
    }
    @Test void templateDownloadSideEffectsNeverPersistBusinessOrInspectionRows()throws Exception {
        byte[] bytes=mvc.perform(get("/api/uploads/templates/B.8"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(3);
            assertThat(book.getSheetAt(0).getRow(3).getPhysicalNumberOfCells()).isPositive();
        }
        noBusiness();verify(mapper,never()).insertReview(any());verify(legacy,never()).insertIssue(any());
    }
    @Test void validationSalaryRequiresMonth()throws Exception {
        mvc.perform(multipart("/api/uploads/validate")
            .file(new MockMultipartFile("file","급여.xlsx","application/octet-stream",workbook("B.1",List.of(salary()))))
            .param("form_type","B.1"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));noBusiness();
    }
    @Test void validationMissingFileHasLiteralPostContractWithoutBusinessWrites() throws Exception {
        mvc.perform(post("/api/uploads/validate").contentType("multipart/form-data").param("form_type", "B.8"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.field").value("file"))
            .andExpect(jsonPath("$.message").value("파일을 선택해 주세요."))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        noBusiness(); verify(mapper, never()).nextUploadId();
    }
    @Test void developerValidationRejectsSalaryMonthBeforeCreatingAReview()throws Exception {
        validate("B.8","2026-10",List.of(developer()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"))
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verify(mapper,never()).nextUploadId();noBusiness();
    }
    @Test void salaryApplicationReturnsOnlyThePublicMonthlyCostFields()throws Exception {
        actor();
        when(mapper.insertSalary(any())).thenReturn(Map.of("mth_cst_id",1L,"cst_ym","2026-10",
            "emp_se_cd","FT","slry_amt",4000000L,"alwnc_amt",200000L,"sins_cmpny_amt",420000L,
            "rtrpay_acml_amt",350000L,"indv_lbr_cst_amt",4970000L,"real_pay_amt",4200000L));
        long id=data(validate("B.1","2026-10",List.of(salary()))).get("upload_id").asLong();
        JsonNode applied=data(mvc.perform(post("/api/uploads/{uploadId}/apply",id)));
        Map<?,?> cost=json.convertValue(applied.path("monthly_costs").get(0),Map.class);
        assertThat(cost.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder("cst_ym","emp_se_cd",
            "slry_amt","alwnc_amt","sins_cmpny_amt","rtrpay_acml_amt","indv_lbr_cst_amt","real_pay_amt");
        assertThat(applied.path("monthly_costs").get(0).path("indv_lbr_cst_amt").asLong()).isEqualTo(4970000L);
    }
    @Test void validationBusinessClosedSalaryMonthHasNoWrites()throws Exception {
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        mvc.perform(multipart("/api/uploads/validate")
            .file(new MockMultipartFile("file","급여.xlsx","application/octet-stream",workbook("B.1",List.of(salary()))))
            .param("form_type","B.1").param("month","2026-09"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));noBusiness();
    }
    @Test void closedAfterValidationHasNoWrites()throws Exception{actor();long id=data(validate("B.1","2026-10",List.of(salary()))).get("upload_id").asLong();when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");mvc.perform(post("/api/uploads/"+id+"/apply")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));noBusiness();}
    @Test void salaryDefaultsSnapshotReplacementAndExpenseAudit()throws Exception{actor();var row=salary();row.put("팀비","50000");when(mapper.salaryCount("2026-10")).thenReturn(3L);long id=data(validate("B.1","2026-10",List.of(row))).get("upload_id").asLong();assertThat(service.result(id).overwrite_notice()).contains("3명");noBusiness();mvc.perform(post("/api/uploads/"+id+"/apply")).andExpect(status().isOk()).andExpect(jsonPath("$.data.ovwr_yn").value("Y"));verify(mapper).insertSalary(argThat(w->w.input().get("alwnc_amt").equals(200000L)&&w.input().get("rtrpay_acml_amt").equals(350000L)&&w.input().get("pstn_cd").equals("TL")));verify(mapper).deleteSalaries("2026-10");verify(mapper).deleteExpenses("2026-10");verify(mapper).insertExpense(argThat(w->w.amount()==50000&&w.developer()==101));verify(mapper).history(argThat(w->w.table().equals("cst.mth_cst")&&w.id().equals("2026-10")&&w.type().equals("UPDATE")&&w.reason().equals("급여 업로드 #"+id+" · 1명")));}
    @ParameterizedTest @CsvSource({"월 급여,''","월 급여,-1","월 급여,abc","수당,-1","수당,1.5","팀비,-1","4대보험(회사),''"}) void invalidSalaryInputsBlockReplacement(String column,String value)throws Exception{actor();var row=salary();row.put(column,value);long id=data(validate("B.1","2026-10",List.of(row))).get("upload_id").asLong();assertThat(service.result(id).can_apply()).isFalse();mvc.perform(post("/api/uploads/"+id+"/apply")).andExpect(status().isBadRequest());noBusiness();}
    @Test void oneWonComparisonIsWarningAndDescription()throws Exception{actor();var row=salary();row.put("개인별 원가","4970001");long id=data(validate("B.1","2026-10",List.of(row))).get("upload_id").asLong();mvc.perform(get("/api/uploads/"+id+"/issues").param("severity","WARNING")).andExpect(jsonPath("$.data.items[0].column").value("개인별 원가"));mvc.perform(post("/api/uploads/"+id+"/apply")).andExpect(status().isOk());verify(mapper).insertSalary(argThat(w->w.input().get("excel_diff_desc").toString().contains("4970001")));}
    @Test void allowanceChangeRequiresExplicitReview()throws Exception{actor();long id=data(validate("B.1","2026-10",List.of(salary()))).get("upload_id").asLong();when(mapper.allowance("TL")).thenReturn("210000");mvc.perform(post("/api/uploads/"+id+"/apply")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VALIDATION"));noBusiness();}
    @Test void zeroRowsCannotDeleteMonth()throws Exception{validate("B.1","2026-10",List.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_FILE"));noBusiness();}
    @ParameterizedTest @ValueSource(strings={"B.8","B.1"}) void templateLayoutAndTextManagement(String type)throws Exception{var response=mvc.perform(get("/api/uploads/templates/"+type)).andExpect(status().isOk()).andReturn().getResponse();try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))){assertThat(book.getNumberOfSheets()).isEqualTo(3);var sheet=book.getSheetAt(0);int column=PersonnelUploadForm.headers(type).indexOf("관리번호");assertThat(sheet.getColumnStyle(column).getDataFormatString()).isEqualTo("@");assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).startsWith("예시)");if(type.equals("B.1")){assertThat(sheet.getRow(3).getCell(9).getStringCellValue()).isEqualTo("월 급여 *");assertThat(column).isEqualTo(23);assertThat(sheet.getRow(3).getCell(25).getStringCellValue()).isEqualTo("근로소득세");}}}
    @Test void malformedAndRenamedFileRejected()throws Exception{mvc.perform(multipart("/api/uploads/validate").file(new MockMultipartFile("file","wrong.xlsx","application/octet-stream","not xlsx".getBytes())).param("form_type","B.8")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));noBusiness();}
    @Test void revalidationValidationRejectsZeroIdWithoutSideEffects()throws Exception {
        mvc.perform(post("/api/uploads/0/revalidate")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("uploadId")).andExpect(jsonPath("$.requestId").isNotEmpty());
        noBusiness();
    }
    @Test void applyValidationRejectsErroredRowsWithoutPartialWrites()throws Exception {
        var row=developer();row.put("주 직무","");long id=validateDeveloper(row);
        mvc.perform(post("/api/uploads/{uploadId}/apply",id)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("uploadId")).andExpect(jsonPath("$.message").isNotEmpty());
        noBusiness();verify(mapper,never()).insertReview(any());
        mvc.perform(get("/api/uploads/{uploadId}",id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.error_count").value(1)).andExpect(jsonPath("$.data.can_apply").value(false));
    }
    @Test void rowsValidationRejectsInvalidSeverityWithoutWrites()throws Exception {
        long id=validateDeveloper(developer());
        mvc.perform(get("/api/uploads/{uploadId}/rows",id).param("severity","UNKNOWN"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("severity"));noBusiness();
    }
    @Test void issuesValidationRejectsInvalidSeverityWithoutWrites()throws Exception {
        long id=validateDeveloper(developer());
        mvc.perform(get("/api/uploads/{uploadId}/issues",id).param("severity","NORMAL"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("severity"));noBusiness();
    }
    @Test void errorsValidationRejectsZeroIdWithoutWrites()throws Exception {
        mvc.perform(get("/api/uploads/0/errors")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("uploadId")).andExpect(jsonPath("$.requestId").isNotEmpty());noBusiness();
    }
    @Test void formulaIsRowErrorNotEvaluatedBusinessInput()throws Exception{byte[] source=workbook("B.8",List.of(developer()));try(var book=new XSSFWorkbook(new ByteArrayInputStream(source));var out=new ByteArrayOutputStream()){book.getSheetAt(0).getRow(4).getCell(1).setCellFormula("1+1");book.write(out);var result=data(mvc.perform(multipart("/api/uploads/validate").file(new MockMultipartFile("file","formula.xlsx","application/octet-stream",out.toByteArray())).param("form_type","B.8")));assertThat(result.get("error_count").asInt()).isEqualTo(1);}noBusiness();}
    @ParameterizedTest @CsvSource({"이름,51","연락처,31","비고,501"}) void developerLengthsAreRowErrors(String column,int length)throws Exception{var row=developer();row.put(column,"가".repeat(length));long id=validateDeveloper(row);assertThat(service.result(id).can_apply()).isFalse();mvc.perform(get("/api/uploads/"+id+"/issues")).andExpect(jsonPath("$.data.items[0].column").value(column));noBusiness();}
    @ParameterizedTest @ValueSource(strings={"lower","A_1","ABCDEFGHIJKLMNOPQRSTU"}) void invalidManagementNumbersDoNotWrite(String number)throws Exception{var row=developer();row.put("관리번호",number);long id=validateDeveloper(row);assertThat(service.result(id).can_apply()).isFalse();noBusiness();}
    @Test void outsourcedRequiresUniqueActivePartnerAndAliasWarns()throws Exception{var row=developer();row.put("직원구분","업체");row.put("소속","협력사");long rejected=validateDeveloper(row);assertThat(service.result(rejected).can_apply()).isFalse();when(mapper.partners("협력사")).thenReturn(List.of(Map.of("ptnr_id",8L)));long valid=validateDeveloper(row);assertThat(service.result(valid).can_apply()).isTrue();mvc.perform(post("/api/uploads/"+valid+"/apply")).andExpect(status().isOk());verify(mapper).insertDeveloper(argThat(w->w.input().get("vndr_ptnr_id").equals(8L)&&w.input().get("emp_se_cd").equals("OS")));}
    @Test void ambiguousPartnerIsError()throws Exception{var row=developer();row.put("직원구분","OS");row.put("소속","협력사");when(mapper.partners("협력사")).thenReturn(List.of(Map.of("ptnr_id",8L),Map.of("ptnr_id",9L)));long id=validateDeveloper(row);assertThat(service.result(id).can_apply()).isFalse();noBusiness();}
    @Test void salesPartCannotOfferMapping()throws Exception{var row=developer();row.put("영업 파트","미등록");long id=validateDeveloper(row);mvc.perform(get("/api/uploads/"+id+"/issues")).andExpect(jsonPath("$.data.items[0].mapping_allowed").value(false));noBusiness();}
    @ParameterizedTest @CsvSource({"2026-02-30,2026-03-01","2026-10-10,2026-10-09",",2026-10-10"}) void workStatusDatesBlockInvalidRows(String start,String end)throws Exception{var row=developer();row.put("상태","RESIGN");row.put("시작일",start==null?"":start);row.put("종료일",end);long id=validateDeveloper(row);assertThat(service.result(id).can_apply()).isFalse();noBusiness();}
    @Test void resignationCreatesOwnedStatusAuditAndDate()throws Exception{var row=developer();row.put("상태","RESIGN");row.put("시작일","2026-10-01");when(mapper.insertStatus(any())).thenReturn(33L);long id=validateDeveloper(row);mvc.perform(post("/api/uploads/"+id+"/apply")).andExpect(status().isOk());verify(mapper).insertDeveloper(argThat(w->w.input().get("rsgn_dt").equals(LocalDate.parse("2026-10-01"))));verify(mapper).history(argThat(w->w.table().equals("mst.dev_work_stts_hist")&&w.id().equals("33")&&w.after().contains("dev_id")));}
    @ParameterizedTest @ValueSource(strings={"미등록","중지","이름"}) void salaryIdentityErrorsBlockReplacement(String rule)throws Exception{actor();var row=salary();if(rule.equals("미등록"))row.put("관리번호","UNKNOWN");if(rule.equals("이름"))row.put("이름","다른 이름");if(rule.equals("중지")){var d=new LinkedHashMap<>(mapper.developer("K-0101"));d.put("use_yn","N");when(mapper.developer("K-0101")).thenReturn(d);}long id=data(validate("B.1","2026-10",List.of(row))).get("upload_id").asLong();assertThat(service.result(id).can_apply()).isFalse();noBusiness();}
    @Test void salaryTypeAndAffiliationDifferenceOnlyWarnsAndDoesNotChangeDeveloper()throws Exception{actor();var row=salary();row.put("구분","CT");row.put("소속","다른 소속");long id=data(validate("B.1","2026-10",List.of(row))).get("upload_id").asLong();assertThat(service.result(id).can_apply()).isTrue();mvc.perform(post("/api/uploads/"+id+"/apply")).andExpect(status().isOk());verify(mapper).insertSalary(argThat(w->w.input().get("emp_se_cd").equals("CT")));verify(mapper,never()).insertDeveloper(any());}
    @Test void earlierResignationOnlyWarnsForActiveDeveloper()throws Exception{actor();var d=new LinkedHashMap<>(mapper.developer("K-0101"));d.put("rsgn_dt",java.sql.Date.valueOf("2026-08-01"));when(mapper.developer("K-0101")).thenReturn(d);long id=data(validate("B.1","2026-10",List.of(salary()))).get("upload_id").asLong();assertThat(service.result(id).can_apply()).isTrue();mvc.perform(get("/api/uploads/"+id+"/issues")).andExpect(jsonPath("$.data.items[0].message").value("이 달 전에 퇴사한 사람입니다(퇴사일 2026-08-01)."));}
    @Test void outsourcedVatDefaultAndIgnoredNegativeExpenses()throws Exception{actor();var row=salary();row.put("구분","OS");row.put("월 급여","5000000");row.put("팀비","-3.1");when(mapper.preview(any())).thenReturn(Map.of("indv_lbr_cst_amt",5000000L,"real_pay_amt",5500000L));long id=data(validate("B.1","2026-10",List.of(row))).get("upload_id").asLong();assertThat(service.result(id).can_apply()).isTrue();mvc.perform(post("/api/uploads/"+id+"/apply")).andExpect(status().isOk());verify(mapper).insertSalary(argThat(w->w.input().get("vat_amt").equals(500000L)&&w.input().get("alwnc_amt").equals(0L)&&w.input().get("rtrpay_acml_amt").equals(0L)));verify(mapper,never()).insertExpense(any());}
}
