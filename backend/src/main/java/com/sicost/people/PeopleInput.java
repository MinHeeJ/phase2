package com.sicost.people;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.validation.BillingValues;
import com.sicost.common.validation.CostValues;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import static com.sicost.people.PeopleModels.*;

public final class PeopleInput {
    private PeopleInput() {}
    public static final Set<String> SORTS = Set.of("dev_id", "dev_nm", "mm_qty", "bill_untprc_amt",
        "tot_cst_amt", "prft_amt", "mrgn_rt", "slry_amt", "sls_amt");
    public static Query query(Map<String,String> q, Clock clock) {
        int year = CostValues.year(integer(q.get("year"), "year"), clock);
        String view = CostValues.optionalText(q.get("view"));
        if (view == null) view = "TOTAL";
        if (!Set.of("TOTAL", "AVERAGE").contains(view)) throw BillingValues.invalid("view");
        String emp = option(q.get("empType"), "empType", Set.of("FT", "CT", "FL", "OS"));
        String type = option(q.get("contractType"), "contractType", Set.of("MO", "DP", "TK", "IN"));
        var paging = BillingPaging.of(integer(q.get("page"), "page"), integer(q.get("size"), "size"), q.get("sort"));
        paging.allowedSort(SORTS, "dev_id,asc");
        var filters = new Filters(emp, CostValues.optionalId(q.get("partId"), "partId"),
            CostValues.optionalId(q.get("vendorId"), "vendorId"), type, CostValues.optionalText(q.get("job")));
        return new Query(year, view, filters, bool(q, "lossOnly"), bool(q, "overlapOnly"),
            bool(q, "excludeTurnkeyPrice"), paging);
    }
    private static String option(String value, String field, Set<String> allowed) {
        String text = CostValues.optionalText(value);
        if (text != null && !allowed.contains(text)) throw BillingValues.invalid(field);
        return text;
    }
    private static Integer integer(String text, String field) {
        if (text == null) return null;
        try { return Integer.valueOf(text); }
        catch (NumberFormatException e) { throw BillingValues.invalid(field); }
    }
    private static boolean bool(Map<String,String> q, String field) {
        String value = q.get(field);
        if (value == null) return false;
        if (!Set.of("true", "false").contains(value)) throw BillingValues.invalid(field);
        return Boolean.parseBoolean(value);
    }
}
