package com.sicost.allocations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.allocations.ProjectAllocationModels.*;

@RestController
public class ProjectAllocationController {
    private final ProjectAllocationService service;
    private final ObjectReader reader;

    public ProjectAllocationController(ProjectAllocationService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private String stage(Map<String, String> query, boolean required) {
        query.keySet().forEach(k -> {
            if (!k.equals("stage")) throw ApiException.badRequest("조회 조건을 확인하세요.", k);
        });
        return ProjectAllocationInput.stage(query.get("stage"), required);
    }
    @GetMapping("/api/projects/{id}/allocations")
    public ApiResponse<Allocations> getProjectAllocations(@PathVariable long id,
            @RequestParam Map<String, String> query) {
        return ApiResponse.ok(service.get(id, stage(query, false)));
    }
    @PutMapping("/api/projects/{id}/allocations")
    public ApiResponse<Allocations> saveProjectAllocations(@PathVariable long id,
            @RequestParam Map<String, String> query, @RequestBody String body) {
        String selected = stage(query, true);
        try {
            return ApiResponse.ok(service.save(id, selected,
                    ProjectAllocationInput.parse(reader.readTree(body))));
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("입력값을 확인하세요.", "items");
        }
    }
}
