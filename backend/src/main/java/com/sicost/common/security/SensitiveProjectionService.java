package com.sicost.common.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.*;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SensitiveProjectionService {
    private final ObjectMapper json;
    private final ObjectMapper redacted;
    public SensitiveProjectionService(ObjectMapper json) {
        this.json=json;
        var module=new SimpleModule();
        module.setSerializerModifier(new BeanSerializerModifier() {
            @Override public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                    BeanDescription bean, List<BeanPropertyWriter> properties) {
                return properties.stream().filter(p -> p.getAnnotation(Sensitive.class)==null).toList();
            }
        });
        this.redacted=json.copy().registerModule(module);
    }
    /** Call before returning a DTO or constructing CSV/XLSX/AI output, never after transmission. */
    public JsonNode project(Object dto, ScopePredicate scope, ScopePredicate.Target target) {
        scope.require(target);
        return (scope.sensitive(target)?json:redacted).valueToTree(dto);
    }
}
