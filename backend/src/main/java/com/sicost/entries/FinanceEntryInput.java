package com.sicost.entries;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.time.*;
import java.util.*;
import static com.sicost.entries.FinanceEntryModels.*;

public final class FinanceEntryInput {
    private FinanceEntryInput() {}
    public static ApiException invalid(String field) {
        return new ApiException(400,"VALIDATION_ERROR","입력값을 확인하세요.",field);
    }
    public static YearMonth month(String value, String field) {
        if(value==null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])"))throw invalid(field);
        try {var month=YearMonth.parse(value);if(month.getYear()<1)throw invalid(field);return month;}
        catch(DateTimeException e){throw invalid(field);}
    }
    public static int integer(String value,String field,int max) {
        try {int n=Integer.parseInt(value);if(n<1 || n>max)throw invalid(field);return n;}
        catch(NumberFormatException e){throw invalid(field);}
    }
    public static long id(String value,String field) {
        try {long id=Long.parseLong(value);if(id<1)throw invalid(field);return id;}
        catch(NumberFormatException e){throw invalid(field);}
    }
    public static void object(JsonNode body) {if(body==null || !body.isObject())throw invalid(null);}
    public static EntryInput parse(JsonNode body) {
        object(body);
        var allowed=Set.of("entry_id","entry_ym","fin_se_cd","item_nm","entry_amt","vat_amt","inout_dt","rpt_yn","inout_cmpl_yn","remark","copy_entry_id","auto_fill_yn");
        body.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw invalid(key);});
        String month=text(body,"entry_ym",7,true);month(month,"entry_ym");
        String code=text(body,"fin_se_cd",10,true);
        if(!Set.of("SGA","NOI","NOE").contains(code))throw invalid("fin_se_cd");
        String name=text(body,"item_nm",100,true).strip();
        long amount=money(body,"entry_amt",true),vat=money(body,"vat_amt",false);
        try {Math.addExact(amount,vat);}catch(ArithmeticException e){throw invalid("vat_amt");}
        String date=text(body,"inout_dt",10,false);LocalDate inout=null;
        if(date!=null){try{inout=LocalDate.parse(date);}catch(DateTimeException e){throw invalid("inout_dt");}}
        String recurring=flag(body,"rpt_yn"),completed=flag(body,"inout_cmpl_yn"),auto=flag(body,"auto_fill_yn");
        if("Y".equals(completed)&&inout==null)throw invalid("inout_cmpl_yn");
        Long source=null;var node=body.get("copy_entry_id");
        if(node!=null&&!node.isNull()){if(!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<1)throw invalid("copy_entry_id");source=node.longValue();}
        if("Y".equals(auto)&&source==null)throw invalid("auto_fill_yn");
        return new EntryInput(month,code,name,amount,vat,inout,recurring,completed,text(body,"remark",500,false),source,auto);
    }
    private static String flag(JsonNode b,String field) {
        String value=text(b,field,1,false);if(value==null)return "N";
        if(!Set.of("Y","N").contains(value))throw invalid(field);return value;
    }
    private static String text(JsonNode b,String field,int max,boolean required) {
        var n=b.get(field);if(n==null||n.isNull()){if(required)throw invalid(field);return null;}
        if(!n.isTextual()||n.textValue().length()>max||(required&&n.textValue().isBlank()))throw invalid(field);
        return n.textValue();
    }
    private static long money(JsonNode b,String field,boolean required) {
        var n=b.get(field);if(n==null||n.isNull()){if(required)throw invalid(field);return 0;}
        if(!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0)throw invalid(field);return n.longValue();
    }
    public static List<Long> selections(JsonNode body) {
        object(body);body.fieldNames().forEachRemaining(k->{if(!k.equals("entry_ids"))throw invalid(k);});
        var ids=body.get("entry_ids");if(ids==null)return null;
        if(!ids.isArray())throw invalid("entry_ids");
        var result=new ArrayList<Long>();
        for(var n:ids){if(!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<1||result.contains(n.longValue()))throw invalid("entry_ids");result.add(n.longValue());}
        return result;
    }
}
