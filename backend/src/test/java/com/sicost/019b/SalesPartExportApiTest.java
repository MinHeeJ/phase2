package com.sicost.salespartexport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.salesparts.*;
import com.sicost.salespartsops.*;
import com.sicost.salespartsops.SalesPartLifecycleModels.SalesPartAccess;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
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

@WebMvcTest(SalesPartLifecycleController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,SalesPartLifecycleService.class,
    SalesPartService.class,SalesPartExportService.class,ExcelExportService.class,
    CurrentUser.class,PermissionService.class,SalesPartExportApiTest.Time.class})
class SalesPartExportApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean SalesPartLifecycleMapper mapper;
    @MockBean SalesPartMapper reads;
    @SpyBean ExcelExportService excel;
    static final String PATH="/api/sales-parts/export";
    static final String MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final List<String> COLUMNS=List.of("bsn_part_nm","currentStandard.ldr_nm","currentStandard.prft_dstrb_rt",
        "currentStandard.bsn_fee_amt","currentStandard.aply_bgn_ym","scheduledChanges","peopleCount","projectCount","use_yn");

    Map<String,Object> row(int id) {
        var row=new LinkedHashMap<String,Object>();
        row.put("bsn_part_id",Integer.toString(id));row.put("bsn_part_nm","영업 파트 "+id);
        row.put("currentStandard.ldr_nm","파트장 "+id);row.put("currentStandard.prft_dstrb_rt",new BigDecimal("42.25"));
        row.put("currentStandard.bsn_fee_amt",1234567L);row.put("currentStandard.aply_bgn_ym","2026-10");
        row.put("scheduledChanges","2026-11부터 45.00%");row.put("peopleCount",2L);row.put("projectCount",3L);
        row.put("use_yn",id==101?"N":"Y");return row;
    }
    byte[] download(String... ids)throws Exception {
        var request=get(PATH).param("columns",COLUMNS.toArray(String[]::new));
        if(ids.length>0)request.param("ids",ids);
        return mvc.perform(request).andExpect(status().isOk()).andExpect(content().contentType(MIME))
            .andReturn().getResponse().getContentAsByteArray();
    }
    void noDownload(){verify(mapper,never()).access(any());}

    @Test void secondOfThreePagesDownloadsEveryRowIncludingStoppedAndOneAudit()throws Exception {
        when(mapper.exportRows(any())).thenReturn(IntStream.rangeClosed(1,101).mapToObj(this::row).toList());
        var response=mvc.perform(get(PATH).param("columns",COLUMNS.toArray(String[]::new)).param("page","2").param("size","50"))
            .andExpect(status().isOk()).andExpect(content().contentType(MIME)).andReturn().getResponse();
        assertThat(java.net.URLDecoder.decode(response.getHeader("Content-Disposition"),java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("attachment; filename*=UTF-8''영업파트_20261023.xlsx");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet=book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()-3).isEqualTo(101);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("조회 조건: 전체 (중지 포함)");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("내려받은 사람: 기본 사용자 · 2026-10-23 00:01");
            assertThat(IntStream.range(0,9).mapToObj(i->sheet.getRow(3).getCell(i).getStringCellValue()).toList())
                .containsExactly("파트명","파트장","이익 배분율","영업비(1인·월)","적용 시작","예정된 변경","소속 인력","진행 프로젝트","사용 여부");
            assertThat(sheet.getRow(104).getCell(8).getStringCellValue()).isEqualTo("N");
            var money=sheet.getRow(4).getCell(3);
            assertThat(money.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(money.getNumericCellValue()).isEqualTo(1234567);
            assertThat(money.getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(sheet.getRow(4).getCell(2).getNumericCellValue()).isEqualTo(42.25);
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)4);
            var style=sheet.getRow(3).getCell(0).getCellStyle();
            assertThat(style.getFillForegroundColor()).isEqualTo(IndexedColors.GREY_25_PERCENT.getIndex());
            assertThat(book.getFontAt(style.getFontIndex()).getBold()).isTrue();
        }
        verify(mapper).exportRows(argThat(q->q.ids()==null&&q.month().equals("2026-10")&&q.today().equals(LocalDate.of(2026,10,23))));
        var audit=ArgumentCaptor.forClass(SalesPartAccess.class);verify(mapper).access(audit.capture());
        assertThat(audit.getValue().filename()).isEqualTo("영업파트_20261023.xlsx");assertThat(audit.getValue().actor()).isNull();
        var detail=json.readTree(audit.getValue().detail());
        assertThat(detail.get("columns")).isEqualTo(json.valueToTree(COLUMNS));assertThat(detail.get("ids").isNull()).isTrue();
        assertThat(detail.get("conditions").size()).isZero();
        verify(mapper,never()).state(any());verify(mapper,never()).audit(any());
    }
    @Test void selectedIdsAreDeduplicatedAndOnlyTwoRowsAreWritten()throws Exception {
        when(mapper.exportRows(any())).thenReturn(List.of(row(2),row(101)));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(download("2","101","2")))) {
            var sheet=book.getSheetAt(0);assertThat(sheet.getLastRowNum()-3).isEqualTo(2);
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("영업 파트 2");
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("영업 파트 101");
        }
        verify(mapper).exportRows(argThat(q->q.ids().equals(List.of(2L,101L))));
        var audit=ArgumentCaptor.forClass(SalesPartAccess.class);verify(mapper).access(audit.capture());
        assertThat(json.readTree(audit.getValue().detail()).get("ids")).isEqualTo(json.valueToTree(List.of("2","101")));
    }
    @Test void columnsPreserveFirstAppearanceAndDoNotIncludeUnrequestedValues()throws Exception {
        when(mapper.exportRows(any())).thenReturn(List.of(row(2)));
        var response=mvc.perform(get(PATH).param("columns","use_yn","bsn_part_nm","use_yn"))
            .andExpect(status().isOk()).andReturn().getResponse();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getRow(3).getLastCellNum()).isEqualTo((short)2);
            assertThat(book.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).isEqualTo("사용 여부");
        }
    }
    @Test void missingColumnsFailsBeforeQueryOrAudit()throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));
        verify(mapper,never()).exportRows(any());noDownload();
    }
    @Test void unknownColumnCannotReachSqlOrAudit()throws Exception {
        mvc.perform(get(PATH).param("columns","bsn_part_nm; DROP TABLE mst.bsn_part"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));
        verify(mapper,never()).exportRows(any());noDownload();
    }
    @ParameterizedTest @ValueSource(strings={"","0","-1","abc","9223372036854775808"})
    void malformedSelectionFailsAsIdsField(String id)throws Exception {
        mvc.perform(get(PATH).param("columns","bsn_part_nm").param("ids",id))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));
        verify(mapper,never()).exportRows(any());noDownload();
    }
    @Test void oneMissingIdRejectsTheWholeDownload()throws Exception {
        when(mapper.exportRows(any())).thenReturn(List.of(row(2)));
        mvc.perform(get(PATH).param("columns","bsn_part_nm").param("ids","2","999"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));
        verify(excel,never()).create(anyString(),anyString(),any(),anyList(),anyList());noDownload();
    }
    @Test void nullCurrentStandardExportsBlankInsteadOfScheduledMoney()throws Exception {
        var future=row(2);future.put("currentStandard.bsn_fee_amt",null);future.put("currentStandard.ldr_nm",null);
        when(mapper.exportRows(any())).thenReturn(List.of(future));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(download()))) {
            assertThat(book.getSheetAt(0).getRow(4).getCell(3).getStringCellValue()).isEmpty();
            assertThat(book.getSheetAt(0).getRow(4).getCell(1).getStringCellValue()).isEmpty();
            assertThat(book.getSheetAt(0).getRow(4).getCell(5).getStringCellValue()).contains("2026-11");
        }
    }
    @Test void emptyResultStillProducesAValidWorkbookAndOneAudit()throws Exception {
        when(mapper.exportRows(any())).thenReturn(Collections.emptyList());
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(download()))) {assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(3);}
        verify(mapper).access(any());
    }
    @Test void formulaLikeNameIsTextWithoutExternalLinks()throws Exception {
        var formula=row(2);formula.put("bsn_part_nm","=WEBSERVICE(\"https://invalid.test\")");
        when(mapper.exportRows(any())).thenReturn(List.of(formula));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(download()))) {
            assertThat(book.getSheetAt(0).getRow(4).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(book.getExternalLinksTable()).isEmpty();
        }
    }
    @Test void fileCreationFailureReturnsSafeJsonAndDoesNotAudit()throws Exception {
        when(mapper.exportRows(any())).thenReturn(List.of(row(2)));
        doThrow(new IllegalStateException("private server path")).when(excel).create(anyString(),anyString(),any(),anyList(),anyList());
        mvc.perform(get(PATH).param("columns","bsn_part_nm")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("SERVER_ERROR")).andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
        noDownload();
    }
    @Test void auditFailureNeverReturnsSuccessfulBinary()throws Exception {
        when(mapper.exportRows(any())).thenReturn(List.of(row(2)));
        doThrow(new IllegalStateException("private audit failure")).when(mapper).access(any());
        mvc.perform(get(PATH).param("columns","bsn_part_nm")).andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(jsonPath("$.success").value(false));
    }
}
