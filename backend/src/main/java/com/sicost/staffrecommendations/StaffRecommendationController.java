package com.sicost.staffrecommendations;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sicost.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.staffrecommendations.StaffRecommendationModels.*;

@RestController
@RequestMapping("/api/ai/staff-recommendations")
public class StaffRecommendationController {
    private final StaffRecommendationService service;
    private final RecommendationCriteriaService criteria;
    private final CareerCardService cards;
    public StaffRecommendationController(StaffRecommendationService service,RecommendationCriteriaService criteria,CareerCardService cards) {
        this.service=service;this.criteria=criteria;this.cards=cards;
    }
    @PostMapping("/search")
    public ApiResponse<Snapshot> createStaffRecommendation(HttpServletRequest request,@RequestBody ObjectNode body) {
        return ApiResponse.ok(service.search(request,StaffRecommendationInput.create(body)));
    }
    @GetMapping("/history")
    public ApiResponse<History> listStaffRecommendationHistory(HttpServletRequest request,@RequestParam Map<String,String> query) {
        return ApiResponse.ok(service.history(request,query));
    }
    @GetMapping("/{requestId}")
    public ApiResponse<Snapshot> getStaffRecommendation(HttpServletRequest request,@PathVariable String requestId) {
        return ApiResponse.ok(service.get(request,StaffRecommendationInput.id(requestId,"requestId")));
    }
    @PatchMapping("/results/{resultId}/decision")
    public ApiResponse<DecisionResult> updateStaffRecommendationDecision(HttpServletRequest request,@PathVariable String resultId,@RequestBody ObjectNode body) {
        return ApiResponse.ok(service.decide(request,StaffRecommendationInput.id(resultId,"resultId"),StaffRecommendationInput.decision(body)));
    }
    @PostMapping("/criteria-draft")
    public ApiResponse<Draft> previewStaffRecommendationCriteria(HttpServletRequest request,@RequestBody ObjectNode body) {
        return ApiResponse.ok(criteria.preview(request,body));
    }
    @GetMapping("/developers/{devId}/career-card")
    public ResponseEntity<byte[]> exportDeveloperCareerCard(HttpServletRequest request,@PathVariable String devId) {
        byte[] bytes=cards.export(request,StaffRecommendationInput.id(devId,"devId"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=career-card.xlsx").cacheControl(CacheControl.noStore()).body(bytes);
    }
}
