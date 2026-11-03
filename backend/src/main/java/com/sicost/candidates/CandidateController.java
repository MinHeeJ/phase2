package com.sicost.candidates;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.candidates.CandidateModels.*;

@RestController
public class CandidateController {
    private final CandidateService service;
    private final ObjectReader reader;
    public CandidateController(CandidateService service,ObjectMapper json) {
        this.service=service; this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private CandidateWrite input(String body) {
        try { return CandidateInput.parse(reader.readTree(body)); }
        catch(JsonProcessingException e) { throw CandidateInput.invalid(null); }
    }
    @GetMapping("/api/candidates")
    public ApiResponse<CandidatePage> listCandidates(@RequestParam Map<String,String> query) {
        return ApiResponse.ok(service.list(query));
    }
    @GetMapping("/api/candidates/{id}")
    public ApiResponse<CandidateDetail> getCandidate(@PathVariable long id,@RequestParam Map<String,String> query) {
        if(!query.isEmpty()) throw CandidateInput.invalid(query.keySet().iterator().next());
        return ApiResponse.ok(service.get(id));
    }
    @PostMapping("/api/candidates")
    public ApiResponse<CandidateDetail> createCandidate(@RequestBody String body) { return ApiResponse.ok(service.create(input(body))); }
    @PutMapping("/api/candidates/{id}")
    public ApiResponse<CandidateDetail> updateCandidate(@PathVariable long id,@RequestBody String body) {
        return ApiResponse.ok(service.update(id,input(body)));
    }
}
