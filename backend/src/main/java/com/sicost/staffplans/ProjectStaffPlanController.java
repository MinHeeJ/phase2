package com.sicost.staffplans;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.staffplans.ProjectStaffPlanModels.*;

@RestController
public class ProjectStaffPlanController {
    private final ProjectStaffPlanService service;
    private final ObjectReader reader;
    public ProjectStaffPlanController(ProjectStaffPlanService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    @GetMapping("/api/projects/{id}/staff-plans")
    public ApiResponse<StaffPlans> getProjectStaffPlans(@PathVariable long id) {
        return ApiResponse.ok(service.getProjectStaffPlans(id));
    }
    @PutMapping("/api/projects/{id}/staff-plans")
    public ApiResponse<StaffPlans> saveProjectStaffPlans(@PathVariable long id, @RequestBody String body) {
        try {
            return ApiResponse.ok(service.saveProjectStaffPlans(id, ProjectStaffPlanInput.parse(reader.readTree(body))));
        } catch (JsonProcessingException ex) {
            throw ApiException.badRequest("입력값을 확인하세요.", "items");
        }
    }
}
