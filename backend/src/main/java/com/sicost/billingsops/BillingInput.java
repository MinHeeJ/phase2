package com.sicost.billingsops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.BillingPaging;
import com.sicost.common.validation.*;
import java.time.*;
import java.util.*;
import static com.sicost.billingsops.BillingModels.*;

public final class BillingInput {
    private BillingInput() {}
    public static long id(long value) {
        if (value < 1) throw BillingValues.invalid("id");
        return value;
    }
    public static String text(JsonNode body, String field, int max, boolean required) {
        JsonNode n=body.get(field);
        if (n==null || n.isNull()) {
            if (required) throw BillingValues.invalid(field);
            return null;
        }
        if (!n.isTextual() || n.textValue().length()>max || (required && n.textValue().isBlank()))
            throw BillingValues.invalid(field);
        return n.textValue();
    }
    public static void object(JsonNode body, Set<String> allowed) {
        if (body==null || !body.isObject()) throw BillingValues.invalid(null);
        body.fieldNames().forEachRemaining(k->{if (!allowed.contains(k)) throw BillingValues.invalid(k);});
    }
    private static LocalDate date(JsonNode body,String field) {
        String s=text(body,field,10,false);
        return s==null?null:BillingValues.date(s,field);
    }
    public static String cancel(JsonNode body) {
        object(body,Set.of("reason"));
        return text(body,"reason",500,true);
    }
    public static Amendment amendment(JsonNode body) {
        object(body,Set.of("reason","sply_amt","vat_amt","bill_dt","rcpt_plan_dt","txinv_no","txinv_dt"));
        long supply=BillingValues.money(body.get("sply_amt"),Long.MIN_VALUE,"sply_amt");
        long vat=BillingValues.money(body.get("vat_amt"),Long.MIN_VALUE,"vat_amt");
        if (supply>=0) throw BillingValues.invalid("sply_amt");
        if (vat>0) throw BillingValues.invalid("vat_amt");
        BillingValues.add(supply,vat,"tot_amt");
        return new Amendment(supply,vat,text(body,"reason",500,true),date(body,"bill_dt"),
            date(body,"rcpt_plan_dt"),text(body,"txinv_no",50,false),date(body,"txinv_dt"));
    }
    public static Integer integer(Map<String,String> q,String field) {
        String v=q.get(field);
        if (v==null || v.isBlank()) return null;
        try { return Integer.valueOf(v); } catch (NumberFormatException ex) { throw BillingValues.invalid(field); }
    }
    public static Long identifier(Map<String,String> q,String field,boolean required) {
        String v=q.get(field);
        if (v==null || v.isBlank()) {
            if (required) throw BillingValues.invalid(field);
            return null;
        }
        try { long id=Long.parseLong(v);if(id<1)throw BillingValues.invalid(field);return id; }
        catch (NumberFormatException ex) { throw BillingValues.invalid(field); }
    }
    public static BillingPaging paging(Map<String,String> q) {
        return BillingPaging.of(integer(q,"page"),integer(q,"size"),q.get("sort"));
    }
    public static GapQuery gaps(Map<String,String> q) {
        String month=q.get("month"),judgment=q.get("judgment");
        if (month!=null && !month.isBlank()) BillingValues.month(month,"month");
        if (judgment!=null && !judgment.isBlank() && !Set.of("맞음","맞음(무상)","적게 청구","많이 청구","청구 없음","근태 미입력").contains(judgment))
            throw BillingValues.invalid("judgment");
        return new GapQuery(month,judgment,search(q),paging(q));
    }
    private static String search(Map<String,String> q) {
        String s=q.get("q");if(s!=null && s.length()>200)throw BillingValues.invalid("q");return s;
    }
    public static OverdueQuery overdue(Map<String,String> q,Clock clock) {
        Integer stage=integer(q,"stage"),days=integer(q,"overDays");
        if(stage!=null && (stage<0 || stage>3))throw BillingValues.invalid("stage");
        if(days!=null && !Set.of(30,60,90).contains(days))throw BillingValues.invalid("overDays");
        return new OverdueQuery(BillingValues.year(integer(q,"year"),clock),stage,
            identifier(q,"customerId",false),days,search(q),paging(q));
    }
}
