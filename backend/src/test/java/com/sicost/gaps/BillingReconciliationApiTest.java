package com.sicost.gaps;

import com.sicost.billingsops.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;
import static com.sicost.billingsops.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={BillingController.class,BillingExportController.class})
@Import({RequestIdFilter.class,ApiExceptionHandler.class,BillingGapService.class,
    BillingAmountService.class,ExcelExportService.class,CurrentUser.class,PermissionService.class})
class BillingReconciliationApiTest {
    @Autowired MockMvc mvc;
    @MockBean BillingMapper mapper;
    @MockBean BillingReceivableService receivables;
    @MockBean BillingRevenueQuery revenue;
    @MockBean BillingCommandService commands;
    @MockBean Clock clock;
    @SpyBean PermissionService permissions;

    @BeforeEach void setup() {
        Clock fixed=Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(fixed.instant());
        when(clock.getZone()).thenReturn(fixed.getZone());
        when(clock.withZone(any())).thenReturn(fixed);
        when(mapper.gapProjects(anyString())).thenReturn(List.of(new Project(901,"정산 프로젝트","MO","MONTH","Y")));
        when(mapper.sources(any())).thenReturn(List.of(source(901,2000000L,"NEW")));
        when(mapper.confirmed(any())).thenReturn(List.of());
    }
    Source source(long id,Long price,String kind) {
        return new Source(id,id,id,"계약 인력","K-00901",kind,LocalDate.of(2026,10,1),
            BigDecimal.ONE,price,null,null,null,null,null);
    }
    void noBusinessWrites() {
        verify(mapper,never()).transition(any());
        verify(mapper,never()).audit(any());
        verify(mapper,never()).insertAmendment(any());
        verify(mapper,never()).lockBill(anyLong());
        verify(mapper,never()).lockProject(anyLong());
    }
    @Test void approvedOperationsUseTheirExistingHandlers() throws Exception {
        try(var input=new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            Map<?,?> document=new Yaml().load(input);
            Map<?,?> paths=(Map<?,?>)document.get("paths");
            for(var entry:Map.of("/api/billings/gaps","listBillingGaps",
                    "/api/billings/gaps/export","exportBillingGaps").entrySet()) {
                Map<?,?> operation=(Map<?,?>)((Map<?,?>)paths.get(entry.getKey())).get("get");
                assertThat(operation.get("operationId")).isEqualTo(entry.getValue());
                mvc.perform(get(entry.getKey()).param("month","2026-10")).andExpect(status().isOk());
            }
        }
    }
    @Test void anonymousListHasContractIdentityAmountsAndNoWrites() throws Exception {
        mvc.perform(get("/api/billings/gaps").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.month").value("2026-10"))
            .andExpect(jsonPath("$.data.items[0].project_id").value(901))
            .andExpect(jsonPath("$.data.items[0].basis_supply_amt").value(2000000))
            .andExpect(jsonPath("$.data.items[0].difference_amt").value(2000000))
            .andExpect(jsonPath("$.data.items[0].suspected_people[0].management_number").value("K-00901"))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.page").value(0));
        verify(permissions).can(BillingActions.READ);
        verify(mapper,never()).download(any());
        noBusinessWrites();
    }
    @ParameterizedTest @CsvSource({"1990000,맞음","2010000,맞음","1989999,적게 청구","2010001,많이 청구"})
    void toleranceIncludesBoundaryButNotOneWonBeyond(long billed,String judgment) throws Exception {
        when(mapper.confirmed(any())).thenReturn(List.of(new Confirmed(77,billed)));
        mvc.perform(get("/api/billings/gaps").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].judgment").value(judgment))
            .andExpect(jsonPath("$.data.items[0].tolerance").value(10000));
    }
    @Test void percentageToleranceExceedsMinimumForLargeAmounts() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of(source(901,10000000L,"NEW")));
        when(mapper.confirmed(any())).thenReturn(List.of(new Confirmed(77,9950000)));
        mvc.perform(get("/api/billings/gaps").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].tolerance").value(50000))
            .andExpect(jsonPath("$.data.items[0].judgment").value("맞음"));
    }
    @Test void confirmedReversalIsIncludedInSupplyNotVatOrDraft() throws Exception {
        when(mapper.confirmed(any())).thenReturn(List.of(new Confirmed(77,2100000),new Confirmed(78,-100000)));
        mvc.perform(get("/api/billings/gaps").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].confirmed_supply_amt").value(2000000))
            .andExpect(jsonPath("$.data.items[0].billing_ids[1]").value(78))
            .andExpect(jsonPath("$.data.items[0].judgment").value("맞음"));
    }
    @Test void freeSupportIsMatchedWithZeroAmount() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of(source(901,2000000L,"FREE")));
        mvc.perform(get("/api/billings/gaps").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].basis_supply_amt").value(0))
            .andExpect(jsonPath("$.data.items[0].judgment").value("맞음(무상)"));
    }
    @Test void dispatchMissingAttendanceIsNotReportedAsMatchedZero() throws Exception {
        when(mapper.gapProjects(anyString())).thenReturn(List.of(new Project(901,"파견","DP","DAY","Y")));
        mvc.perform(get("/api/billings/gaps").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].attendance_missing").value(true))
            .andExpect(jsonPath("$.data.items[0].judgment").value("근태 미입력"));
    }
    @Test void zeroBasisWithoutConfirmedBillHasNoInventedIssue() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of());
        mvc.perform(get("/api/billings/gaps").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.total_difference_amt").value(0));
    }
    @Test void defaultMonthUsesLastStoredClose() throws Exception {
        when(mapper.lastClosedMonth()).thenReturn("2026-09");
        mvc.perform(get("/api/billings/gaps")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.month").value("2026-09"));
        verify(mapper).gapProjects("2026-09");
    }
    @Test void noStoredCloseUsesSeoulCurrentMonth() throws Exception {
        mvc.perform(get("/api/billings/gaps")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.month").value("2026-10"));
    }
    List<Project> projects() {
        var rows=new ArrayList<Project>();
        for(long id=901;id<=921;id++)rows.add(new Project(id,"정산 프로젝트","MO","MONTH","Y"));
        rows.add(new Project(999,"검색 제외","MO","MONTH","Y"));
        return rows;
    }
    @Test void filteredTotalAndSumCoverAllPagesWithStableIdentityTieBreak() throws Exception {
        when(mapper.gapProjects(anyString())).thenReturn(projects());
        mvc.perform(get("/api/billings/gaps").param("month","2026-10").param("q","정산")
                .param("judgment","청구 없음").param("page","1").param("size","20"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(21))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].project_id").value(921))
            .andExpect(jsonPath("$.data.total_difference_amt").value(42000000));
    }
    static Stream<Arguments> invalidParameters() {
        return Stream.of("/api/billings/gaps","/api/billings/gaps/export").flatMap(path->Stream.of(
            Arguments.of(path,"month","2026-13"),Arguments.of(path,"judgment","UNKNOWN"),
            Arguments.of(path,"q","x".repeat(201)),Arguments.of(path,"page","-1"),
            Arguments.of(path,"size","101"),Arguments.of(path,"sort","project_name;drop,asc")));
    }
    @ParameterizedTest @MethodSource("invalidParameters")
    void validationIsFieldSpecificAndNeverWrites(String path,String field,String value) throws Exception {
        mvc.perform(get(path).param(field,value)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(mapper,never()).gapProjects(anyString());
        verify(mapper,never()).sources(any());
        verify(mapper,never()).confirmed(any());
        verify(mapper,never()).download(any());
        noBusinessWrites();
    }
    @Test void exportIsFilteredUnpagedWorkbookWithSafeTextAndNullableActorAudit() throws Exception {
        when(mapper.gapProjects(anyString())).thenReturn(projects());
        byte[] bytes=mvc.perform(get("/api/billings/gaps/export").param("month","2026-10")
                .param("q","정산").param("judgment","청구 없음").param("page","1").param("size","20"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString(".xlsx")))
            .andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(24);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("2026-10","정산");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains("시스템 처리");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("프로젝트");
            assertThat(sheet.getRow(24).getCell(4).getNumericCellValue()).isEqualTo(2000000);
        }
        verify(permissions).can(BillingActions.DOWNLOAD);
        verify(mapper).download(argThat(d->d.actor()==null && d.description().equals("{\"rows\":21}")));
        noBusinessWrites();
    }
    @ParameterizedTest @ValueSource(strings={"=1+1","+SUM(1,1)","@SUM(1,1)","-1+1"})
    void exportedProjectNamesAreStringsNeverFormulas(String name) throws Exception {
        when(mapper.gapProjects(anyString())).thenReturn(List.of(new Project(901,name,"MO","MONTH","Y")));
        byte[] bytes=mvc.perform(get("/api/billings/gaps/export").param("month","2026-10"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var cell=book.getSheetAt(0).getRow(4).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).isEqualTo(name);
        }
    }
    @Test void emptyExportStillHasHeadersAndZeroRowAudit() throws Exception {
        when(mapper.gapProjects(anyString())).thenReturn(List.of());
        byte[] bytes=mvc.perform(get("/api/billings/gaps/export").param("month","2026-10"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(3);
        }
        verify(mapper).download(argThat(d->d.description().equals("{\"rows\":0}")));
    }
    @ParameterizedTest @ValueSource(strings={"/api/billings/gaps","/api/billings/gaps/export"})
    void sourceFailureIsJsonAndDoesNotLeakOrAuditSuccess(String path) throws Exception {
        when(mapper.gapProjects(anyString())).thenThrow(new IllegalStateException("private SQL details"));
        mvc.perform(get(path).param("month","2026-10")).andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
        verify(mapper,never()).download(any());
        noBusinessWrites();
    }
}
