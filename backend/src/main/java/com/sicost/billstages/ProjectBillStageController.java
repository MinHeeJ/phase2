package com.sicost.billstages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.sicost.common.api.ApiException;
import com.sicost.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import static com.sicost.billstages.ProjectBillStageModels.*;

@RestController
public class ProjectBillStageController {
    private final ProjectBillStageService service;
    private final ObjectReader reader;

    public ProjectBillStageController(ProjectBillStageService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @GetMapping("/api/projects/{id}/bill-stages")
    public ApiResponse<Stages> listProjectBillStages(@PathVariable long id) {
        return ApiResponse.ok(service.list(id));
    }

    @PutMapping("/api/projects/{id}/bill-stages")
    public ApiResponse<Stages> saveProjectBillStages(@PathVariable long id, @RequestBody String body) {
        try {
            return ApiResponse.ok(service.save(id, ProjectBillStageInput.parse(reader.readTree(body))));
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("입력값을 확인하세요.", "items");
        }
    }
}
