package com.sicost.partnersops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.util.*;

final class PartnerActivityInput {
    private PartnerActivityInput() {}
    static String id(String value) {
        if(value==null || !value.matches("[1-9][0-9]{0,18}") || new java.math.BigInteger(value).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))>0)
            throw ApiException.badRequest("거래처 번호를 확인하세요.","ids");
        return value;
    }
    static List<String> ids(JsonNode body) {
        if(body==null || !body.isObject() || body.size()!=1 || !body.has("ids") || !body.get("ids").isArray() || body.get("ids").isEmpty())
            throw ApiException.badRequest("거래처를 선택하세요.","ids");
        Set<String> ids=new LinkedHashSet<>();
        for(JsonNode n:body.get("ids")) {
            if(!n.isTextual()) throw ApiException.badRequest("거래처 번호를 확인하세요.","ids");
            ids.add(id(n.textValue()));
        }
        return List.copyOf(ids);
    }
}
