package com.sicost.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.ai.*;
import com.sicost.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import static com.sicost.analysis.AnalysisModels.*;

@RestController
@RequestMapping("/api/ai/analysis")
public class AnalysisController {
    private final AnalysisRouter router;
    private final ConversationService conversations;
    private final AnalysisExportService exports;
    private final AiAccessGuard access;
    private final AnalysisModelClient model;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private ObjectProvider<AiNextPositionReader> nextPositions;
    public AnalysisController(AnalysisRouter router,ConversationService conversations,AnalysisExportService exports,
            AiAccessGuard access,AnalysisModelClient model,Clock clock){this.router=router;this.conversations=conversations;this.exports=exports;this.access=access;this.model=model;this.clock=clock;}
    @GetMapping("/capabilities") public ApiResponse<Capabilities> getAnalysisCapabilities() {
        access.requireAction(AiActions.VIEW_ANALYSIS);
        var capabilities=Arrays.stream(Intent.values()).map(i->{boolean available=i!=Intent.EXIT_AND_NEXT_POSITION||nextPositions.getIfAvailable()!=null;
            return new Capability(i,available,switch(i){case PROJECT_PROFIT_TREND,FORECAST_TURNKEY->List.of("projectIds");case EXIT_AND_NEXT_POSITION->List.of("devIds");case WHAT_IF_PROJECT_COST->List.of("virtualCostAssumption");case WHAT_IF_WORKFORCE->List.of("workforceAssumptions");default->List.of("baseYm");},AnalysisRouter.allowedFilters(i),i==Intent.PROJECT_PROFIT_TREND||i==Intent.MONTHLY_FINANCE?12:36,20,available?null:"UI-30 비저장 엔진 미연결");}).toList();
        return ApiResponse.ok(new Capabilities(capabilities,model.ready()?"준비":"연결불가",List.of("이번 달 적자가 큰 프로젝트 3개","프로젝트 손익 추이","월 재무 현황","오늘 미수 위험","인력 수급","마감 상태"),false));
    }
    @PostMapping("/structured") public ApiResponse<Answer> createStructuredAnalysis(@RequestBody JsonNode input) {
        access.requireAction(AiActions.ASK);return ApiResponse.ok(router.execute(AnalysisInput.structured(input,clock)));
    }
    @PostMapping("/structured/export") public ResponseEntity<byte[]> exportStructuredAnalysis(@RequestBody JsonNode input) {
        access.requireAction(AiActions.EXPORT_ANALYSIS);return file(exports.export(AnalysisInput.structured(input,clock)));
    }
    @PostMapping("/conversations") public ApiResponse<Conversation> createAnalysisConversation(HttpServletRequest request,@RequestBody(required=false) JsonNode input) {
        access.requireAction(AiActions.VIEW_ANALYSIS);noBody(input);return ApiResponse.ok(conversations.create(request));
    }
    @GetMapping("/conversations") public ApiResponse<Page<Conversation>> listAnalysisConversations(HttpServletRequest request,@RequestParam Map<String,String> query) {
        return ApiResponse.ok(conversations.list(request,query));
    }
    @GetMapping("/conversations/{id}") public ApiResponse<Messages> getAnalysisConversation(HttpServletRequest request,@PathVariable String id,@RequestParam Map<String,String> query) {
        return ApiResponse.ok(conversations.get(request,AnalysisInput.id(id,"id"),query));
    }
    @DeleteMapping("/conversations/{id}") public ApiResponse<Visibility> updateAnalysisConversationVisibility(HttpServletRequest request,@PathVariable String id,@RequestBody(required=false) JsonNode input) {
        access.requireAction(AiActions.VIEW_ANALYSIS);noBody(input);return ApiResponse.ok(conversations.hide(request,AnalysisInput.id(id,"id")));
    }
    @PostMapping("/conversations/{id}/messages") public DeferredResult<ApiResponse<Answer>> createAnalysisMessage(HttpServletRequest request,@PathVariable String id,@RequestBody JsonNode input) {
        access.requireAction(AiActions.ASK);long conversation=AnalysisInput.id(id,"id");var message=AnalysisInput.message(input);
        conversations.owned(request,conversation,false);
        var result=new DeferredResult<ApiResponse<Answer>>(135000L);var thread=new AtomicReference<Thread>();
        var future=CompletableFuture.runAsync(()->{thread.set(Thread.currentThread());try {result.setResult(ApiResponse.ok(conversations.ask(request,conversation,message)));}
            catch(RuntimeException ex){result.setErrorResult(ex);}finally {thread.set(null);Thread.interrupted();}});
        Runnable cancel=()->{var active=thread.get();if(active!=null)active.interrupt();future.cancel(true);};
        result.onTimeout(()->{cancel.run();result.setErrorResult(new com.sicost.common.api.ApiException(503,"SERVICE_UNAVAILABLE","처리가 중단되었습니다. 대화 이력을 확인해 주세요.",null));});
        result.onError(ex->cancel.run());result.onCompletion(()->{if(!future.isDone())cancel.run();});return result;
    }
    @PostMapping("/conversations/{id}/messages/{messageId}/feedback") public ApiResponse<Feedback> saveAnalysisMessageFeedback(HttpServletRequest request,@PathVariable String id,@PathVariable String messageId,@RequestBody JsonNode input) {
        access.requireAction(AiActions.ASK);return ApiResponse.ok(conversations.feedback(request,AnalysisInput.id(id,"id"),AnalysisInput.id(messageId,"messageId"),AnalysisInput.feedback(input)));
    }
    @GetMapping("/conversations/{id}/messages/{messageId}/export") public ResponseEntity<byte[]> exportAnalysisMessage(HttpServletRequest request,@PathVariable String id,@PathVariable String messageId) {
        access.requireAction(AiActions.EXPORT_ANALYSIS);return file(exports.export(conversations.exportQuery(request,AnalysisInput.id(id,"id"),AnalysisInput.id(messageId,"messageId"))));
    }
    private void noBody(JsonNode input){if(input!=null)throw AnalysisInput.invalid("body");}
    private ResponseEntity<byte[]> file(byte[] bytes){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=analysis.xlsx").body(bytes);}
}
