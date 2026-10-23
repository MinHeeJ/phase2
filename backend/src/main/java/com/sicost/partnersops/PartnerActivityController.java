package com.sicost.partnersops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.sicost.partnersops.PartnerActivityModels.*;

@RestController
@RequestMapping("/api/partners")
public class PartnerActivityController {
    private final PartnerActivityService service;
    public PartnerActivityController(PartnerActivityService service) {this.service=service;}
    @GetMapping("/options") public ApiResponse<List<PartnerOption>> listPartnerOptions(@RequestParam(required=false) String kind) {
        return ApiResponse.ok(service.options(kind));
    }
    @GetMapping("/{id}/assignments") public ApiResponse<List<PartnerAssignment>> listPartnerAssignments(@PathVariable String id) {
        return ApiResponse.ok(service.assignments(id));
    }
    @GetMapping("/{id}/history") public ApiResponse<List<PartnerChange>> listPartnerHistory(@PathVariable String id) {
        return ApiResponse.ok(service.history(id));
    }
    @PostMapping("/disable") public ApiResponse<PartnerDisableResult> savePartnerDisable(@RequestBody JsonNode input) {
        return ApiResponse.ok(service.disable(PartnerActivityInput.ids(input)));
    }
    @PostMapping("/{id}/enable") public ApiResponse<Map<String,Object>> savePartnerEnable(@PathVariable String id) {
        return ApiResponse.ok(service.enable(id));
    }
    @GetMapping("/export") public ResponseEntity<byte[]> getPartnerExport(@RequestParam MultiValueMap<String,String> params) {
        PartnerDownload file=service.export(params.getFirst("kind"),params.containsKey("useYn")?params.getFirst("useYn"):"Y",
            params.getFirst("q"),params.get("columns"),params.get("ids"));
        String name=URLEncoder.encode(file.filename(),StandardCharsets.UTF_8).replace("+","%20");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+name).body(file.bytes());
    }
}
