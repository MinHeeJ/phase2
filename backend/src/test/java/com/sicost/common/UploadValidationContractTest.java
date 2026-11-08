package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.businessplan2.*;
import com.sicost.priorprofit.*;
import com.sicost.financeentries.*;
import com.sicost.dispatchattendance2.*;
import com.sicost.common.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.yaml.snakeyaml.Yaml;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC binding boundary; workbook and DB effects are covered in each upload service's tests. */
class UploadValidationContractTest {
    BusinessPlanUploadService plans;
    PriorProfitUploadService profits;
    FinanceEntriesUploadService entries;
    DispatchAttendanceUploadService attendance;
    MockMvc mvc;

    @BeforeEach void setup() {
        plans=mock(BusinessPlanUploadService.class);
        profits=mock(PriorProfitUploadService.class);
        entries=mock(FinanceEntriesUploadService.class);
        attendance=mock(DispatchAttendanceUploadService.class);
        var json=new ObjectMapper();
        mvc=MockMvcBuilders.standaloneSetup(new BusinessPlanUploadController(plans,json),
            new PriorProfitUploadController(profits,json),new FinanceEntriesUploadController(entries,json),
            new DispatchAttendanceUploadController(attendance,json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @Test void businessPlanMissingFileReturnsFieldErrorWithoutApply()throws Exception {
        when(plans.validate(null)).thenThrow(new ApiException(400,"VALIDATION_ERROR","파일을 선택해 주세요.","file"));
        mvc.perform(post("/api/uploads/business-plan/validate").contentType(MediaType.MULTIPART_FORM_DATA))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("file")).andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(plans).validate(null);verifyNoMoreInteractions(plans);verifyNoInteractions(profits,entries,attendance);
    }
    @Test void priorProfitMissingFileReturnsFieldErrorWithoutApply()throws Exception {
        when(profits.validate(null)).thenThrow(new ApiException(400,"VALIDATION_ERROR","파일을 선택해 주세요.","file"));
        mvc.perform(post("/api/uploads/prior-profit/validate").contentType(MediaType.MULTIPART_FORM_DATA))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("file")).andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(profits).validate(null);verifyNoMoreInteractions(profits);verifyNoInteractions(plans,entries,attendance);
    }
    @Test void financeEntriesMissingFileReturnsFieldErrorWithoutApply()throws Exception {
        when(entries.validate(null)).thenThrow(new ApiException(400,"VALIDATION_ERROR","파일을 선택해 주세요.","file"));
        mvc.perform(post("/api/uploads/finance-entries/validate").contentType(MediaType.MULTIPART_FORM_DATA))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("file")).andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(entries).validate(null);verifyNoMoreInteractions(entries);verifyNoInteractions(plans,profits,attendance);
    }
    @Test void attendanceMissingFileFailsBindingBeforeAnyServiceCall()throws Exception {
        mvc.perform(post("/api/uploads/dispatch-attendance/validate").contentType(MediaType.MULTIPART_FORM_DATA).param("month","2026-10").param("projectId","1"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("file")).andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(attendance,plans,profits,entries);
    }
    @Test void uploadOperationsRemainInDurableClasspathContract()throws Exception {
        try(var input=new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            Map<?,?> document=new Yaml().load(input);
            Map<?,?> paths=(Map<?,?>)document.get("paths");
            assertThat(((Map<?,?>)paths.get("/api/uploads/business-plan/validate")).containsKey("post")).isTrue();
            assertThat(((Map<?,?>)paths.get("/api/uploads/prior-profit/validate")).containsKey("post")).isTrue();
            assertThat(((Map<?,?>)paths.get("/api/uploads/finance-entries/validate")).containsKey("post")).isTrue();

        }
    }
}
