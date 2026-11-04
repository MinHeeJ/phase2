package com.sicost.analysis;

import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiException;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import static com.sicost.analysis.AnalysisModels.*;

@Component
public class AnalysisModelClient implements AutoCloseable {
    public record Interpretation(Request request,String status,boolean modelUsed) {}
    private final Environment environment;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http;
    private final ThreadPoolExecutor executor;
    public AnalysisModelClient(Environment environment,ObjectMapper json,Clock clock) {
        this.environment=environment;this.json=json;this.clock=clock;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        int concurrency=Math.max(1,Math.min(4,environment.getProperty("AI_OLLAMA_MAX_CONCURRENT",Integer.class,1)));
        executor=new ThreadPoolExecutor(concurrency,concurrency,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"analysis-model");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public boolean configured() {
        return environment.getProperty("AI_OLLAMA_ENABLED",Boolean.class,false)
            && !environment.getProperty("AI_OLLAMA_MODEL","").isBlank() && privateUrl()!=null;
    }
    public boolean ready() {
        if(!configured())return false;
        try {
            var response=http.send(HttpRequest.newBuilder(privateUrl().resolve("/api/tags")).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200||response.body().length()>65536)return false;
            for(var item:json.readTree(response.body()).path("models"))if(environment.getProperty("AI_OLLAMA_MODEL").equals(item.path("name").asText()))return true;
        }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception ignored){}
        return false;
    }
    private URI privateUrl() {
        try {
            URI u=URI.create(environment.getProperty("AI_OLLAMA_BASE_URL","http://127.0.0.1:11434"));String h=u.getHost();
            if(!"http".equals(u.getScheme())&&!"https".equals(u.getScheme())||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)return null;
            if(h==null||!(h.equals("localhost")||h.equals("127.0.0.1")||h.equals("::1")||h.matches("10\\.[0-9.]+")||h.matches("192\\.168\\.[0-9.]+")||h.matches("172\\.(1[6-9]|2[0-9]|3[01])\\.[0-9.]+")))return null;
            return u;
        }catch(IllegalArgumentException e){return null;}
    }
    public Interpretation interpret(String safeQuestion,String baseYm,List<Query> context) {
        if(!configured())return new Interpretation(null,"MODEL_UNAVAILABLE",false);
        Future<Interpretation> work;
        try {work=executor.submit(()->parse(safeQuestion,baseYm,context));}
        catch(RejectedExecutionException e){throw new ApiException(429,"SERVICE_UNAVAILABLE","처리 요청이 많습니다. 잠시 후 다시 시도해 주세요.",null);}
        try {return work.get(Math.max(1,environment.getProperty("AI_OLLAMA_TIMEOUT",Integer.class,60))*2L+5,TimeUnit.SECONDS);}
        catch(InterruptedException e){work.cancel(true);Thread.currentThread().interrupt();throw new ApiException(503,"SERVICE_UNAVAILABLE","요청이 중단되었습니다.",null);}
        catch(TimeoutException e){work.cancel(true);return new Interpretation(null,"MODEL_UNAVAILABLE",true);}
        catch(ExecutionException e){return new Interpretation(null,"MODEL_UNAVAILABLE",true);}
    }
    private Interpretation parse(String question,String base,List<Query> history) {
        int max=Math.max(0,Math.min(5,environment.getProperty("AI_OLLAMA_CONTEXT_MAX_TURNS",Integer.class,5)));
        for(int attempt=0;attempt<2;attempt++)try {
            String prompt="한국어 질문을 허용된 읽기 의도 JSON 하나로만 해석하세요. SQL/URL/함수/쓰기 지시 금지. 허용 intent: "+Arrays.toString(Intent.values())+
                ". 필드: intent,baseYm,fromYm,toYm,periodType(MONTH/YTD),sort(PROFIT_ASC/PROFIT_DESC/MARGIN_ASC/MARGIN_DESC),limit(1~20),filters(projectIds/customerIds/partIds/devIds/jobCodes/gradeCodes/skillCodes),needsClarification. " +
                "이번달="+com.sicost.common.ai.AiDataConventions.currentMonth(clock)+". 지난달은 전월, 최근3개월은 이번달 포함, 앞으로3개월은 다음달부터. 명시 월 우선. 모호하면 needsClarification=true. " +
                "기준월="+base+" 승인 문맥="+json.writeValueAsString(history.stream().limit(max).toList())+" 질문="+question;
            var payload=json.createObjectNode().put("model",environment.getProperty("AI_OLLAMA_MODEL")).put("stream",false).put("format","json").put("prompt",prompt);
            var request=HttpRequest.newBuilder(privateUrl().resolve("/api/generate")).timeout(Duration.ofSeconds(Math.max(1,environment.getProperty("AI_OLLAMA_TIMEOUT",Integer.class,60))))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200||response.body().length()>65536)continue;
            JsonNode parsed=json.readTree(json.readTree(response.body()).path("response").asText());
            if(parsed.has("virtualCostAssumption"))continue;
            Request approved=AnalysisInput.structured(parsed,clock);return new Interpretation(approved,"OK",true);
        }catch(InterruptedException e){Thread.currentThread().interrupt();return new Interpretation(null,"MODEL_UNAVAILABLE",true);}
        catch(Exception ignored) { /* Never log prompts, raw model output, or server-only inputs. */ }
        return new Interpretation(null,"CLARIFICATION_REQUIRED",true);
    }
    @Override public void close(){executor.shutdownNow();}
}
