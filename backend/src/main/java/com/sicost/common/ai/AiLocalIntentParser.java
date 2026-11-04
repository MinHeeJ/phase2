package com.sicost.common.ai;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sicost.common.api.ApiException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AiLocalIntentParser {
    public record Parsed(String status, ObjectNode value, boolean modelUsed) {}
    private final Environment environment;
    private final ObjectMapper json;
    private final Semaphore pending=new Semaphore(2,true),running=new Semaphore(1,true);
    public AiLocalIntentParser(Environment environment,ObjectMapper json) {this.environment=environment;this.json=json;}
    public Parsed parseCriteria(AiQuestionSanitizer.Prepared prepared) {
        if(prepared.needsClarification())return result("CLARIFICATION_REQUIRED",false);
        // 분석 해석(AnalysisModelClient)과 같은 주소·모델 기준을 쓴다.
        if(!AiModelEndpoint.enabled(environment))return result("MODEL_UNAVAILABLE",false);
        String model=AiModelEndpoint.model(environment);URI base=AiModelEndpoint.base(environment);
        if(!pending.tryAcquire())throw new ApiException(429,"SERVICE_UNAVAILABLE","모델 요청이 많습니다. 잠시 후 다시 시도하세요.",null);
        boolean acquired=false;
        try {
            acquired=running.tryAcquire(60,TimeUnit.SECONDS);if(!acquired)return result("MODEL_UNAVAILABLE",false);
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
            for(int attempt=0;attempt<2;attempt++) {
                ObjectNode payload=json.createObjectNode().put("model",model).put("stream",false).put("format","json").put("think",false);
                payload.putObject("options").put("temperature",0);
                payload.put("system","추천 조건 초안만 JSON 객체로 추출한다. 허용 키: jobCode,startYm,gradeCode,skillCodes,industryCode,durationMonths,headcount,requestedMm. 불명확한 값은 생략한다. 점수, 인물, SQL, URL을 생성하지 않는다.");
                payload.put("prompt",prepared.modelQuestion());
                var response=client.send(HttpRequest.newBuilder(base.resolve("/api/generate")).timeout(Duration.ofSeconds(60))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()!=200||response.body().length()>65536)return result("MODEL_UNAVAILABLE",true);
                try {
                    JsonNode envelope=json.readTree(response.body());JsonNode value=json.readTree(envelope.path("response").asText());
                    if(value!=null&&value.isObject())return new Parsed("OK",(ObjectNode)value,true);
                }catch(com.fasterxml.jackson.core.JsonProcessingException ignored){ }
            }
            return result("CLARIFICATION_REQUIRED",true);
        }catch(InterruptedException e){Thread.currentThread().interrupt();return result("MODEL_UNAVAILABLE",false);}
        catch(java.io.IOException|IllegalArgumentException e){return result("MODEL_UNAVAILABLE",true);}
        finally{if(acquired)running.release();pending.release();}
    }
    private Parsed result(String status,boolean used){return new Parsed(status,json.createObjectNode(),used);}
}
