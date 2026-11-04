package com.sicost.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.ai.*;
import com.sicost.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.sicost.analysis.AnalysisModels.*;

@Service
public class ConversationService {
    private final ConversationMapper mapper;
    private final AiOwnerSession owners;
    private final AiAccessGuard access;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transaction;
    @Autowired private AnalysisRouter router;
    @Autowired private AnalysisModelClient model;
    @Autowired private AiQuestionSanitizer sanitizer;
    @Autowired private Environment environment;
    public ConversationService(ConversationMapper mapper,AiOwnerSession owners,AiAccessGuard access,
            ObjectMapper json,Clock clock,PlatformTransactionManager transactions) {
        this.mapper=mapper;this.owners=owners;this.access=access;this.json=json;this.clock=clock;transaction=new TransactionTemplate(transactions);
    }
    LocalDateTime now(){return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    static OffsetDateTime timestamp(LocalDateTime t){return t.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();}
    Conversation view(ConversationRow row){return new Conversation(row.id(),row.questionSummary(),row.answerSummary(),timestamp(row.createdAt()),timestamp(row.updatedAt()),row.hiddenAt()==null);}
    public Conversation create(HttpServletRequest request) {
        access.requireAction(AiActions.VIEW_ANALYSIS);String owner=owners.ownerHash(request);var at=now();
        int days=Math.max(1,environment.getProperty("sicost.ai.conversation-retention-days",Integer.class,30));
        return transaction.execute(tx->{owners.assertCurrent(request,owner);long id=mapper.create(new ConversationWrite(owner,at,LocalDateTime.ofInstant(owners.expiresAt(request),ZoneId.of("Asia/Seoul")),at.plusDays(days)));return view(mapper.conversation(id));});
    }
    public Page<Conversation> list(HttpServletRequest request,Map<String,String> input) {
        access.requireAction(AiActions.VIEW_ANALYSIS);validateQuery(input,Set.of("page","size","search"));
        int page=AnalysisInput.page(input),size=AnalysisInput.size(input);var search=input.get("search");
        if(search!=null&&search.length()>1000)throw AnalysisInput.invalid("search");
        var query=new ConversationQuery(owners.ownerHash(request),search,now(),(long)page*size,size);
        return new Page<>(mapper.conversations(query).stream().map(this::view).toList(),mapper.count(query),page,size);
    }
    public ConversationRow owned(HttpServletRequest request,long id,boolean hiddenAllowed) {
        access.requireAction(AiActions.VIEW_ANALYSIS);var row=mapper.conversation(id);if(row==null)throw ApiException.notFound("대화가 없습니다.");
        owners.requireOwner(request,row.ownerHash());if(!now().isBefore(row.retentionUntil())||!hiddenAllowed&&row.hiddenAt()!=null)throw ApiException.notFound("표시 가능한 대화가 없습니다.");return row;
    }
    public Messages get(HttpServletRequest request,long id,Map<String,String> input) {
        validateQuery(input,Set.of("page","size"));int page=AnalysisInput.page(input),size=AnalysisInput.size(input);var row=owned(request,id,false);
        return new Messages(mapper.messages(new MessageQuery(id,(long)page*size,size)).stream().map(this::stored).toList(),mapper.messageCount(id),page,size,view(row));
    }
    public Visibility hide(HttpServletRequest request,long id) {
        return transaction.execute(tx->{owned(request,id,true);mapper.hide(new MessageQuery(id,0,0));return new Visibility(id,false);});
    }
    MessageRow message(HttpServletRequest request,long id,long messageId) {
        owned(request,id,false);var row=mapper.message(messageId);if(row==null)throw ApiException.notFound("메시지가 없습니다.");
        if(row.conversationId()!=id){owned(request,row.conversationId(),false);throw AiAccessGuard.forbidden();}return row;
    }
    public Feedback feedback(HttpServletRequest request,long id,long messageId,FeedbackInput input) {
        access.requireAction(AiActions.ASK);return transaction.execute(tx->{message(request,id,messageId);
            var reason=mask(input.reason());mapper.feedback(new FeedbackWrite(messageId,input.rating(),reason));return new Feedback(messageId,input.rating(),reason);});
    }
    public Request exportQuery(HttpServletRequest request,long id,long messageId) {
        access.requireAction(AiActions.EXPORT_ANALYSIS);var row=message(request,id,messageId);var query=decodeQuery(row.criteria());
        if(query==null||query.intent()==Intent.WHAT_IF_PROJECT_COST)throw ApiException.badRequest("가상 금액은 보존하지 않습니다. 정형 분석에서 독립 가상값을 다시 입력해 주세요.","virtualCostAssumption");
        return new Request(query,null);
    }
    public Answer ask(HttpServletRequest request,long id,MessageInput input) {
        access.requireAction(AiActions.ASK);var owner=owned(request,id,false).ownerHash();
        var recent=mapper.context(id);if(input.contextMessageId()!=null){var selected=message(request,id,input.contextMessageId());
            if(recent.stream().noneMatch(m->m.id()==selected.id()))throw AnalysisInput.invalid("contextMessageId");}
        if(Pattern.compile("실제\\s*개인|개인\\s*(급여|원가)|연봉|주민|역산|payroll|salary",Pattern.CASE_INSENSITIVE).matcher(input.question()).find())throw AiAccessGuard.forbidden();
        Answer answer;String summary;Request virtual=virtual(input);
        if(virtual!=null){answer=router.execute(virtual);summary="독립 가상 원가 계산 (금액 미보존)";}
        else {
            var safe=sanitizer.prepare(input.question(),Map.of());summary=safe.needsClarification()?"질문 조건 재입력 필요":safe.modelQuestion();
            if(safe.needsClarification())answer=AnalysisRouter.state("CLARIFICATION_REQUIRED",null,"개인정보와 민감 금액을 제거하고 대상·기간을 다시 입력해 주세요.");
            else if(Pattern.compile("(마감|배정|계약|청구|급여|지급).*(확정해|생성해|변경해|취소해|삭제해)").matcher(safe.modelQuestion()).find())
                answer=AnalysisRouter.state("UNSUPPORTED",null,"업무 변경은 실행하지 않습니다. 마감 상태 등 읽기 분석을 선택해 주세요.");
            else if((safe.modelQuestion().contains("그중")||safe.modelQuestion().contains("그 중"))&&recent.isEmpty())
                answer=AnalysisRouter.state("CLARIFICATION_REQUIRED",null,"참조할 성공 질문이 없습니다. 프로젝트와 기간을 지정해 주세요.");
            else {
                List<Query> context=recent.stream().map(r->decodeQuery(r.criteria())).filter(Objects::nonNull).limit(5).toList();
                var interpreted=model.interpret(safe.modelQuestion(),input.baseYm(),context);
                if(interpreted.request()==null)answer=AnalysisRouter.state(interpreted.status(),null,"질문을 해석할 수 없습니다. 모델 연결을 확인하거나 정형 분석을 이용해 주세요.").withModel(interpreted.modelUsed());
                else {
                    var approved=resolveMonth(interpreted.request(),input,safe.modelQuestion());
                    if((safe.modelQuestion().contains("그중")||safe.modelQuestion().contains("그 중"))&&!context.isEmpty()){
                        var previous=context.get(0);var q=approved.query();
                        if(!q.filters().references().isEmpty()&&!previous.filters().references().containsAll(q.filters().references()))throw AiAccessGuard.forbidden();
                    }
                    answer=router.execute(approved).withModel(interpreted.modelUsed());
                }
            }
        }
        if(Thread.currentThread().isInterrupted())throw new ApiException(503,"SERVICE_UNAVAILABLE","요청이 중단되었습니다.",null);
        final Answer result=answer;final String savedSummary=summary;
        return transaction.execute(tx->{owners.assertCurrent(request,owner);owned(request,id,false);
            Query approved=result.interpretedQuery();
            if(approved!=null&&approved.intent()==Intent.PROJECT_PROFIT_RANKING&&!result.table().isEmpty()) {
                var ids=result.table().get(0).rows().stream().filter(ProfitRow.class::isInstance).map(ProfitRow.class::cast).map(ProfitRow::projectId).distinct().limit(20).toList();
                var f=approved.filters();approved=new Query(approved.intent(),approved.baseYm(),approved.fromYm(),approved.toYm(),approved.periodType(),approved.sort(),approved.limit(),new Filters(ids,f.customerIds(),f.partIds(),f.devIds(),f.jobCodes(),f.gradeCodes(),f.skillCodes()),approved.workforceAssumptions(),approved.needsClarification());
            }
            String answerSummary=virtual!=null?"독립 가상 계산 완료 · 수치 재조회에는 가상값 재입력 필요":result.status()+" · "+(approved==null?"질문 조건 확인":approved.intent()+" · "+approved.baseYm());
            var write=new MessageWrite(id,input.contextMessageId(),savedSummary,answerSummary,approved,approved==null?null:encode(approved),encode(result.sourceRefs()),result.status(),now());
            if(mapper.updateTime(write)!=1)throw ApiException.notFound("표시 가능한 대화가 없습니다.");
            long messageId=mapper.append(write);owners.assertCurrent(request,owner);return result.withMessage(messageId);
        });
    }
    private Request resolveMonth(Request request,MessageInput input,String question) {
        var q=request.query();String base=input.baseYm();var explicit=Pattern.compile("[0-9]{4}-(?:0[1-9]|1[0-2])").matcher(question);
        if(explicit.find())base=explicit.group();
        YearMonth current=AiDataConventions.currentMonth(clock);String from=q.fromYm(),to=q.toYm();
        if(base==null){base=question.contains("지난달")||question.contains("지난 달")?current.minusMonths(1).toString():current.toString();
            if(question.contains("최근 3개월")){from=current.minusMonths(2).toString();to=current.toString();}
            if(question.contains("앞으로 3개월")){from=current.plusMonths(1).toString();to=current.plusMonths(3).toString();}}
        return new Request(new Query(q.intent(),base,from,to,q.periodType(),q.sort(),q.limit(),q.filters(),q.workforceAssumptions(),q.needsClarification()),null);
    }
    private Request virtual(MessageInput input) {
        if(!input.question().contains("가상"))return null;
        BigDecimal revenue=amount(input.question(),"매출"),cost=amount(input.question(),"원가");
        var percent=Pattern.compile("([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*%").matcher(input.question());
        if(revenue==null||cost==null||!percent.find())return null;
        var root=json.createObjectNode().put("intent","WHAT_IF_PROJECT_COST");if(input.baseYm()!=null)root.put("baseYm",input.baseYm());
        root.putObject("virtualCostAssumption").put("revenue",revenue).put("cost",cost).put("costChangePercent",new BigDecimal(percent.group(1)));
        return AnalysisInput.structured(root,clock);
    }
    private BigDecimal amount(String question,String label) {
        var match=Pattern.compile(label+"\\s*([0-9,]+)\\s*(억원|천만원|백만원|만원|원)?").matcher(question);if(!match.find())return null;
        var n=new BigDecimal(match.group(1).replace(",",""));var unit=match.group(2);
        return n.multiply(unit==null||"원".equals(unit)?BigDecimal.ONE:new BigDecimal(switch(unit){case "억원"->"100000000";case "천만원"->"10000000";case "백만원"->"1000000";default->"10000";}));
    }
    private String mask(String input){if(input==null)return null;var prepared=sanitizer.prepare(input.isBlank()?"내용 없음":input,Map.of());return prepared.needsClarification()?"[민감 내용 제거]":prepared.modelQuestion();}
    private StoredMessage stored(MessageRow row){return new StoredMessage(row.id(),row.question(),row.answer(),row.intent()==null?null:Intent.valueOf(row.intent()),decodeQuery(row.criteria()),row.baseYm(),decodeSources(row.sources()),row.status(),timestamp(row.createdAt()),row.rating()==null?null:new Feedback(row.id(),row.rating(),row.reason()));}
    private Query decodeQuery(String value){try{return value==null||"null".equals(value)?null:json.readValue(value,Query.class);}catch(Exception e){throw new IllegalStateException("승인 조건을 읽을 수 없습니다.");}}
    private List<Source> decodeSources(String value){try{return json.readValue(value,new TypeReference<List<Source>>(){});}catch(Exception e){throw new IllegalStateException("출처 요약을 읽을 수 없습니다.");}}
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("요약을 저장할 수 없습니다.");}}
    static void validateQuery(Map<String,String> input,Set<String> allowed){input.keySet().forEach(k->{if(!allowed.contains(k))throw AnalysisInput.invalid(k);});}
}
