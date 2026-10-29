package com.sicost.billingsops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.billing.BillingRules;
import java.time.LocalDate;
import java.util.*;
import static com.sicost.billingsops.BillingReviewModels.*;

final class BillingReviewInput {
    private BillingReviewInput() {}
    static long id(String value, String field) {
        try { long n=Long.parseLong(value); if(n>0) return n; }
        catch (RuntimeException ignored) { }
        throw BillingRules.invalid(field);
    }
    static int integer(Map<String,String> values, String field, int fallback) {
        if (!values.containsKey(field)) return fallback;
        try { return Integer.parseInt(values.get(field)); }
        catch (RuntimeException e) { throw BillingRules.invalid(field); }
    }
    static String text(JsonNode n, String field, int maximum, boolean required) {
        JsonNode v=n.get(field);
        if(v==null || v.isNull()) {
            if(required) throw BillingRules.invalid(field);
            return null;
        }
        if(!v.isTextual()) throw BillingRules.invalid(field);
        String value=BillingRules.text(v.textValue().trim(),maximum,field);
        if(required && value.isBlank()) throw BillingRules.invalid(field);
        return value;
    }
    static String reason(JsonNode n) { object(n); return text(n,"reason",500,true); }
    static void object(JsonNode n) { if(n==null || !n.isObject()) throw BillingRules.invalid(null); }
    static LocalDate date(JsonNode n,String field) {
        String s=text(n,field,10,false);
        if(s==null) return null;
        try { return LocalDate.parse(s); } catch(RuntimeException e) { throw BillingRules.invalid(field); }
    }
    static long money(JsonNode n,String field) {
        var v=n.get(field);
        if(v==null || !v.isIntegralNumber() || !v.canConvertToLong()) throw BillingRules.invalid(field);
        return v.longValue();
    }
    static Amendment amendment(JsonNode n) {
        object(n);
        var input=new Amendment(money(n,"sply_amt"),money(n,"vat_amt"),reason(n),date(n,"bill_dt"),
            date(n,"rcpt_plan_dt"),text(n,"txinv_no",50,false),date(n,"txinv_dt"));
        if(input.sply_amt()>=0) throw BillingRules.invalid("sply_amt");
        if(input.vat_amt()>0) throw BillingRules.invalid("vat_amt");
        BillingRules.add(input.sply_amt(),input.vat_amt(),"vat_amt");
        return input;
    }
    record Paging(int page,int size,String field,boolean descending) {
        <T> List<T> page(List<T> rows) {
            long start=(long)page*size;
            int from=(int)Math.min(start,rows.size());
            return List.copyOf(rows.subList(from,(int)Math.min(start+size,rows.size())));
        }
    }
    static Paging paging(Map<String,String> values, Set<String> fields, String defaultField) {
        int page=integer(values,"page",0),size=integer(values,"size",50);
        if(page<0) throw BillingRules.invalid("page");
        if(!Set.of(20,50,100).contains(size)) throw BillingRules.invalid("size");
        String[] sort=values.getOrDefault("sort",defaultField+",asc").split(",",-1);
        if(sort.length!=2 || !fields.contains(sort[0]) || !Set.of("asc","desc").contains(sort[1])) {
            throw BillingRules.invalid("sort");
        }
        return new Paging(page,size,sort[0],sort[1].equals("desc"));
    }
}
