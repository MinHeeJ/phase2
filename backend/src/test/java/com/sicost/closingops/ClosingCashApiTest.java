package com.sicost.closingops;

import com.sicost.cashflow.*;
import com.sicost.billingsops.BillingRevenueQuery;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.excel.ExcelExportService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.cashflow.CashFlowModels.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=CashFlowController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,CashFlowService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class})
class ClosingCashApiTest {
    @Autowired MockMvc mvc;
    @MockBean CashFlowMapper mapper;
    @MockBean BillingRevenueQuery billing;
    @MockBean Clock clock;
    @BeforeEach void setup() {
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));when(clock.instant()).thenReturn(Instant.parse("2026-10-30T00:00:00Z"));
        when(mapper.initial()).thenReturn(new Balance("2026-09",100L,null,null,"Y","OPEN"));
        when(mapper.balances()).thenReturn(List.of());
    }
    @Test void forecastStartsFromEachMonthsActualOpeningRatherThanAccumulatedForecast() throws Exception {
        when(mapper.events()).thenReturn(List.of(new Event(LocalDate.of(2026,9,1),80,null,0,80,false),new Event(LocalDate.of(2026,10,1),10,null,0,10,false)));
        mvc.perform(get("/api/finance/cashflow").param("year","2026").param("mode","BOTH"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.months[8].forecast_closing").value(20))
            .andExpect(jsonPath("$.data.months[9].actual_opening").value(100)).andExpect(jsonPath("$.data.months[9].forecast_opening").value(100))
            .andExpect(jsonPath("$.data.months[9].forecast_closing").value(90));
    }
    @Test void validPartialExecutionIsActualCashEvenBeforePlanCompletes() throws Exception {
        when(mapper.events()).thenReturn(List.of(new Event(LocalDate.of(2026,10,1),100,null,0,60,false,"CORP_TAX"),new Event(null,0,LocalDate.of(2026,10,2),40,0,false,"CORP_TAX")));
        mvc.perform(get("/api/finance/cashflow").param("year","2026").param("mode","ACTUAL"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.months[9].actual_outflow").value(40))
            .andExpect(jsonPath("$.data.months[9].actual_closing").value(60));
    }
    @Test void reversalLedgerEntriesRestoreCashWithoutCountingPlannedAmountAsActual() throws Exception {
        when(mapper.events()).thenReturn(List.of(new Event(null,0,LocalDate.of(2026,10,1),40,0,false,"CORP_TAX"),new Event(null,0,LocalDate.of(2026,10,2),-40,0,false,"CORP_TAX")));
        mvc.perform(get("/api/finance/cashflow").param("year","2026").param("mode","ACTUAL"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.months[9].actual_outflow").value(0)).andExpect(jsonPath("$.data.months[9].actual_closing").value(100));
    }
}
