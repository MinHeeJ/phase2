package com.sicost.businessplan2;

import com.sicost.apiops.*;
import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
import com.sicost.uploadsops.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={UploadController.class,UploadResultController.class})
@Import({RequestIdFilter.class,ApiExceptionHandler.class,UploadResultService.class})
class BusinessPlanUploadReviewApiTest {
    @Autowired MockMvc mvc;
    @MockBean UploadService uploads;
    @MockBean UploadResultMapper mapper;
    @MockBean UploadInspectionContexts contexts;
    @MockBean PermissionService permissions;
    @MockBean UploadReviewAdapter adapter;
    @BeforeEach void setup() throws Exception {
        when(adapter.supports(77)).thenReturn(true);when(adapter.supportsForm("B.10")).thenReturn(true);
        when(adapter.permissionKey("upload")).thenReturn("UI-18.upload");
        when(adapter.permissionKey("download")).thenReturn("UI-18.download");
        var result=new PersonnelUploadModels.Result(77,"B.10",null,1,0,0,true,0,"미마감 월 전체 교체","/api/uploads/77/issues",true);
        when(adapter.result(77)).thenReturn(result);when(adapter.revalidate(77)).thenReturn(result);
        when(adapter.issues(eq(77L),any(),any(),any())).thenReturn(new DeveloperPaging(0,50,"row,asc").result(List.of(),0));
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=new java.io.ByteArrayOutputStream()) {
            book.createSheet("사업계획");book.write(out);
            when(adapter.template()).thenReturn(new UploadModels.UploadFile("사업계획.xlsx",out.toByteArray()));
            when(adapter.errors(77)).thenReturn(new UploadModels.UploadFile("사업계획_오류.xlsx",out.toByteArray()));
        }
    }
    @ParameterizedTest @CsvSource({"GET,/api/uploads/77,upload","GET,/api/uploads/77/issues,upload",
        "GET,/api/uploads/77/rows,upload","POST,/api/uploads/77/revalidate,upload","POST,/api/uploads/77/apply,upload",
        "GET,/api/uploads/77/errors,download","GET,/api/uploads/templates/B.10,download"})
    void commonPathsUseAdapterPermissionWithoutAuthentication(String method,String path,String action)throws Exception {
        mvc.perform(method.equals("GET")?get(path):post(path)).andExpect(status().isOk());
        verify(permissions).can("UI-18."+action);verify(permissions,never()).can("UI-13."+action);
    }
    @Test void existingAdaptersKeepTheirOriginalPermissionByDefault() {
        var legacy=mock(UploadReviewAdapter.class,CALLS_REAL_METHODS);
        assertThat(legacy.permissionKey("upload")).isEqualTo("UI-13.upload");
        assertThat(legacy.permissionKey("download")).isEqualTo("UI-13.download");
    }
}
