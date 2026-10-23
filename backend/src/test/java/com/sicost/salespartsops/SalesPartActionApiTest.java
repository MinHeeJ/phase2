package com.sicost.salespartsops;

import static com.sicost.salespartsops.SalesPartActionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers=SalesPartActionController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,SalesPartActionService.class,CurrentUser.class,PermissionService.class,SalesPartActionApiTest.Time.class})
class SalesPartActionApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean SalesPartActionMapper mapper;
    Part part=new Part("2","영업1","N","Y",1);
    Standard current=new Standard("10","2026-01",null,"파트장",new BigDecimal("40.00"),1500000L,"적용 중");
    @BeforeEach void setup() {
        when(mapper.part("2")).thenAnswer(i->part);when(mapper.lockPart("2")).thenAnswer(i->part);
        when(mapper.standards(any())).thenReturn(List.of(current));when(mapper.lastClosed()).thenReturn("2026-09");
        when(mapper.insertStandard(any())).thenReturn("20");
        doAnswer(i->{PartWrite w=i.getArgument(0);part=new Part("2",w.value(),"N",part.use_yn(),1);return null;}).when(mapper).rename(any());
        doAnswer(i->{PartWrite w=i.getArgument(0);part=new Part("2",part.bsn_part_nm(),"N",w.value(),1);return null;}).when(mapper).state(any());
    }
    ResultActions standard(Map<String,Object> body)throws Exception {return mvc.perform(post("/api/sales-parts/2/standards").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));}
    Map<String,Object> valid() {return new HashMap<>(Map.of("aply_bgn_ym","2026-11","ldr_nm","새 파트장","prft_dstrb_rt",45,"bsn_fee_amt",1500000));}
    void unchanged() {verify(mapper,never()).history(any());verify(mapper,never()).state(any());verify(mapper,never()).rename(any());verify(mapper,never()).deleteStandard(any());verify(mapper,never()).closeStandard(any());verify(mapper,never()).insertStandard(any());}
    ResultActions rename(String name)throws Exception {return mvc.perform(put("/api/sales-parts/2/name").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("bsn_part_nm",name))));}
    @Test void publicOptionsAreFullArrayIncludingHqAndReadOnly()throws Exception {
        when(mapper.options()).thenReturn(List.of(new Option("2","영업1",false),new Option("1","본사",true)));
        mvc.perform(get("/api/sales-parts/options")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[1].hq").value(true));
        verify(mapper).options();unchanged();verify(mapper,never()).download(any());
    }
    @Test void publicDisableExcludesOptionAndAuditsOnlyOnce()throws Exception {
        mvc.perform(post("/api/sales-parts/2/disable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("N"));
        mvc.perform(post("/api/sales-parts/2/disable")).andExpect(status().isOk());
        verify(mapper,times(1)).state(new PartWrite("2","N",null));verify(mapper).history(new History("mst.bsn_part","2","DISABLE","use_yn","Y","N",null));
        verify(mapper,never()).deleteStandard(any());
    }
    @Test void publicEnablePreservesStandardAndIdAndIsIdempotent()throws Exception {
        part=new Part("2","영업1","N","N",1);
        mvc.perform(post("/api/sales-parts/2/enable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("Y")).andExpect(jsonPath("$.data.bsn_part_id").value("2")).andExpect(jsonPath("$.data.standards[0].part_std_hist_id").value("10"));
        mvc.perform(post("/api/sales-parts/2/enable")).andExpect(status().isOk());
        verify(mapper,times(1)).state(new PartWrite("2","Y",null));verify(mapper).history(new History("mst.bsn_part","2","UPDATE","use_yn","N","Y",null));verify(mapper,never()).deleteStandard(any());
    }
    @Test void missingDisableNeverCreatesAnything()throws Exception {
        mvc.perform(post("/api/sales-parts/999/disable")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));unchanged();
    }
    @Test void missingEnableNeverCreatesAnything()throws Exception {
        mvc.perform(post("/api/sales-parts/999/enable")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));unchanged();
    }
    @Test void missingStandardsNeverCreatesAnything()throws Exception {
        mvc.perform(post("/api/sales-parts/999/standards").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid())))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));unchanged();
    }
    @Test void missingRenameIs404()throws Exception {mvc.perform(put("/api/sales-parts/999/name").contentType(MediaType.APPLICATION_JSON).content("{\"bsn_part_nm\":\"새 이름\"}")).andExpect(status().isNotFound());unchanged();}
    @Test void threeAffiliatedPeopleBlockDisable()throws Exception {when(mapper.people(any())).thenReturn(3L);mvc.perform(post("/api/sales-parts/2/disable")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_IN_USE")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("소속 인력 3명")));unchanged();}
    @Test void oneOngoingProjectBlocksDisable()throws Exception {when(mapper.projects("2")).thenReturn(1L);mvc.perform(post("/api/sales-parts/2/disable")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_IN_USE"));unchanged();}
    @ParameterizedTest @ValueSource(strings={"disable","standards","name"})
    void hqWinsOverOtherBusinessChecks(String action)throws Exception {
        part=new Part("2","본사","Y","Y",999);when(mapper.people(any())).thenReturn(3L);when(mapper.lastClosed()).thenReturn("2026-12");
        var request=action.equals("name")?put("/api/sales-parts/2/name"):post("/api/sales-parts/2/"+action);
        request.contentType(MediaType.APPLICATION_JSON).content("{}");
        mvc.perform(request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HQ_PART_LOCKED"));unchanged();
    }
    @Test void publicRenameOnlyChangesPartAndAudits()throws Exception {
        mvc.perform(put("/api/sales-parts/2/name").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("bsn_part_nm","새 이름"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_part_nm").value("새 이름"));
        verify(mapper).history(new History("mst.bsn_part","2","UPDATE","bsn_part_nm","영업1","새 이름",null));verify(mapper,never()).state(any());verify(mapper,never()).insertStandard(any());
    }
    @Test void identicalNameDoesNotUpdateOrAudit()throws Exception {rename("영업1").andExpect(status().isOk());unchanged();}
    @ParameterizedTest @ValueSource(strings={""," ","1234567890123456789012345678901"})
    void invalidNameHasField(String value)throws Exception {rename(value).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bsn_part_nm"));unchanged();}
    @Test void duplicateNameIs409()throws Exception {when(mapper.nameExists(any())).thenReturn(true);rename("다른 이름").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PART_NAME"));unchanged();}
    @Test void nameThirtyCharactersIsAccepted()throws Exception {rename("가".repeat(30)).andExpect(status().isOk());}
    @Test void concurrentDuplicateIsConflict()throws Exception {doThrow(new org.springframework.dao.DuplicateKeyException("duplicate")).when(mapper).rename(any());rename("중복").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PART_NAME"));verify(mapper,never()).history(any());}
    @Test void publicStandardClosesPreviousAndAddsIndependentSnapshot()throws Exception {
        mvc.perform(post("/api/sales-parts/2/standards").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentStandard.prft_dstrb_rt").value(40));
        verify(mapper).closeStandard(new EndWrite("10","2026-10"));verify(mapper).history(new History("mst.bsn_part_std_hist","10","UPDATE","aply_end_ym",null,"2026-10",null));
        verify(mapper).insertStandard(argThat(w->w.actor()==null && w.input().prft_dstrb_rt().compareTo(new BigDecimal("45"))==0));
        verify(mapper).history(argThat(h->h.type().equals("INSERT") && h.after().contains("2026-11")));
    }
    @ParameterizedTest @CsvSource({"bsn_fee_amt,0","bsn_fee_amt,-1","bsn_fee_amt,1.5","prft_dstrb_rt,100.001","prft_dstrb_rt,-1","prft_dstrb_rt,101"})
    void invalidNumericStandardDoesNotWrite(String field,String value)throws Exception {Map<String,Object> body=valid();body.put(field,new BigDecimal(value));standard(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));unchanged();}
    @ParameterizedTest @ValueSource(strings={""," ","123456789012345678901234567890123456789012345678901"})
    void missingOrLongLeaderRejected(String leader)throws Exception {var body=valid();body.put("ldr_nm",leader);standard(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ldr_nm"));unchanged();}
    @ParameterizedTest @ValueSource(strings={"2026-13","2026-1","bad"})
    void invalidMonthRejected(String month)throws Exception {var body=valid();body.put("aply_bgn_ym",month);standard(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("aply_bgn_ym"));unchanged();}
    @ParameterizedTest @ValueSource(ints={0,100})
    void rateEndpointsAccepted(int rate)throws Exception {var body=valid();body.put("prft_dstrb_rt",rate);standard(body).andExpect(status().isOk());}
    @Test void sameStandardComparedToRequestedMonth()throws Exception {var body=valid();body.put("ldr_nm","파트장");body.put("prft_dstrb_rt",40);standard(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAME_STANDARD"));unchanged();}
    @Test void closedMonthPreservesRows()throws Exception {var body=valid();body.put("aply_bgn_ym","2026-09");standard(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));unchanged();}
    @Test void noClosedMonthRejectsBeforeCurrentSeoulMonth()throws Exception {when(mapper.lastClosed()).thenReturn(null);var body=valid();body.put("aply_bgn_ym","2026-09");standard(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));unchanged();}
    @Test void alreadyStartedRowCannotBeDeleted()throws Exception {var body=valid();body.put("aply_bgn_ym","2026-01");when(mapper.lastClosed()).thenReturn("2025-12");standard(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));unchanged();}
    @Test void replacingFutureDeletesBeforeClosingAndInsertingWithSummaries()throws Exception {
        Standard future=new Standard("11","2026-11",null,"예정 파트장",new BigDecimal("43"),1600000L,"예정");when(mapper.standards(any())).thenReturn(List.of(future,current));
        standard(valid()).andExpect(status().isOk());var order=inOrder(mapper);order.verify(mapper).deleteStandard("11");order.verify(mapper).history(argThat(h->h.type().equals("DELETE")&&h.before().contains("예정 파트장")));order.verify(mapper).closeStandard(new EndWrite("10","2026-10"));order.verify(mapper).insertStandard(any());
    }
    @Test void decemberChangeKeepsNovemberRowAndClosesItsPeriod()throws Exception {
        when(mapper.standards(any())).thenReturn(List.of(new Standard("11","2026-11",null,"예정 파트장",new BigDecimal("43"),1600000L,"예정"),new Standard("10","2026-01","2026-10","파트장",new BigDecimal("40"),1500000L,"적용 중")));
        var body=valid();body.put("aply_bgn_ym","2026-12");standard(body).andExpect(status().isOk());verify(mapper,never()).deleteStandard(any());verify(mapper).closeStandard(new EndWrite("11","2026-11"));
    }
    @Test void referencedFutureCannotBeDeleted()throws Exception {when(mapper.standards(any())).thenReturn(List.of(new Standard("11","2026-11",null,"예정",new BigDecimal("40"),1500000L,"예정")));when(mapper.standardReferences("11")).thenReturn(1L);standard(valid()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));unchanged();}
    @Test void nullableResponseKeysArePresentForNoCurrentAndNoClosed()throws Exception {when(mapper.standards(any())).thenReturn(List.of());when(mapper.lastClosed()).thenReturn(null);rename("새 이름").andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"currentStandard\":null"))).andExpect(content().string(org.hamcrest.Matchers.containsString("\"lastClosedMonth\":null"))).andExpect(jsonPath("$.data.firstAllowedMonth").value("2026-10"));}
    @ParameterizedTest @ValueSource(strings={"","columns=unknown","columns=bsn_part_nm&columns=bsn_part_nm","columns="})
    void invalidExportColumnsNeverAudits(String query)throws Exception {mvc.perform(get("/api/sales-parts/export?"+query)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));verify(mapper,never()).download(any());}
    @ParameterizedTest @ValueSource(strings={"","999","2,3"})
    void invalidExportIdsNeverAudits(String id)throws Exception {when(mapper.allParts()).thenReturn(List.of(part));mvc.perform(get("/api/sales-parts/export").param("columns","bsn_part_nm").param("ids",id)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));verify(mapper,never()).download(any());}
    @Test void exportFromPageTwoContainsAllThreePagesInVisibleColumnOrderAndNumericFee()throws Exception {
        List<Part> parts=new ArrayList<>();for(int i=1;i<=121;i++)parts.add(new Part(""+i,i==2?"=SUM(A1)":"파트"+i,"N",i==3?"N":"Y",i));when(mapper.allParts()).thenReturn(parts);
        byte[] bytes=mvc.perform(get("/api/sales-parts/export").param("page","2").param("size","50").param("columns","bsn_fee_amt","bsn_part_nm","use_yn"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment; filename*=UTF-8''"))).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet=book.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(124);assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("영업비(1인·월)");assertThat(sheet.getRow(4).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);assertThat(sheet.getRow(4).getCell(0).getCellStyle().getDataFormatString()).isEqualTo("#,##0");assertThat(sheet.getRow(5).getCell(1).getCellType()).isEqualTo(CellType.STRING);assertThat(sheet.getRow(6).getCell(2).getStringCellValue()).isEqualTo("중지");assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();assertThat(book.getFontAt(sheet.getRow(3).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains("기본 사용자","2026-10-22 09:00");
        }
        var audit=ArgumentCaptor.forClass(Download.class);verify(mapper,times(1)).download(audit.capture());assertThat(audit.getValue().filename()).isEqualTo("영업파트_20261022.xlsx");assertThat(audit.getValue().actor()).isNull();assertThat(json.readTree(audit.getValue().detail()).get("rowCount").asInt()).isEqualTo(121);unchanged();
    }
    @Test void selectedTwoExportDoesNotApplyPageAndExcludesOtherRows()throws Exception {
        when(mapper.allParts()).thenReturn(List.of(part,new Part("3","세번째","N","Y",2),new Part("4","네번째","N","Y",3)));
        byte[] bytes=mvc.perform(get("/api/sales-parts/export").param("columns","bsn_part_nm").param("ids","2","4").param("page","2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {var sheet=book.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(5);assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("네번째");}
    }
}
