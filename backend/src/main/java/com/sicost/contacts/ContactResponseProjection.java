package com.sicost.contacts;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Business contact originals are available only through the purpose-audited contact endpoints. */
@RestControllerAdvice(basePackages={"com.sicost.developers","com.sicost.developersops","com.sicost.partners","com.sicost.partnersops"})
public class ContactResponseProjection implements ResponseBodyAdvice<Object> {
    private static final Set<String> CONTACTS=Set.of("tel_no","email_addr","chrg_tel_no","chrg_email_addr","txinv_email_addr");
    private final ObjectMapper json;
    public ContactResponseProjection(ObjectMapper json) { this.json=json; }
    @Override public boolean supports(MethodParameter parameter,Class<? extends HttpMessageConverter<?>> converter) { return !parameter.getParameterType().equals(byte[].class); }
    @Override public Object beforeBodyWrite(Object body,MethodParameter parameter,MediaType type,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response) {
        if(body==null || body instanceof byte[] || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) return body;
        JsonNode result=json.valueToTree(body);mask(result);return result;
    }
    private void mask(JsonNode node) {
        if(node instanceof ObjectNode object) {
            for(String name:CONTACTS) if(object.has(name)) object.putNull(name);
            object.elements().forEachRemaining(this::mask);
        } else if(node.isArray()) node.elements().forEachRemaining(this::mask);
    }
}
