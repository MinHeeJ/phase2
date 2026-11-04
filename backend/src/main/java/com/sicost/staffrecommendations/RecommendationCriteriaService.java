package com.sicost.staffrecommendations;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sicost.common.ai.*;
import com.sicost.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.sicost.staffrecommendations.StaffRecommendationModels.*;

@Service
public class RecommendationCriteriaService {
    private final RecommendationBoundary boundary;
    private final AiQuestionSanitizer sanitizer;
    private final AiLocalIntentParser parser;
    private final StaffRecommendationMapper mapper;
    public RecommendationCriteriaService(RecommendationBoundary boundary,AiQuestionSanitizer sanitizer,
            AiLocalIntentParser parser,StaffRecommendationMapper mapper) {
        this.boundary=boundary;this.sanitizer=sanitizer;this.parser=parser;this.mapper=mapper;
    }
    public Draft preview(HttpServletRequest request,ObjectNode body) {
        StaffRecommendationInput.keys(body,Set.of("question"));
        if(!body.path("question").isTextual())throw StaffRecommendationInput.invalid("question");
        var prepared=sanitizer.prepare(body.path("question").asText(),Map.of());
        String owner=boundary.enter(request,AiActions.RECOMMEND);
        var parsed=parser.parseCriteria(prepared);ObjectNode criteria=parsed.value();
        if("OK".equals(parsed.status()))try {
            StaffRecommendationInput.keys(criteria,Set.of("jobCode","startYm","gradeCode","skillCodes","customerId","industryCode","durationMonths","headcount","requestedMm","assumedExitYm"));
            StaffRecommendationInput.validateCriteria(criteria);
            for(var entry:Map.of("jobCode","JOB","gradeCode","GRADE","industryCode","INDUSTRY").entrySet())
                if(criteria.has(entry.getKey())&&!mapper.codes(entry.getValue()).contains(criteria.path(entry.getKey()).asText()))throw StaffRecommendationInput.invalid(entry.getKey());
            var skills=mapper.codes("SKILL");for(var skill:criteria.path("skillCodes"))if(!skills.contains(skill.asText()))throw StaffRecommendationInput.invalid("skillCodes");
            // Model-generated entity IDs are never accepted as server-resolved identities.
            if(criteria.has("customerId"))throw StaffRecommendationInput.invalid("customerId");
        }catch(ApiException e){criteria.removeAll();return new Draft("CLARIFICATION_REQUIRED",criteria,"코드와 조건을 확인해 수동으로 입력하세요.",List.of(),parsed.modelUsed());}
        boundary.current(request,owner);
        String clarification="OK".equals(parsed.status())?null:"모델을 사용할 수 없거나 조건이 불명확합니다. 수동 조건으로 추천을 실행할 수 있습니다.";
        return new Draft(parsed.status(),criteria,clarification,List.of("조건을 수정·확인한 뒤 별도로 추천 실행하세요."),parsed.modelUsed());
    }
}
