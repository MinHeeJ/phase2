package com.sicost.systemadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.time.LocalDate;
import java.util.*;

final class AdministrationInput {
    private AdministrationInput() {}
    static Map<String,Object> fields(JsonNode body,Set<String> allowed) {
        if(body==null || !body.isObject()) throw invalid(null);
        Map<String,Object> result=new LinkedHashMap<>();
        body.fields().forEachRemaining(e->{
            if(!allowed.contains(e.getKey())) throw invalid(e.getKey());
            JsonNode n=e.getValue();
            if(n.isNull()) result.put(e.getKey(),null);
            else if(n.isTextual()) result.put(e.getKey(),n.textValue());
            else if(n.isBoolean()) result.put(e.getKey(),n.booleanValue());
            else if(n.isIntegralNumber() && n.canConvertToLong()) result.put(e.getKey(),n.longValue());
            else if(n.isArray()) result.put(e.getKey(),n);
            else throw invalid(e.getKey());
        });
        return result;
    }
    static String text(Map<String,Object> input,String key,int max,boolean required) {
        Object value=input.get(key);
        if(value==null && !required) return null;
        if(!(value instanceof String s) || s.length()>max || (required && s.isBlank())) throw invalid(key);
        return (String)value;
    }
    static boolean flag(Map<String,Object> input,String key) {
        Object value=input.get(key);
        if(value==null) return false;
        if(!(value instanceof Boolean)) throw invalid(key);
        return (Boolean)value;
    }
    static Long id(Object value,String key) {
        if(value==null) return null;
        if(!(value instanceof Number n) || n.longValue()<=0) throw invalid(key);
        return ((Number)value).longValue();
    }
    static List<Long> ids(Map<String,Object> input,String key,boolean required) {
        if(!(input.get(key) instanceof JsonNode array) || !array.isArray()) throw invalid(key);
        List<Long> values=new ArrayList<>();
        for(JsonNode n:array) {
            if(!n.isIntegralNumber() || !n.canConvertToLong() || n.longValue()<=0) throw invalid(key);
            if(values.contains(n.longValue())) throw invalid(key);
            values.add(n.longValue());
        }
        if(required && values.isEmpty()) throw invalid(key);
        return values;
    }
    static void user(Map<String,Object> input,boolean create) {
        for(String field:List.of("name","email","phone")) if(create || input.containsKey(field))
            text(input,field,field.equals("name")?50:field.equals("phone")?30:100,!field.equals("phone"));
        if(input.containsKey("email") && !((String)input.get("email")).matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalid("email");
        if(create) {
            String login=text(input,"loginId",50,true);
            if(!login.matches("[A-Za-z0-9._-]+")) throw invalid("loginId");
        }
        for(String field:List.of("partId","developerId")) if(input.containsKey(field)) input.put(field,id(input.get(field),field));
        if(input.containsKey("status") && !(input.get("status") instanceof String status && Set.of("ACTIVE","LOCKED","DISABLED").contains(status))) throw invalid("status");
        if(input.get("resignationDate")!=null) {
            try { input.put("resignationDate",LocalDate.parse(text(input,"resignationDate",10,true))); }
            catch(java.time.DateTimeException ex) { throw invalid("resignationDate"); }
        }
    }
    static void role(Map<String,Object> input) {
        text(input,"name",50,true);text(input,"description",500,false);
        if(!(input.get("dataScope") instanceof String scope) || !Set.of("ALL","PART","PROJECT","SELF").contains(scope)) throw invalid("dataScope");
        yn(input,"useYn");
        for(String field:List.of("admin","sensitiveInquiry","partStandardChange")) input.put(field,flag(input,field));
    }
    static void menu(Map<String,Object> input) {
        text(input,"name",50,true);text(input,"icon",30,false);yn(input,"useYn");
        input.put("parentId",id(input.get("parentId"),"parentId"));
        if(!(input.get("sortOrder") instanceof Number n) || n.longValue()<0 || n.longValue()>Integer.MAX_VALUE) throw invalid("sortOrder");
        input.put("mobileVisible",flag(input,"mobileVisible"));
    }
    static void yn(Map<String,Object> input,String key) {
        if(!(input.get(key) instanceof String value) || !Set.of("Y","N").contains(value)) throw invalid(key);
    }
    static ApiException invalid(String field) { return ApiException.badRequest("입력값을 확인하세요.",field); }
}
