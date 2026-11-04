package com.sicost.staffrecommendations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sicost.aiops.RecommendationOptionsService;
import com.sicost.aiops.RecommendationOptionsModels.*;
import com.sicost.common.ai.*;
import com.sicost.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.staffrecommendations.StaffRecommendationModels.*;
import static com.sicost.staffrecommendations.StaffRecommendationScorer.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class StaffRecommendationService {
    private final StaffRecommendationMapper mapper;
    private final RecommendationOptionsService options;
    private final RecommendationBoundary boundary;
    private final ObjectMapper json;
    private final Clock clock;
    public StaffRecommendationService(StaffRecommendationMapper mapper,RecommendationOptionsService options,
            RecommendationBoundary boundary,ObjectMapper json,Clock clock) {
        this.mapper=mapper;this.options=options;this.boundary=boundary;this.json=json;this.clock=clock;
    }
    private LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    private ObjectNode object(String value) {try{return (ObjectNode)json.readTree(value);}catch(JsonProcessingException e){throw unavailable();}}
    private JsonNode tree(String value) {try{return json.readTree(value);}catch(JsonProcessingException e){throw unavailable();}}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw unavailable();}}
    private static ApiException unavailable(){return new ApiException(503,"SERVICE_UNAVAILABLE","추천 원천을 조회할 수 없습니다.",null);}
    private void codes(ObjectNode c) {
        for(var pair:Map.of("jobCode","JOB","gradeCode","GRADE","industryCode","INDUSTRY").entrySet())
            if(c.hasNonNull(pair.getKey())&&!mapper.codes(pair.getValue()).contains(c.path(pair.getKey()).asText()))throw StaffRecommendationInput.invalid(pair.getKey());
        var skills=mapper.codes("SKILL");for(var skill:c.path("skillCodes"))if(!skills.contains(skill.asText()))throw StaffRecommendationInput.invalid("skillCodes");
        if(c.hasNonNull("customerId")&&!mapper.customerExists(c.path("customerId").longValue()))throw ApiException.notFound("고객이 없습니다.");
    }
    private ObjectNode effective(Create input,SourceRow source) {
        ObjectNode c=json.createObjectNode();
        if(source!=null) {
            c.put("jobCode",source.jobCode()).put("startYm",source.startYm()).put("durationMonths",source.durationMonths())
                .put("headcount",source.remainingHeadcount()).put("requestedMm",source.requestedMm());
            if(source.gradeCode()!=null)c.put("gradeCode",source.gradeCode());
            if(source.customerId()!=null)c.put("customerId",source.customerId());
            if(source.industryCode()!=null)c.put("industryCode",source.industryCode());

        }
        c.setAll(input.criteria());
        if(!c.has("durationMonths"))c.put("durationMonths",1);
        if(!c.has("headcount"))c.put("headcount",1);
        if(!c.has("requestedMm"))c.put("requestedMm",number("1"));
        if(!c.hasNonNull("jobCode"))throw StaffRecommendationInput.invalid("jobCode");
        if(!c.hasNonNull("startYm"))throw StaffRecommendationInput.invalid("startYm");
        StaffRecommendationInput.validateCriteria(c);codes(c);return c;
    }

    private record Candidate(long devId,ObjectNode person,ObjectNode criteria,SourceRow slot,Long handle,ObjectNode view,Breakdown score) {}
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Snapshot search(HttpServletRequest request,Create input) {
        String owner=boundary.enter(request,AiActions.RECOMMEND);
        var people=mapper.people();var candidates=new ArrayList<Candidate>();
        ObjectNode criteria;SourceRow selected=null;
        if(A.equals(input.direction())) {
            if(input.sourceId()!=null)selected=options.resolveCurrentSource(request,input.sourceId());
            criteria=effective(input,selected);
            if(input.sourceId()!=null&&!input.criteria().has("skillCodes")) {
                var selectedOptions=options.get(request,Map.of("sourceId",input.sourceId().toString()));
                if(!selectedOptions.sources().isEmpty())criteria.set("skillCodes",json.valueToTree(selectedOptions.sources().get(0).criteria().skillCodes()));
            }
            for(var row:people) {
                ObjectNode p=object(row.document());
                if(!excluded(p,YearMonth.parse(criteria.path("startYm").asText()),criteria.path("durationMonths").asInt()))
                    candidates.add(candidate(row.id(),p,criteria,selected,input.sourceId()));
            }
        } else {
            Person person=people.stream().filter(p->p.id()==input.devId()).findFirst().orElseThrow(()->ApiException.notFound("개발자가 없습니다."));
            ObjectNode p=object(person.document());LocalDate exit=null;String likelihood=null;
            for(var a:p.path("assignments"))if("ACTIVE".equals(a.path("status").asText())&&!LocalDate.parse(a.path("start").asText()).isAfter(today())
                &&!LocalDate.parse(a.path("end").asText()).isBefore(today())) {
                LocalDate end=LocalDate.parse(a.path("end").asText());if(exit==null||end.isAfter(exit)){exit=end;likelihood=a.path("extension").isNull()?null:a.path("extension").asText();}
            }
            boolean estimated=exit==null;
            if(exit==null) {
                if(!input.criteria().hasNonNull("assumedExitYm"))throw StaffRecommendationInput.invalid("assumedExitYm");
                exit=YearMonth.parse(input.criteria().path("assumedExitYm").asText()).atDay(1);
            }
            YearMonth next=YearMonth.from(exit).plusMonths(1),last=YearMonth.from(exit).plusMonths(3);
            criteria=input.criteria().deepCopy();if(!criteria.has("requestedMm"))criteria.put("requestedMm",number("1"));codes(criteria);
            for(var option:options.get(request,Map.of()).sources()) {
                SourceRow slot=options.resolveCurrentSource(request,option.sourceId());
                YearMonth start=YearMonth.parse(slot.startYm()),end=start.plusMonths(slot.durationMonths()-1);
                YearMonth actual=start.isAfter(next)?start:next;
                if(start.isAfter(last)||actual.isAfter(end)||slot.remainingHeadcount()<1)continue;
                ObjectNode c=json.valueToTree(option.criteria());c.setAll(input.criteria());
                c.put("startYm",actual.toString()).put("durationMonths",(int)ChronoUnit.MONTHS.between(actual,end)+1);
                if(!c.has("requestedMm"))c.put("requestedMm",number("1"));
                if(excluded(p,actual,c.path("durationMonths").asInt()))continue;
                var candidate=candidate(person.id(),p,c,slot,option.sourceId());var view=candidate.view();
                view.put("positionStartYm",start.toString()).put("positionEndYm",end.toString()).put("recommendedStartYm",actual.toString())
                    .put("remainingHeadcount",slot.remainingHeadcount()).put("waitingDays",Math.max(0,ChronoUnit.DAYS.between(exit,actual.atDay(1))-1))
                    .put("waitingDaysEstimated",estimated).put("extensionLikelihood",likelihood);
                if(!estimated)view.put("exitDate",exit.toString());
                if(estimated){((ArrayNode)view.get("availabilityWarnings")).add("UNKNOWN_EXIT");view.put("availabilityStatus","판정 불가");}
                if("H".equals(likelihood))((ArrayNode)view.get("availabilityWarnings")).add("연장 가능성 높음, 일정 확인 필요");
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.<Candidate,BigDecimal>comparing(c->c.score().total()).reversed()
            .thenComparing(c->c.score().job_scr(),Comparator.reverseOrder()).thenComparing(c->c.score().skl_scr(),Comparator.reverseOrder())
            .thenComparing(c->c.view().path("earliestStartYm").isNull()?"9999-99":c.view().path("earliestStartYm").asText())
            .thenComparingLong(Candidate::devId).thenComparing(c->c.slot()==null?"":c.slot().sourceType())
            .thenComparingLong(c->c.slot()==null?0:c.slot().rowId()));
        long id=mapper.nextRequestId();List<ObjectNode> results=new ArrayList<>();List<ResultWrite> writes=new ArrayList<>();
        for(var c:candidates.stream().limit(10).toList()) {
            long resultId=mapper.nextResultId(input.direction());var view=c.view();int rank=results.size()+1;
            view.put("resultId",resultId).put("rank",rank).put("decisionStatus","PENDING");results.add(view);
            SourceRow slot=c.slot();writes.add(new ResultWrite(resultId,id,c.devId(),slot==null?null:slot.projectId(),
                slot!=null&&"PROJECT".equals(slot.sourceType())?slot.rowId():null,slot==null?null:slot.needId(),rank,c.score(),view.path("reason").asText(),view.path("availabilityWarnings").toString()));
        }
        String at=OffsetDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString();
        List<String> missing=results.isEmpty()?List.of():List.of("approvedRelatedJobMapping","costComparison","performanceEvidence");
        Snapshot snapshot=new Snapshot(id,input.direction(),criteria,at,results,missing,List.of());
        ObjectNode document=json.valueToTree(snapshot);document.put("sourceFingerprint",mapper.fingerprint());
        String type=B.equals(input.direction())?"NEXT":selected==null?"TEXT":selected.sourceType();
        boundary.current(request,owner);
        mapper.insertRequest(new RequestWrite(id,owner,AiDataConventions.requestType(type),selected==null?null:selected.projectId(),selected==null?null:selected.needId(),document.toString(),
            "{\"job_scr\":25,\"skl_scr\":20,\"cust_scr\":15,\"grd_scr\":10,\"achv_scr\":10,\"exp_scr\":10,\"asgn_psbl_scr\":10}",LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")))));
        for(var row:writes)if(A.equals(input.direction()))mapper.insertA(row);else mapper.insertB(row);
        return snapshot;
    }
    private Candidate candidate(long devId,ObjectNode p,ObjectNode criteria,SourceRow slot,Long handle) {
        BigDecimal cost=number("5");
        if(mapper.hasCostEvidence(devId)) {
            String raw=mapper.syntheticCost(devId,criteria.path("startYm").asText());
            if(raw!=null){JsonNode input=tree(raw);cost=cost(input.hasNonNull("price")?input.path("price").decimalValue():null,input.hasNonNull("cost")?input.path("cost").decimalValue():null);}
        }
        Breakdown score=score(p,criteria,mapper.grades(),cost,today());ObjectNode view=availability(json,p,criteria);
        view.put("devId",devId).put("developerName","개발자 · "+devId).put("developerNumber","내부 참조 · "+devId)
            .put("score",score.total()).put("jobCode",p.path("job").asText("미기록"));
        if(p.hasNonNull("grade"))view.set("gradeCode",p.get("grade"));
        view.set("skillCodes",p.path("skills"));view.set("breakdown",json.valueToTree(score));
        var sources=view.putArray("sourceIds");if(handle!=null)sources.add(handle);
        if(slot!=null){if(slot.projectId()!=null)view.put("projectId",slot.projectId());if(slot.needId()!=null)view.put("needId",slot.needId());}
        view.put("reason","직무 "+score.job_scr()+" · 구조화 스킬 "+score.skl_scr()+" · 실수행 고객/업종 "+score.cust_scr()+". 일정 위험은 점수와 별도로 확인하세요.");
        view.putArray("evidence").add("DB 직무·스킬 코드 및 실제 배정 이력 기준").add("비용 근거 부재 시 비교 불가 · 관련 직무 승인 매핑 미기록");
        String requestedGrade=criteria.path("gradeCode").asText();
        if(!requestedGrade.isBlank()&&!requestedGrade.equals(p.path("grade").asText()))((ArrayNode)view.get("availabilityWarnings")).add("요청 등급과 다름: 미달/초과 확인 필요");
        view.set("career",tree(mapper.career(devId)));return new Candidate(devId,p,criteria,slot,handle,view,score);
    }
    private Stored owned(HttpServletRequest request,long id) {
        var stored=mapper.request(id);if(stored==null)throw ApiException.notFound("추천 이력이 없습니다.");boundary.own(request,stored.owner());return stored;
    }
    public Snapshot get(HttpServletRequest request,long id) {
        boundary.enter(request,AiActions.VIEW_RECOMMENDATIONS);return materialize(owned(request,id),true);
    }
    private Snapshot materialize(Stored stored,boolean career) {
        ObjectNode doc=object(stored.document());String direction=doc.path("direction").asText();var states=mapper.states(stored.id(),direction);
        List<ObjectNode> results=new ArrayList<>();for(var n:doc.path("results")) {
            ObjectNode row=((ObjectNode)n).deepCopy();var state=states.stream().filter(s->s.id()==row.path("resultId").longValue()).findFirst().orElseThrow(StaffRecommendationService::unavailable);
            row.put("decisionStatus",state.decision());row.put("rejectionReason",state.reason());
            if(state.assignmentId()!=null)row.put("assignmentId",state.assignmentId());if(state.temporaryAssignmentId()!=null)row.put("temporaryAssignmentId",state.temporaryAssignmentId());
            if("ADOPT".equals(state.decision()))row.put("assignmentLinkStatus",state.assignmentId()!=null||state.temporaryAssignmentId()!=null?"배정 연결됨":"채택됨·배정 미생성");
            if(career)row.set("career",tree(mapper.career(state.devId())));results.add(row);
        }
        return new Snapshot(stored.id(),direction,(ObjectNode)doc.path("criteria"),doc.path("evaluatedAt").asText(),results,new ArrayList<>(strings(doc.path("missingDataFields"))),new ArrayList<>(strings(doc.path("warnings"))));
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public DecisionResult decide(HttpServletRequest request,long id,Decision input) {
        boundary.enter(request,AiActions.DECISION);var row=mapper.result(id,input.direction());if(row==null)throw ApiException.notFound("추천 결과가 없습니다.");
        Stored stored=owned(request,row.requestId());var document=object(stored.document());
        if(!input.direction().equals(document.path("direction").asText()))throw ApiException.notFound("방향과 결과가 일치하지 않습니다.");
        if(!"PENDING".equals(row.decision()))throw ApiException.conflict("CONFLICT","이미 결정된 추천입니다.",null);
        if(!Objects.equals(document.path("sourceFingerprint").asText(),mapper.fingerprint()))throw ApiException.conflict("STALE_RECOMMENDATION","추천 원천이 변경되었습니다. 다시 실행하세요.",null);
        if(mapper.transition(new Transition(id,input.direction(),input.decision(),input.reason()))!=1)throw ApiException.conflict("CONFLICT","이미 결정된 추천입니다.",null);
        return new DecisionResult(id,input.direction(),input.decision(),input.reason(),false);
    }
    public History history(HttpServletRequest request,Map<String,String> query) {
        StaffRecommendationInput.history("",query);String owner=boundary.enter(request,AiActions.VIEW_RECOMMENDATIONS);
        var filter=StaffRecommendationInput.history(owner,query);var rows=mapper.history(filter);var all=rows.stream().map(r->materialize(r,false)).toList();
        int adopt=0,reject=0,linked=0,observed=0,extended=0,recent=0;
        for(int i=0;i<all.size();i++)for(var result:all.get(i).results()) {
            if("ADOPT".equals(result.path("decisionStatus").asText())) {
                adopt++;if(!rows.get(i).createdAt().toLocalDate().isBefore(today().minusMonths(3)))recent++;
                if(result.has("assignmentId")||result.has("temporaryAssignmentId"))linked++;
                if(result.has("assignmentId")){int outcome=mapper.observed(result.path("assignmentId").longValue(),today());if(outcome>=0){observed++;extended+=outcome;}}
            }else if("REJECT".equals(result.path("decisionStatus").asText()))reject++;
        }
        long offset=(long)filter.page()*filter.size();var page=all.stream().skip(offset).limit(filter.size()).toList();
        return new History(page,all.size(),filter.page(),filter.size(),new Quality(rate(adopt,adopt+reject),rate(linked,adopt),rate(extended,observed),recent,recent<20?List.of("배점 근거 부족"):List.of()));
    }
    private Rate rate(int n,int d){return new Rate(n,d,AiDataConventions.ratioPercent(BigDecimal.valueOf(n),BigDecimal.valueOf(d)));}
    public JsonNode career(HttpServletRequest request,long devId) {
        String owner=boundary.enter(request,AiActions.EXPORT_RECOMMENDATIONS);
        if(mapper.people().stream().noneMatch(p->p.id()==devId))throw ApiException.notFound("개발자가 없습니다.");
        boolean allowed=false;
        for(var row:mapper.history(StaffRecommendationInput.history(owner,Map.of())))for(var result:object(row.document()).path("results"))if(result.path("devId").longValue()==devId)allowed=true;
        if(!allowed)throw AiAccessGuard.forbidden();return tree(mapper.career(devId));
    }
}
