package com.sicost.candidates;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.candidates.CandidateModels.*;

@RestController
@RequestMapping("/api/candidates")
public class CandidateController {
    private final CandidateService service;
    public CandidateController(CandidateService service){this.service=service;}
    @GetMapping public ApiResponse<CandidatePage> list(@RequestParam Map<String,String> parameters){return ApiResponse.ok(service.list(parameters));}
    @GetMapping("/{id}") public ApiResponse<CandidateDetail> get(@PathVariable long id){return ApiResponse.ok(service.get(id));}
    @PostMapping public ApiResponse<CandidateDetail> create(@RequestBody JsonNode body){return ApiResponse.ok(service.create(body));}
    @PutMapping("/{id}") public ApiResponse<CandidateDetail> update(@PathVariable long id,@RequestBody JsonNode body){return ApiResponse.ok(service.update(id,body));}
    @PostMapping("/{id}/contacts") public ApiResponse<CandidateDetail> contact(@PathVariable long id,@RequestBody JsonNode body){return ApiResponse.ok(service.contact(id,body));}
    @PostMapping("/{id}/convert") public ApiResponse<CandidateConversionResult> convert(@PathVariable long id,@RequestBody JsonNode body){return ApiResponse.ok(service.convert(id,body));}
}
