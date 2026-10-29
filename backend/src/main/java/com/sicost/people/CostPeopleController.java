package com.sicost.people;

import com.sicost.common.api.ApiResponse;
import com.sicost.common.costallocation.CostAnalysisInput;
import com.sicost.common.security.CostAnalysisActions;
import com.sicost.common.security.PermissionService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.sicost.people.CostPeopleModels.*;

@RestController
public class CostPeopleController {
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final CostPeopleService service;
    private final CostPeopleExportService exports;
    private final PermissionService permissions;
    private final Clock clock;
    public CostPeopleController(CostPeopleService service, CostPeopleExportService exports,
            PermissionService permissions, Clock clock) {
        this.service = service;
        this.exports = exports;
        this.permissions = permissions;
        this.clock = clock;
    }
    @GetMapping("/api/costs/people")
    public Object listCostPeople(@RequestParam Map<String, String> input,
            @RequestHeader(value = "Accept", defaultValue = "application/json") String accept) {
        var query = CostPeopleInput.parse(input, clock);
        boolean download = MediaType.parseMediaTypes(accept).stream()
            .anyMatch(type -> type.getQualityValue() > 0 && XLSX.equals(type.getType() + "/" + type.getSubtype()));
        if (!download) return ApiResponse.ok(service.list(query));
        permissions.can(CostAnalysisActions.PEOPLE_DOWNLOAD);
        var file = exports.export(query);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(XLSX))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.name(), StandardCharsets.UTF_8).build().toString())
            .body(file.bytes());
    }
    @GetMapping("/api/costs/people/{devId}/breakdown")
    public ApiResponse<Breakdown> getCostPersonBreakdown(@PathVariable String devId,
            @RequestParam Map<String, String> input) {
        long id = CostAnalysisInput.id(devId, "devId");
        return ApiResponse.ok(service.breakdown(id, CostPeopleInput.parse(input, clock)));
    }
}
