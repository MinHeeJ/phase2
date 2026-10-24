package com.sicost.partnerexport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import com.sicost.partnersops.*;
import com.sicost.partnersops.PartnerLifecycleModels.PartnerAccess;
import com.sicost.salesparts.SalesPartMapper;
import com.sicost.salesparts.SalesPartService;
import com.sicost.salespartsops.*;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers={PartnerLifecycleController.class,SalesPartLifecycleController.class})
@Import({RequestIdFilter.class,ApiExceptionHandler.class,PartnerLifecycleService.class,
    SalesPartLifecycleService.class,SalesPartExportService.class,SalesPartService.class,
    ExcelExportService.class,CurrentUser.class,PermissionService.class,PartnerExportApiTest.Time.class})
class PartnerExportApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PartnerLifecycleMapper partners;
    @MockBean SalesPartLifecycleMapper parts;
    @MockBean SalesPartMapper reads;
    @SpyBean ExcelExportService excel;

    @BeforeEach void rows() {
        when(partners.exportRows(any())).thenReturn(List.of(partner("1"),partner("2")));
        when(parts.exportRows(any())).thenReturn(List.of(part("1","Y"),part("2","N")));
    }
    Map<String,Object> partner(String id) {
        return Map.of("ptnr_id",id,"ptnr_nm","협력사 B","biz_reg_no","123-45-67890",
            "bank_cd","국민은행","acnt_no","001-23456789","dpstr_nm","협력사 B","projectCount",2L);
    }
    Map<String,Object> part(String id,String use) {
        return Map.of("bsn_part_id",id,"bsn_part_nm","영업 파트 "+id,"use_yn",use,
            "currentStandard.bsn_fee_amt",1500000L);
    }
    void noAccess() {verify(partners,never()).access(any());verify(parts,never()).access(any());}
    String nameColumn(String resource){return resource.equals("partners")?"ptnr_nm":"bsn_part_nm";}

    @Test void partnerWorkbookCarriesFullSeparateAccountsTextAndOneNormalizedAudit() throws Exception {
        byte[] bytes=mvc.perform(get("/api/partners/export").param("columns","ptnr_nm","paymentAccount","biz_reg_no","acnt_no","projectCount")
            .param("kind","VEND").param("useYn","").param("q","  협력사  ").param("ids","1","2","1"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment; filename*=UTF-8''")))
            .andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(5);
            assertThat(IntStream.range(0,6).mapToObj(i->sheet.getRow(3).getCell(i).getStringCellValue()).toList())
                .containsExactly("상호","은행","계좌번호","예금주","사업자번호","진행 프로젝트");
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("국민은행");
            assertThat(sheet.getRow(4).getCell(2).getStringCellValue()).isEqualTo("001-23456789");
            assertThat(sheet.getRow(4).getCell(3).getStringCellValue()).isEqualTo("협력사 B");
            assertThat(sheet.getRow(4).getCell(4).getStringCellValue()).isEqualTo("123-45-67890");
            assertThat(sheet.getRow(4).getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(4).getCell(5).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("조회 조건:","VEND","전체","협력사");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("내려받은 사람: 기본 사용자 · 2026-10-23 00:01");
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)4);
            assertThat(sheet.getRow(3).getCell(0).getCellStyle().getFillForegroundColor()).isEqualTo(IndexedColors.GREY_25_PERCENT.getIndex());
            assertThat(book.getFontAt(sheet.getRow(3).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
        }
        verify(partners).exportRows(argThat(q->q.kind().equals("VEND")&&q.useYn().isEmpty()&&q.q().equals("협력사")&&q.ids().equals(List.of("1","2"))));
        var audit=ArgumentCaptor.forClass(PartnerAccess.class);verify(partners).access(audit.capture());
        assertThat(audit.getValue().actor()).isNull();
        assertThat(audit.getValue().filename()).isEqualTo("거래처_20261023.xlsx");
        var detail=json.readTree(audit.getValue().detail());
        assertThat(detail.get("filename").asText()).isEqualTo(audit.getValue().filename());
        assertThat(detail.get("conditions").get("q").asText()).isEqualTo("협력사");
        assertThat(detail.get("columns")).isEqualTo(json.valueToTree(List.of("ptnr_nm","bank_cd","acnt_no","dpstr_nm","biz_reg_no","projectCount")));
        assertThat(audit.getValue().detail()).doesNotContain("001-23456789","예금주");
        verify(partners,never()).changeState(any());verify(partners,never()).audit(any());
    }
    @Test void sensitiveMarkersAreInheritedAfterExpansionWithoutReorderingOrDuplicates() {
        var columns=PartnerLifecycleService.columns(List.of("acnt_no","ptnr_nm","paymentAccount","bank_cd"));
        assertThat(columns).extracting(ExportColumn::key).containsExactly("acnt_no","ptnr_nm","bank_cd","dpstr_nm");
        assertThat(columns).filteredOn(ExportColumn::sensitive).extracting(ExportColumn::key).containsExactly("acnt_no","bank_cd","dpstr_nm");
        record Marked(@Sensitive String value,String label) {}
        assertThat(ExcelExportService.column(Marked.class,"value","값",false,v->v).sensitive()).isTrue();
        assertThat(ExcelExportService.column(Marked.class,"label","표기",false,v->v).sensitive()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"partners","sales-parts"})
    void entireResultIgnoresPageAndSize(String resource) throws Exception {
        if(resource.equals("partners"))when(partners.exportRows(any())).thenReturn(IntStream.range(1,152).mapToObj(i->partner(""+i)).toList());
        else when(parts.exportRows(any())).thenReturn(IntStream.range(1,152).mapToObj(i->part(""+i,"Y")).toList());
        var response=mvc.perform(get("/api/"+resource+"/export").param("columns",nameColumn(resource)).param("page","2").param("size","50"))
            .andExpect(status().isOk()).andReturn().getResponse();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(154);
        }
        if(resource.equals("partners"))verify(partners).exportRows(argThat(q->q.ids()==null&&q.useYn().equals("Y")));
        else verify(parts).exportRows(argThat(q->q.ids()==null&&q.month().equals("2026-10")));
    }
    @Test void stoppedSalesPartsRemainInFileAndMoneyIsNumericAndAuditedOnce() throws Exception {
        var response=mvc.perform(get("/api/sales-parts/export").param("columns","use_yn","currentStandard.bsn_fee_amt","bsn_part_nm","use_yn")
            .param("ids","1","2","1")).andExpect(status().isOk()).andReturn().getResponse();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet=book.getSheetAt(0);
            assertThat(sheet.getRow(3).getLastCellNum()).isEqualTo((short)3);
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("N");
            assertThat(sheet.getRow(4).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(4).getCell(1).getNumericCellValue()).isEqualTo(1500000);
            assertThat(sheet.getRow(4).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
        }
        verify(parts).exportRows(argThat(q->q.ids().equals(List.of(1L,2L))));
        verify(parts).access(argThat(a->a.filename().equals("영업파트_20261023.xlsx")&&a.actor()==null&&a.detail().contains("conditions")));
        verify(parts,never()).state(any());verify(parts,never()).audit(any());
    }
    @ParameterizedTest @ValueSource(strings={"partners","sales-parts"})
    void missingColumnsRejectsBeforeQuery(String resource) throws Exception {
        mvc.perform(get("/api/"+resource+"/export")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));
        verify(partners,never()).exportRows(any());verify(parts,never()).exportRows(any());noAccess();
    }
    @ParameterizedTest @ValueSource(strings={"partners","sales-parts"})
    void unknownColumnsNeverEnterSql(String resource) throws Exception {
        mvc.perform(get("/api/"+resource+"/export").param("columns","file_path_addr; DROP TABLE mst.ptnr"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));
        verify(partners,never()).exportRows(any());verify(parts,never()).exportRows(any());noAccess();
    }
    @ParameterizedTest @ValueSource(strings={"partners","sales-parts"})
    void malformedIdsRejectWholeDownload(String resource) throws Exception {
        mvc.perform(get("/api/"+resource+"/export").param("columns",nameColumn(resource)).param("ids","1","9223372036854775808"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));noAccess();
    }
    @ParameterizedTest @ValueSource(strings={"partners","sales-parts"})
    void missingOrOutOfFilterSelectionReturnsNoPartialFileOrAudit(String resource) throws Exception {
        mvc.perform(get("/api/"+resource+"/export").param("columns",nameColumn(resource)).param("ids","1","2","999"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));noAccess();
    }
    @Test void invalidKindIsFieldError() throws Exception {
        mvc.perform(get("/api/partners/export").param("columns","ptnr_nm").param("kind","UNKNOWN"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("kind"));noAccess();
    }
    @Test void invalidUseIsFieldError() throws Exception {
        mvc.perform(get("/api/partners/export").param("columns","ptnr_nm").param("useYn","UNKNOWN"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("useYn"));noAccess();
    }
    @ParameterizedTest @ValueSource(strings={"partners","sales-parts"})
    void emptyResultIsValidHeaderOnlyWorkbook(String resource) throws Exception {
        when(partners.exportRows(any())).thenReturn(List.of());when(parts.exportRows(any())).thenReturn(List.of());
        var response=mvc.perform(get("/api/"+resource+"/export").param("columns",nameColumn(resource)))
            .andExpect(status().isOk()).andReturn().getResponse();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(3);
        }
    }
    @ParameterizedTest @ValueSource(strings={"partners","sales-parts"})
    void formulaLikeTextIsNotExecutable(String resource) throws Exception {
        var row=Map.<String,Object>of(nameColumn(resource),"=WEBSERVICE(\"https://invalid.test\")");
        when(partners.exportRows(any())).thenReturn(List.of(row));when(parts.exportRows(any())).thenReturn(List.of(row));
        var response=mvc.perform(get("/api/"+resource+"/export").param("columns",nameColumn(resource)))
            .andExpect(status().isOk()).andReturn().getResponse();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getRow(4).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(book.getExternalLinksTable()).isEmpty();
        }
    }
    @ParameterizedTest @ValueSource(strings={"partners","sales-parts"})
    void fileGenerationFailureDoesNotAudit(String resource) throws Exception {
        doThrow(new IllegalStateException("internal path must not escape")).when(excel).create(anyString(),anyString(),any(),anyList(),anyList());
        mvc.perform(get("/api/"+resource+"/export").param("columns",nameColumn(resource)))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SERVER_ERROR"))
            .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));noAccess();
    }
    @ParameterizedTest @ValueSource(strings={"partners","sales-parts"})
    void auditFailureDoesNotReturnSuccessfulFile(String resource) throws Exception {
        doThrow(new IllegalStateException("audit failure")).when(partners).access(any());
        doThrow(new IllegalStateException("audit failure")).when(parts).access(any());
        mvc.perform(get("/api/"+resource+"/export").param("columns",nameColumn(resource)))
            .andExpect(status().isInternalServerError()).andExpect(content().contentTypeCompatibleWith("application/json"));
    }
}
