package com.sicost.partnersops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.partnersops.PartnerLifecycleModels.*;

@RestController
@RequestMapping("/api/partners")
public class PartnerLifecycleController {
    private final PartnerLifecycleService service;
    public PartnerLifecycleController(PartnerLifecycleService service){this.service=service;}
    @GetMapping("/options") public ApiResponse<List<PartnerOption>> listPartnerOptions(@RequestParam String kind){return ApiResponse.ok(service.options(kind));}
    @GetMapping("/{id}/assignments") public ApiResponse<List<PartnerAssignment>> listPartnerAssignments(@PathVariable String id){return ApiResponse.ok(service.assignments(id));}
    @GetMapping("/{id}/history") public ApiResponse<List<PartnerChange>> listPartnerHistory(@PathVariable String id){return ApiResponse.ok(service.history(id));}
    @PostMapping("/disable") public ApiResponse<PartnerDisableResult> savePartnerDisable(@RequestBody JsonNode input){return ApiResponse.ok(service.disable(PartnerLifecycleInput.ids(input)));}
    @PostMapping("/{id}/enable") public ApiResponse<PartnerDetail> savePartnerEnable(@PathVariable String id){return ApiResponse.ok(service.enable(id));}
    @GetMapping("/export") public ResponseEntity<byte[]> getPartnerExport(@RequestParam(required=false) String kind,
        @RequestParam(required=false) String useYn,@RequestParam(required=false) String q,
        @RequestParam(required=false) List<String> columns,@RequestParam(required=false) List<String> ids) {
        var file=service.export(kind,useYn==null?"Y":useYn,q,columns,ids);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+URLEncoder.encode(file.filename(),StandardCharsets.UTF_8).replace("+","%20"))
            .body(file.bytes());
    }
}
