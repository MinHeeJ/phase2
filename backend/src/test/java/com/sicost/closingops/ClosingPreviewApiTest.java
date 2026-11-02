package com.sicost.closingops;

import com.sicost.apiops.ClosingQueryService;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.costallocation.*;
import com.sicost.cashflow.*;
import com.sicost.profit.*;
import com.sicost.tax.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.closingops.ClosingModels.*;

@WebMvcTest(controllers=ClosingController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,ClosingSnapshotService.class,PermissionService.class})
class ClosingPreviewApiTest {
    @Autowired MockMvc mvc;
    @Autowired ClosingSnapshotService snapshots;
    @MockBean ClosingService service;
    @MockBean ClosingHistoryService history;
    @MockBean ClosingMapper mapper;
    @MockBean ClosingQueryService stored;
    @MockBean CostAllocationService costs;
    @MockBean MonthlyProfitService profits;
    @MockBean CorporateTaxService taxes;
    @MockBean CashFlowService cash;
    Map<String,Object> profit;
    @BeforeEach void setup() {
        profit=new LinkedHashMap<>();
        for(String key:List.of("revenue","project_labor_cost","idle_cost","direct_expenses","sga_input","indirect_labor_cost","rnd_cost","previous_adjustment","non_operating_income","non_operating_expenses","operating_profit","cost","corporate_tax"))profit.put(key,10L);
        profit.put("warnings",List.of("원천 경고"));
        var months=new ArrayList<Map<String,Object>>();for(int i=0;i<12;i++)months.add(profit);
        when(profits.calculate(2026)).thenReturn(months);
        when(costs.calculate("2026-10",null)).thenReturn(new CostAllocationModels.Allocation("2026-10","2026-10",false,"원",List.of(),List.of(),0));
        when(taxes.plan(2026)).thenReturn(new CorporateTaxModels.Plan(2026,120L,null,List.of(new CorporateTaxModels.Month("2026-10",false,10L,null)),List.of(),true));
        var month=new CashFlowModels.Month("2026-10",100L,100L,90L,90L,null,100L,0,10,0L,10L,100L,0,10,false,List.of());
        when(cash.flow(2026,"ACTUAL")).thenReturn(new CashFlowModels.Flow(2026,"ACTUAL",List.of(month),null,null,"2026-01"));
    }
    @Test void openPreviewMatchesTheComponentsUsedByConfirmationWithoutWritingAnything() throws Exception {
        var expected=snapshots.components("2026-10");
        mvc.perform(get("/api/closing/preview").param("month","2026-10")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.profit").value(ClosingSnapshotService.amount(expected.profit(),"operating_profit")))
            .andExpect(jsonPath("$.data.cost").value(ClosingSnapshotService.amount(expected.profit(),"cost")))
            .andExpect(jsonPath("$.data.tax").value(expected.tax())).andExpect(jsonPath("$.data.cash").value(expected.cash()))
            .andExpect(jsonPath("$.data.warnings[0]").value("원천 경고"));
        verify(mapper,never()).ensureMonth(any());verify(mapper,never()).saveChecks(any());verify(mapper,never()).profit(any());verify(mapper,never()).audit(any());
    }
    @Test void closedPreviewOnlyUsesValidatedStoredSnapshots() throws Exception {
        when(mapper.status("2026-10")).thenReturn("CLOSED");when(mapper.preview("2026-10")).thenReturn(new StoredPreview("2026-10",10,20,30,40));
        mvc.perform(get("/api/closing/preview").param("month","2026-10")).andExpect(status().isOk()).andExpect(jsonPath("$.data.cash").value(40));
        verify(stored).getClosing("2026-10");verifyNoInteractions(costs,profits,taxes,cash);
    }
    @Test void closedMissingNeverRecalculates() throws Exception {
        when(mapper.status("2026-10")).thenReturn("CLOSED");
        mvc.perform(get("/api/closing/preview").param("month","2026-10")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MISSING_CLOSED_SNAPSHOT"));verifyNoInteractions(costs,profits,taxes,cash);
    }
    @Test void invalidMonthDoesNotReadOrWrite() throws Exception {
        mvc.perform(get("/api/closing/preview").param("month","2026-13")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MONTH"));verifyNoInteractions(mapper);
    }
    @Test void importConflictDoesNotReplaceImport() throws Exception {
        when(mapper.profitSource("2026-10")).thenReturn("IMPORT");mvc.perform(get("/api/closing/preview").param("month","2026-10")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_CONFLICT"));verify(mapper,never()).profit(any());
    }
    @Test void taxBelowClosedIsDistinctBusinessConflict() throws Exception {
        when(taxes.plan(2026)).thenReturn(new CorporateTaxModels.Plan(2026,0L,null,List.of(new CorporateTaxModels.Month("2026-10",false,null,"ANNUAL_BELOW_CLOSED")),List.of(),false));
        mvc.perform(get("/api/closing/preview").param("month","2026-10")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TAX_BELOW_CLOSED"));
    }
    @Test void absentOpeningDoesNotFabricateZeroCash() throws Exception {
        when(cash.flow(2026,"ACTUAL")).thenThrow(ApiException.conflict("OPENING_BALANCE_REQUIRED","기초 잔액 필요","month"));
        mvc.perform(get("/api/closing/preview").param("month","2026-10")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPENING_BALANCE_REQUIRED"));
    }
    @Test void absentProfitDoesNotFabricateZero() throws Exception {
        profit.put("cost",null);mvc.perform(get("/api/closing/preview").param("month","2026-10")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSE_CHECK_FAILED"));
    }
}
