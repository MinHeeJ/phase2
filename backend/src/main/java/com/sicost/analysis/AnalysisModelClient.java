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
    public boolean configured() {return com.sicost.common.ai.AiModelEndpoint.enabled(environment);}
    public boolean ready() {
        if(!configured())return false;
        try {
            var response=http.send(HttpRequest.newBuilder(privateUrl().resolve("/api/tags")).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200||response.body().length()>65536)return false;
            String model=com.sicost.common.ai.AiModelEndpoint.model(environment);
            for(var item:json.readTree(response.body()).path("models"))if(com.sicost.common.ai.AiModelEndpoint.sameModel(model,item.path("name").asText()))return true;
        }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception ignored){}
        return false;
    }
    private URI privateUrl() {return com.sicost.common.ai.AiModelEndpoint.base(environment);}
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
            String prompt=prompt(question,base,json.writeValueAsString(history.stream().limit(max).toList()));
            var payload=json.createObjectNode().put("model",com.sicost.common.ai.AiModelEndpoint.model(environment)).put("stream",false).put("think",false).put("prompt",prompt);
            payload.set("format",SCHEMA);payload.putObject("options").put("temperature",0);
            var request=HttpRequest.newBuilder(privateUrl().resolve("/api/generate")).timeout(Duration.ofSeconds(Math.max(1,environment.getProperty("AI_OLLAMA_TIMEOUT",Integer.class,60))))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200||response.body().length()>65536)continue;
            JsonNode parsed=clean(json.readTree(json.readTree(response.body()).path("response").asText()));
            if(parsed.has("virtualCostAssumption"))continue;
            Request approved=AnalysisInput.structured(parsed,clock);return new Interpretation(approved,"OK",true);
        }catch(InterruptedException e){Thread.currentThread().interrupt();return new Interpretation(null,"MODEL_UNAVAILABLE",true);}
        catch(Exception ignored) { /* Never log prompts, raw model output, or server-only inputs. */ }
        return new Interpretation(null,"CLARIFICATION_REQUIRED",true);
    }
    /** 의도별 뜻과 예시를 준다. 이름만 주면 작은 모델은 의도를 고르지 못한다. */
    private String prompt(String question,String base,String history) {
        var month=com.sicost.common.ai.AiDataConventions.currentMonth(clock);
        return "한국어 업무 질문을 아래 의도 중 하나의 JSON 으로만 바꾸세요. SQL·URL·함수 이름·쓰기 지시는 만들지 마세요.\n"+
            "의도: PROJECT_PROFIT_RANKING=이번 기간 프로젝트별 손익·이익률 순위(적자·흑자·마진이 큰/작은 프로젝트), PROJECT_PROFIT_TREND=특정 프로젝트의 월별 손익 추이, "+
            "MONTHLY_FINANCE=월 매출·영업이익 등 재무 현황, RECEIVABLES_RISK=미수금·연체 위험, PROJECT_PROGRESS_RISK=프로젝트 진척·지연 위험, "+
            "WORKFORCE_CAPACITY=인력 수급·가동·대기 인원, EXIT_AND_NEXT_POSITION=철수 예정자와 다음 자리, SALES_PIPELINE_FORECAST=영업 기회 기반 매출 예측, "+
            "CASH_FORECAST=자금(현금) 흐름, CLOSE_STATUS=월 마감 상태, WHAT_IF_WORKFORCE=인력 가정 시뮬레이션, WHAT_IF_PROJECT_COST=프로젝트 원가 가정 시뮬레이션, "+
            "FORECAST_PROFIT=앞으로의 손익 예측, FORECAST_STAFF=앞으로의 인력 예측, FORECAST_CASH=앞으로의 현금 예측, FORECAST_RECEIVABLES=앞으로의 미수 예측, "+
            "FORECAST_TURNKEY=턴키 프로젝트 예상 최종 손익.\n"+
            "규칙: 이번달="+month+", 지난달="+month.minusMonths(1)+". 월은 YYYY-MM. 적자가 큰 순서는 sort=PROFIT_ASC, 이익이 큰 순서는 PROFIT_DESC, 이익률(마진)이 낮은 순서는 MARGIN_ASC, 높은 순서는 MARGIN_DESC. "+
            "'3개' 같은 개수는 limit. 기간을 말하면 fromYm·toYm(최근 3개월은 이번달 포함, 앞으로 3개월은 다음달부터). 모르는 값은 넣지 말고, 질문이 모호하면 needsClarification=true.\n"+
            "예: '이번 달 적자가 큰 프로젝트 3개' → {\"intent\":\"PROJECT_PROFIT_RANKING\",\"baseYm\":\""+month+"\",\"sort\":\"PROFIT_ASC\",\"limit\":3}\n"+
            "예: '마진율 높은 프로젝트 5개' → {\"intent\":\"PROJECT_PROFIT_RANKING\",\"baseYm\":\""+month+"\",\"sort\":\"MARGIN_DESC\",\"limit\":5}\n"+
            "예: '지난달 재무 현황' → {\"intent\":\"MONTHLY_FINANCE\",\"baseYm\":\""+month.minusMonths(1)+"\"}\n"+
            "예: '오늘 미수 위험' → {\"intent\":\"RECEIVABLES_RISK\",\"baseYm\":\""+month+"\"}\n"+
            "기준월="+base+" 이전 질문 조건="+history+"\n질문="+question;
    }
    /** 모델은 빈 값을 null 로, 숫자를 문자열로 주곤 한다. 없는 값으로 보고 정리한 뒤 엄격 검증(AnalysisInput)에 넘긴다. */
    static JsonNode clean(JsonNode node) {
        if(node==null||!node.isObject())return node;
        var out=((com.fasterxml.jackson.databind.node.ObjectNode)node).deepCopy();
        var names=new ArrayList<String>();out.fieldNames().forEachRemaining(names::add);
        for(String k:names){JsonNode v=out.get(k);
            if(v==null||v.isNull()||v.isTextual()&&v.textValue().isBlank())out.remove(k);
            else if(v.isObject())out.set(k,clean(v));
            else if("limit".equals(k)&&v.isTextual()&&v.textValue().matches("[0-9]{1,2}"))out.put(k,Integer.parseInt(v.textValue()));}
        if(out.has("filters")&&out.get("filters").isObject()&&out.get("filters").isEmpty())out.remove("filters");
        return out;
    }
    private static final JsonNode SCHEMA=schema();
    private static JsonNode schema() {
        var m=new ObjectMapper();var s=m.createObjectNode().put("type","object");var p=s.putObject("properties");
        var intent=p.putObject("intent").put("type","string");var e=intent.putArray("enum");for(var i:Intent.values())e.add(i.name());
        for(String k:List.of("baseYm","fromYm","toYm"))p.putObject(k).put("type","string").put("pattern","^[0-9]{4}-(0[1-9]|1[0-2])$");
        p.putObject("periodType").put("type","string").putArray("enum").add("MONTH").add("YTD");
        p.putObject("sort").put("type","string").putArray("enum").add("PROFIT_ASC").add("PROFIT_DESC").add("MARGIN_ASC").add("MARGIN_DESC");
        p.putObject("limit").put("type","integer").put("minimum",1).put("maximum",20);
        p.putObject("needsClarification").put("type","boolean");
        s.putArray("required").add("intent");s.put("additionalProperties",false);
        return s;
    }
    @Override public void close(){executor.shutdownNow();}
}
