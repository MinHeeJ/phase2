package com.sicost.costsops;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.validation.BillingValues;
import com.sicost.common.validation.CostValues;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import static com.sicost.costsops.RenegotiationModels.*;

public final class RenegotiationInput {
    private RenegotiationInput() {}

    public static Query query(Map<String, String> values) {
        Integer window = integer(values, "windowMonths");
        if (window == null) window = 6;
        if (!Set.of(3, 6, 12).contains(window)) throw BillingValues.invalid("windowMonths");
        BigDecimal margin = decimal(values, "marginBelow");
        if (margin == null) margin = BigDecimal.valueOf(15);
        if (margin.compareTo(BigDecimal.TEN) != 0 && margin.compareTo(BigDecimal.valueOf(15)) != 0
                && margin.compareTo(BigDecimal.valueOf(20)) != 0) throw BillingValues.invalid("marginBelow");
        BigDecimal raise = decimal(values, "raiseRate");
        if (raise != null && (raise.signum() < 0 || raise.compareTo(BigDecimal.valueOf(50)) > 0)) {
            throw BillingValues.invalid("raiseRate");
        }
        String employee = CostValues.optionalText(values.get("empType"));
        if (employee != null && !Set.of("FT", "CT", "FL", "OS").contains(employee)) {
            throw BillingValues.invalid("empType");
        }
        var paging = BillingPaging.of(integer(values, "page"), integer(values, "size"), values.get("sort"));
        paging.allowedSort(RenegotiationService.SORT_FIELDS, "end_dt,asc");
        return new Query(new Filters(window, margin, raise, employee,
            CostValues.optionalId(values.get("projectId"), "projectId")), paging);
    }

    private static Integer integer(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) return null;
        try {
            if (!value.matches("[0-9]{1,10}")) throw BillingValues.invalid(key);
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            throw BillingValues.invalid(key);
        }
    }

    private static BigDecimal decimal(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) return null;
        try {
            if (!value.matches("[+-]?[0-9]{1,12}(\\.[0-9]{1,12})?")) throw BillingValues.invalid(key);
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw BillingValues.invalid(key);
        }
    }
}
