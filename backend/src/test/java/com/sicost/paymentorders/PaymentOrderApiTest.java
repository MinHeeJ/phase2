package com.sicost.paymentorders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import static com.sicost.paymentorders.PaymentOrderModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=PaymentOrderController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,PaymentOrderService.class,CurrentUser.class,PermissionService.class})
class PaymentOrderApiTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PaymentOrderMapper mapper;
    final List<Order> rows=new ArrayList<>();
    @BeforeEach void setup() {
        rows.clear(); rows.add(new Order(1,LocalDate.parse("2026-10-10"),false));
        when(mapper.list("2026-10")).thenAnswer(i->List.copyOf(rows));
        when(mapper.save(any())).thenAnswer(i->{var w=i.getArgument(0,Write.class);
            rows.removeIf(r->r.pay_odr_no()==w.order().pay_odr_no());rows.add(w.order());return 1;});
    }
    org.springframework.test.web.servlet.ResultActions save(String body)throws Exception {
        return mvc.perform(put("/api/finance/payment-orders").param("month","2026-10")
            .contentType("application/json").content(body));
    }
    String body="{\"items\":[{\"pay_odr_no\":1,\"planned_date\":\"2026-10-12\"}]}";
    void untouched() {verify(mapper,never()).save(any());verify(mapper,never()).audit(any());verify(mapper,never()).reschedule(any());}
    @Test void publicReadIsReadOnlyAndUsesMonth()throws Exception {
        mvc.perform(get("/api/finance/payment-orders").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].planned_date").value("2026-10-10"));untouched();
    }
    @Test void publicSaveReschedulesPlansAndAuditsOldAndNewDates()throws Exception {
        save(body).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].planned_date").value("2026-10-12"));
        verify(mapper).lockMonth("2026-10");verify(mapper).lockPayments("2026-10");verify(mapper).reschedule(any());
        var audit=org.mockito.ArgumentCaptor.forClass(Audit.class);verify(mapper).audit(audit.capture());
        assertThat(audit.getValue().before()).isEqualTo("2026-10-10");assertThat(audit.getValue().after()).isEqualTo("2026-10-12");
    }
    @Test void closedMonthPreservesEverything()throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");save(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));untouched();
        assertThat(rows.get(0).planned_date()).hasToString("2026-10-10");
    }
    @Test void paidOrderCannotChange()throws Exception {
        rows.set(0,new Order(1,LocalDate.parse("2026-10-10"),true));
        save(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PAID"));untouched();
    }
    @Test void unchangedPaidOrderCanBeEchoed()throws Exception {
        rows.set(0,new Order(1,LocalDate.parse("2026-10-12"),true));save(body).andExpect(status().isOk());untouched();
    }
    @Test void missingOrderCanBeAdded()throws Exception {
        rows.clear();save(body).andExpect(status().isOk());verify(mapper).save(any());
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","{\"items\":null}","{\"items\":[{}]}","{\"items\":[{\"pay_odr_no\":6,\"planned_date\":\"2026-10-10\"}]}","{\"items\":[{\"pay_odr_no\":1.5,\"planned_date\":\"2026-10-10\"}]}","{\"items\":[{\"pay_odr_no\":1,\"planned_date\":\"2026-02-30\"}]}","{\"items\":[{\"pay_odr_no\":1,\"planned_date\":\"2026-11-10\"}]}","{\"items\":[{\"pay_odr_no\":1,\"planned_date\":\"2026-10-10\"},{\"pay_odr_no\":1,\"planned_date\":\"2026-10-11\"}]}","{broken"})
    void invalidPayloadNeverWrites(String input)throws Exception {
        save(input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));untouched();
    }
    @ParameterizedTest @ValueSource(strings={"","2026-13","0000-01","26-01"})
    void invalidReadMonth(String month)throws Exception {
        mvc.perform(get("/api/finance/payment-orders").param("month",month)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));untouched();
    }
    @Test void missingMonthIsFinanceValidation()throws Exception {
        mvc.perform(get("/api/finance/payment-orders")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void unexpectedReadDoesNotLeakSql()throws Exception {
        when(mapper.list(anyString())).thenThrow(new IllegalStateException("private SQL"));
        mvc.perform(get("/api/finance/payment-orders").param("month","2026-10")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }
}
