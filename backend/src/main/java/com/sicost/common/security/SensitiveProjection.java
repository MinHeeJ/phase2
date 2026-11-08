package com.sicost.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.RecordComponent;
import java.util.Set;
import org.springframework.stereotype.Component;
import static com.sicost.common.security.SecurityModels.*;

@Component
public class SensitiveProjection {
    private static final Set<String> CONTACTS=Set.of("phone","email","tel_no","email_addr","chrg_tel_no","chrg_email_addr","txinv_email_addr");
    private final ObjectMapper json;
    public SensitiveProjection(ObjectMapper json) { this.json=json; }
    public JsonNode project(Object value,ScopePredicate scope,Resource row) {
        if(!scope.allows(row)) throw com.sicost.common.api.ApiException.notFound("요청한 항목이 없습니다.");
        JsonNode result=json.valueToTree(value);
        sanitize(value,result,scope.sensitive(row));
        return result;
    }
    private void sanitize(Object source,JsonNode target,boolean sensitive) {
        if(source==null || target==null) return;
        if(source instanceof Iterable<?> elements && target.isArray()) {
            int index=0;for(Object element:elements) sanitize(element,target.get(index++),sensitive);
        } else if(source.getClass().isRecord() && target instanceof ObjectNode object) {
            for(RecordComponent component:source.getClass().getRecordComponents()) {
                var accessor=component.getAccessor();
                if(CONTACTS.contains(component.getName()) || (!sensitive && (component.isAnnotationPresent(Sensitive.class) || accessor.isAnnotationPresent(Sensitive.class)))) {
                    object.remove(component.getName());continue;
                }
                try { accessor.trySetAccessible();sanitize(accessor.invoke(source),object.get(component.getName()),sensitive); }
                catch(ReflectiveOperationException ex) { throw new IllegalStateException("응답 투영에 실패했습니다."); }
            }
        }
    }
}
