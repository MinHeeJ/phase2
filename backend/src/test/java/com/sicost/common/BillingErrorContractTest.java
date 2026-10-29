package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.validation.BillingRules;
import com.sicost.common.validation.BillingValues;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

@WebMvcTest(controllers = BillingErrorContractTest.Errors.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, BillingErrorContractTest.Errors.class})
class BillingErrorContractTest {
    @Autowired MockMvc mvc;

    // Test-only boundary for common error serialization, not a substitute billing API controller.
    @RestController
    static class Errors {
        @GetMapping("/__test/billing-errors/internal") void internal() {
            BillingRules.requireBillable("IN");
        }
        @GetMapping("/__test/billing-errors/closed") void closed() {
            BillingRules.requireOpen(BillingValues.month("2026-10", "month"), "CLOSED", "month");
        }
        @GetMapping("/__test/billing-errors/stale") void stale() {
            BillingRules.requireChanged(0);
        }
        @GetMapping("/__test/billing-errors/over") void over() {
            BillingRules.requireReceiptAmount(1100, 300, 801);
        }
        @GetMapping("/__test/billing-errors/refund") void refund() {
            BillingRules.requireAdjustedTotal(1100, -220, 900);
        }
        @GetMapping("/__test/billing-errors/values") ApiResponse<Map<String, Object>> values() {
            return ApiResponse.ok(Map.of("sply_amt", 1000L, "vat_amt", 100L, "tot_amt", 1100L,
                "bill_dt", LocalDate.of(2026, 10, 1), "atrb_ym", "2026-10"));
        }
    }

    @Test void internalBillingValidationUses400SingularFieldAndGeneratedRequestId() throws Exception {
        var result = mvc.perform(get("/__test/billing-errors/internal").header("X-Request-Id", "caller-value"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("contract_type"))
            .andExpect(jsonPath("$.fields").doesNotExist()).andExpect(jsonPath("$.meta").isMap())
            .andReturn();
        String id = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(id).matches("[a-f0-9-]{36}").isNotEqualTo("caller-value");
        assertThat(result.getResponse().getContentAsString()).contains(id);
    }

    @ParameterizedTest @CsvSource({
        "closed,CLOSED_MONTH,month", "stale,STALE_BILL,bill_id",
        "over,OVER_RECEIPT,rcpt_amt", "refund,REFUND_REQUIRED,sply_amt"
    })
    void explicitBillingConflictsKeep409AndOwnCode(String path, String code, String field) throws Exception {
        mvc.perform(get("/__test/billing-errors/" + path))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.success").value(false));
    }

    @Test void wonAreJsonNumbersAndCalendarValuesAreIsoWithoutAuthentication() throws Exception {
        mvc.perform(get("/__test/billing-errors/values"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.sply_amt").isNumber()).andExpect(jsonPath("$.data.sply_amt").value(1000))
            .andExpect(jsonPath("$.data.tot_amt").value(1100))
            .andExpect(jsonPath("$.data.bill_dt").value("2026-10-01"))
            .andExpect(jsonPath("$.data.atrb_ym").value("2026-10"));
    }
}
