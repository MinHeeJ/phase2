package com.sicost.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.assignments.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.CurrentUser;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.LongStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.board.WorkforceBoardModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=WorkforceBoardController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,WorkforceBoardService.class,
    WorkforceBoardExportService.class,ExcelExportService.class,CurrentUser.class,WorkforceBoardApiTest.Time.class})
class WorkforceBoardApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"),ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean WorkforceBoardMapper mapper;
    @MockBean AssignmentMapper assignments;
    @BeforeEach void setup() {
        when(mapper.people(any())).thenAnswer(call -> {
            Query q=call.getArgument(0);
            return "NO_RESULT".equals(q.job())?List.of():List.of(person(1));
        });
        when(mapper.months(any())).thenReturn(List.of());
        when(mapper.leaves(any())).thenReturn(List.of());
        when(mapper.temporary(any())).thenReturn(List.of());
        when(assignments.list(any())).thenReturn(List.of());
    }
    private Person person(long id) { return new Person(id,"개발자 "+id,"K-"+id,"BE","S",null); }
    @Test void anonymousHappyPathHasTwelveCellsAndFilteredTotals() throws Exception {
        mvc.perform(get("/api/workforce/board").param("year","2026").param("job","BE").param("grade","S"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items[0].months.length()").value(12))
            .andExpect(jsonPath("$.data.items[0].months[0].status").value("대기"))
            .andExpect(jsonPath("$.data.monthly_totals[0].free_people").value(1));
        verify(mapper).people(argThat(q -> "BE".equals(q.job()) && "S".equals(q.grade())));
        verify(mapper,never()).download(any());
    }
    @ParameterizedTest @ValueSource(strings={"year=0","year=10000","year=2026-13","year=abc","sort=salary,asc","sort=dev_nm,up","page=-1","size=21","job=bad!","emp_se_cd=UNKNOWN","free_only=yes","month=2026-13"})
    void invalidBoardQueryHasFieldMessageAndRequestIdWithoutSourceReads(String entry) throws Exception {
        String[] pair=entry.split("=",2);
        var request=get("/api/workforce/board").param("year","2026");
        if(pair[0].equals("year"))request=get("/api/workforce/board");
        mvc.perform(request.param(pair[0],pair[1])).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value(pair[0])).andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(mapper,assignments);
    }
    @ParameterizedTest @ValueSource(strings={"year=0","year=2026-13","emp_se_cd=UNKNOWN","skill=bad!","free_only=yes","sort=dev_id,asc","page=0","size=50"})
    void invalidExportQueryNeverWritesHistory(String entry) throws Exception {
        String[] pair=entry.split("=",2);
        var request=get("/api/workforce/board/export").param("year","2026");
        if(pair[0].equals("year"))request=get("/api/workforce/board/export");
        mvc.perform(request.param(pair[0],pair[1])).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value(pair[0])).andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(mapper,assignments);
    }
    @Test void missingYearRejectedOnBothOperations() throws Exception {
        for(String path:List.of("/api/workforce/board","/api/workforce/board/export"))
            mvc.perform(get(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("year"));
    }
    @Test void paginationDoesNotCutMonthlyTotalsAndSortIsStable() throws Exception {
        doReturn(LongStream.rangeClosed(1,61).mapToObj(this::person).toList()).when(mapper).people(any());
        mvc.perform(get("/api/workforce/board").param("year","2026").param("page","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(61))
            .andExpect(jsonPath("$.data.items.length()").value(11))
            .andExpect(jsonPath("$.data.items[0].dev_id").value(51))
            .andExpect(jsonPath("$.data.monthly_totals[0].free_people").value(61))
            .andExpect(jsonPath("$.data.monthly_totals[0].remaining_mm").value(61));
    }
    @Test void emptyResultStillHasTwelveGlobalTotals() throws Exception {
        mvc.perform(get("/api/workforce/board").param("year","2026").param("job","NO_RESULT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.monthly_totals.length()").value(12));
    }
    @Test void repeatedReadPreservesValuesAndDoesNotWrite() throws Exception {
        String first=mvc.perform(get("/api/workforce/board").param("year","2026"))
            .andReturn().getResponse().getContentAsString();
        String next=mvc.perform(get("/api/workforce/board").param("year","2026"))
            .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(first).get("data")).isEqualTo(json.readTree(next).get("data"));
        verify(mapper,never()).download(any());
        verify(assignments,never()).month(any());
        verify(assignments,never()).insert(any());
    }
    @Test void temporaryOverlayNeverChangesConfirmedFillOrFreePeople() throws Exception {
        when(mapper.temporary(any())).thenReturn(List.of(new TemporarySource(3,4,1,"2026-01","2026-12",
            new BigDecimal("0.75"),"MANUAL",null,"ACTIVE",null,"영업 기회")));
        mvc.perform(get("/api/workforce/board").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].months[0].status").value("가배정"))
            .andExpect(jsonPath("$.data.items[0].months[0].confirmed_mm").value(0))
            .andExpect(jsonPath("$.data.items[0].months[0].temporary_mm").value(0.75))
            .andExpect(jsonPath("$.data.items[0].months[0].fill_ratio").value(0))
            .andExpect(jsonPath("$.data.monthly_totals[0].free_people").value(1));
    }
    @Test void leaveTakesPriorityOverTemporaryAndExcludesFreePeople() throws Exception {
        when(mapper.leaves(any())).thenReturn(List.of(new Leave(1,"LEAVE",LocalDate.of(2026,1,1),null)));
        mvc.perform(get("/api/workforce/board").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].months[0].status").value("휴직"))
            .andExpect(jsonPath("$.data.monthly_totals[0].free_people").value(0));
    }
    @Test void anonymousExportIsRealXlsxAndIncludesAllRowsWithSeoulFilename() throws Exception {
        doReturn(LongStream.rangeClosed(1,61).mapToObj(this::person).toList()).when(mapper).people(any());
        var result=mvc.perform(get("/api/workforce/board/export").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.file_name").value("인력운영보드_20261014.xlsx"))
            .andExpect(jsonPath("$.data.media_type").value(DownloadArtifact.XLSX)).andReturn();
        var data=json.readTree(result.getResponse().getContentAsString()).get("data");
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(Base64.getDecoder().decode(data.get("content_base64").asText())))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(64);
            assertThat(workbook.getSheetAt(0).getRow(3).getLastCellNum()).isEqualTo((short)16);
            assertThat(workbook.getSheetAt(0).getRow(4).getCell(4).getStringCellValue()).contains("확정 0 MM","가배정 0 MM");
        }
        verify(mapper).download(argThat(a -> a.actor()==null && a.filename().equals("인력운영보드_20261014.xlsx")));
        verify(assignments,never()).month(any());
    }
    @Test void emptyFilteredExportIsValidWorkbookAndDeterministic() throws Exception {
        var first=mvc.perform(get("/api/workforce/board/export").param("year","2026").param("job","NO_RESULT"))
            .andExpect(status().isOk()).andReturn().getResponse();
        var second=mvc.perform(get("/api/workforce/board/export").param("year","2026").param("job","NO_RESULT"))
            .andExpect(status().isOk()).andReturn().getResponse();
        for(var response:List.of(first,second)) {
            var data=json.readTree(response.getContentAsString()).get("data");
            try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(Base64.getDecoder().decode(data.get("content_base64").asText())))) {
                assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(3);
            }
        }
    }
    private AssignmentModels.Row assignment(long id) {
        return new AssignmentModels.Row(id,"A-"+id,1,"프로젝트 "+id,1,"개발자","K-1","BE",
            LocalDate.of(2026,1,1),LocalDate.of(2026,1,31),LocalDate.of(2026,1,31),BigDecimal.ONE,
            "NEW",null,"H",null,null,"ACTIVE",null,null,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE,
            LocalDateTime.of(2026,1,1,0,0),null,0,false,false,false,false);
    }
    @Test void confirmedSumHasCappedFillAndFullMultiAssignmentDetails() throws Exception {
        when(assignments.list(any())).thenReturn(List.of(assignment(1),assignment(2)));
        when(mapper.months(any())).thenReturn(List.of(new MonthlyAssignment(1,"2026-01",new BigDecimal("0.75")),
            new MonthlyAssignment(2,"2026-01",new BigDecimal("0.50"))));
        mvc.perform(get("/api/workforce/board").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].months[0].confirmed_mm").value(1.25))
            .andExpect(jsonPath("$.data.items[0].months[0].fill_ratio").value(100))
            .andExpect(jsonPath("$.data.items[0].months[0].assignment_count").value(2))
            .andExpect(jsonPath("$.data.items[0].months[0].status").value("철수예정"))
            .andExpect(jsonPath("$.data.items[0].months[0].withdrawal_date").value("2026-01-31"))
            .andExpect(jsonPath("$.data.items[0].months[0].assignments[0].prj_nm").value("프로젝트 1"))
            .andExpect(jsonPath("$.data.items[0].months[0].assignments[0].person_month_mm_qty").value(1.25))
            .andExpect(jsonPath("$.data.monthly_totals[0].remaining_mm").value(0));
    }
    @Test void retirementWinsOverConfirmedAndLeave() throws Exception {
        when(assignments.list(any())).thenReturn(List.of(assignment(1)));
        doReturn(List.of(new Person(1,"개발자","K-1","BE","S",LocalDate.of(2026,1,15)))).when(mapper).people(any());
        when(mapper.leaves(any())).thenReturn(List.of(new Leave(1,"LEAVE",LocalDate.of(2026,1,1),null)));
        when(mapper.months(any())).thenReturn(List.of(new MonthlyAssignment(1,"2026-01",BigDecimal.ONE)));
        mvc.perform(get("/api/workforce/board").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].months[0].status").value("퇴사"))
            .andExpect(jsonPath("$.data.monthly_totals[0].free_people").value(0));
    }
    @Test void leaveOverridesConfirmedAndFreeOnlyExcludesUnavailablePerson() throws Exception {
        when(assignments.list(any())).thenReturn(List.of(assignment(1)));
        when(mapper.leaves(any())).thenReturn(List.of(new Leave(1,"LEAVE",LocalDate.of(2026,1,1),null)));
        when(mapper.months(any())).thenReturn(List.of(new MonthlyAssignment(1,"2026-01",BigDecimal.ONE)));
        mvc.perform(get("/api/workforce/board").param("year","2026").param("free_only","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0));
    }
}
