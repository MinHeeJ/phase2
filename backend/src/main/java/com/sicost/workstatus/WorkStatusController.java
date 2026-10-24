package com.sicost.workstatus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com.sicost.workstatus.WorkStatusModels.*;

@RestController
public class WorkStatusController {
    private final WorkStatusService service;
    private final ObjectReader reader;
    public WorkStatusController(WorkStatusService service,ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private WorkStatusInput input(String body) {
        try {return WorkStatusInputParser.parse(reader.readTree(body));}
        catch(JsonProcessingException ex) {throw ApiException.badRequest("입력값을 확인하세요.",null);}
    }
    @GetMapping("/api/developers/{id}/work-status")
    public ApiResponse<PageResult<WorkStatusRow>> listDeveloperWorkStatuses(@PathVariable long id,
        @RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="50") int size,@RequestParam(required=false) String sort) {
        return ApiResponse.ok(service.list(id,status,new DeveloperPaging(page,size,sort)));
    }
    @PostMapping("/api/developers/{id}/work-status") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkStatusRow> createDeveloperWorkStatus(@PathVariable long id,@RequestBody String body) {
        return ApiResponse.ok(service.create(id,input(body)));
    }
    @PutMapping("/api/developers/{id}/work-status/{histId}")
    public ApiResponse<WorkStatusRow> updateDeveloperWorkStatus(@PathVariable long id,@PathVariable long histId,@RequestBody String body) {
        return ApiResponse.ok(service.update(id,histId,input(body)));
    }
    @DeleteMapping("/api/developers/{id}/work-status/{histId}")
    public ApiResponse<WorkStatusRemoval> updateDeveloperWorkStatusRemoval(@PathVariable long id,@PathVariable long histId) {
        return ApiResponse.ok(service.delete(id,histId));
    }
}
