package com.sicost.priorprofit;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.util.Set;
import static com.sicost.priorprofit.PriorProfitUploadModels.*;

public final class PriorProfitUploadInput {
    private PriorProfitUploadInput() {}
    public static ApiException invalid(String field) {
        return new ApiException(400,"VALIDATION_ERROR","입력값을 확인하세요.",field);
    }
    public static Apply parse(JsonNode body) {
        if(body==null || !body.isObject()) throw invalid(null);
        body.fieldNames().forEachRemaining(k->{if(!Set.of("upload_id","confirm").contains(k)) throw invalid(k);});
        var id=body.get("upload_id");
        if(id==null || !id.isTextual() || !id.asText().matches("[1-9][0-9]{0,18}")) throw invalid("upload_id");
        long value;
        try {value=Long.parseLong(id.asText());} catch(NumberFormatException e) {throw invalid("upload_id");}
        if(!body.has("confirm") || !body.get("confirm").isBoolean()) throw invalid("confirm");
        return new Apply(value,body.get("confirm").booleanValue());
    }
}
