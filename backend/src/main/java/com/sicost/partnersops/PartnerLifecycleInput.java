package com.sicost.partnersops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.math.BigInteger;
import java.util.*;

final class PartnerLifecycleInput {
    private PartnerLifecycleInput() {}
    static String id(String value,String field) {
        if(value==null || !value.matches("[1-9][0-9]{0,18}") || new BigInteger(value).compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0)
            throw ApiException.badRequest("거래처를 확인하세요.",field);
        return value;
    }
    static List<String> ids(JsonNode body) {
        if(body==null || !body.isObject() || body.size()!=1 || !body.has("ids") || !body.get("ids").isArray() || body.get("ids").isEmpty())
            throw ApiException.badRequest("거래처를 선택하세요.","ids");
        var ids=new LinkedHashSet<String>();
        for(var node:body.get("ids")) {
            if(!node.isTextual()) throw ApiException.badRequest("거래처를 확인하세요.","ids");
            ids.add(id(node.textValue(),"ids"));
        }
        return List.copyOf(ids);
    }
    static void kind(String value,boolean options) {
        if(value==null && !options) return;
        if(!(options?Set.of("CUST","VEND"):Set.of("CUST","VEND","BOTH")).contains(value==null?"":value))
            throw ApiException.badRequest("구분을 확인하세요.","kind");
    }
}
