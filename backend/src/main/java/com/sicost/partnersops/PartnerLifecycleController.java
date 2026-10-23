package com.sicost.partnersops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.sicost.partnersops.PartnerLifecycleModels.*;

@RestController
@RequestMapping("/api/partners")
public class PartnerLifecycleController {
    private final PartnerLifecycleService service;
    private final ObjectReader reader;
    public PartnerLifecycleController(PartnerLifecycleService service,ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @GetMapping("/options") public ApiResponse<List<PartnerOption>> listPartnerOptions(@RequestParam String kind) {return ApiResponse.ok(service.listPartnerOptions(kind));}
    @GetMapping("/{id}/assignments") public ApiResponse<List<PartnerAssignment>> listPartnerAssignments(@PathVariable String id) {return ApiResponse.ok(service.listPartnerAssignments(id));}
    @GetMapping("/{id}/history") public ApiResponse<List<PartnerChange>> listPartnerHistory(@PathVariable String id) {return ApiResponse.ok(service.listPartnerHistory(id));}
    @PostMapping("/disable") public ApiResponse<PartnerDisableResult> savePartnerDisable(@RequestBody String body) {
        JsonNode input;try {input=reader.readTree(body);}catch(JsonProcessingException ex){throw PartnerExportQuery.invalid("ids");}
        return ApiResponse.ok(service.savePartnerDisable(PartnerExportQuery.disableIds(input)));
    }
    @PostMapping("/{id}/enable") public ApiResponse<Map<String,Object>> savePartnerEnable(@PathVariable String id) {return ApiResponse.ok(service.savePartnerEnable(id));}
    @GetMapping("/export") public ResponseEntity<byte[]> getPartnerExport(@RequestParam MultiValueMap<String,String> params) {
        PartnerFile file=service.getPartnerExport(PartnerExportQuery.from(params,service.today()));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+URLEncoder.encode(file.filename(),StandardCharsets.UTF_8).replace("+","%20"))
            .body(file.bytes());
    }
}
