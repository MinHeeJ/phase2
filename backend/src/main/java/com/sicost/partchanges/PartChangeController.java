package com.sicost.partchanges;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.sicost.common.api.ApiException;
import com.sicost.common.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import static com.sicost.partchanges.PartChangeModels.*;

@RestController
public class PartChangeController {
    private final PartChangeService service;
    private final ObjectReader reader;

    public PartChangeController(PartChangeService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @GetMapping("/api/projects/{id}/part-changes")
    public ApiResponse<List<PartChange>> listProjectPartChanges(@PathVariable long id) {
        return ApiResponse.ok(service.list(id));
    }

    @PostMapping("/api/projects/{id}/part-changes")
    public ApiResponse<List<PartChange>> createProjectPartChange(@PathVariable long id, @RequestBody String body) {
        try {
            return ApiResponse.ok(service.create(id, PartChangeInput.parse(reader.readTree(body))));
        } catch (JsonProcessingException ex) {
            throw ApiException.badRequest("입력값을 확인하세요.", null);
        }
    }
}
