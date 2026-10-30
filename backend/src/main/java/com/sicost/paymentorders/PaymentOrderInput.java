package com.sicost.paymentorders;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CalendarValues;
import java.time.*;
import java.util.*;
import static com.sicost.paymentorders.PaymentOrderModels.*;

public final class PaymentOrderInput {
    private PaymentOrderInput() {}
    public static ApiException invalid(String field) {
        return new ApiException(400,"VALIDATION_ERROR","입력값을 확인하세요.",field);
    }
    public static YearMonth month(String value) {
        try {return CalendarValues.month(value,"month");}
        catch(ApiException ex) {throw invalid("month");}
    }
    public static List<Order> parse(JsonNode body, String month) {
        var ym=month(month);
        if(body==null || !body.isObject() || !body.path("items").isArray()) throw invalid("items");
        var items=body.get("items");
        if(items.size()>5) throw invalid("items");
        var result=new ArrayList<Order>();var numbers=new HashSet<Integer>();
        for(var item:items) {
            var number=item.path("pay_odr_no");var date=item.path("planned_date");
            if(!number.isIntegralNumber() || !number.canConvertToInt() || number.intValue()<1 || number.intValue()>5)
                throw invalid("pay_odr_no");
            if(!numbers.add(number.intValue())) throw invalid("pay_odr_no");
            LocalDate parsed;
            try {
                if(!date.isTextual() || !date.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid("planned_date");
                parsed=LocalDate.parse(date.textValue());
            } catch(java.time.format.DateTimeParseException ex) {throw invalid("planned_date");}
            if(!YearMonth.from(parsed).equals(ym)) throw invalid("planned_date");
            result.add(new Order(number.intValue(),parsed,false));
        }
        return List.copyOf(result);
    }
}
