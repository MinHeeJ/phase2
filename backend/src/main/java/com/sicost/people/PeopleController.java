package com.sicost.people;

import com.sicost.common.api.ApiResponse;
import com.sicost.common.validation.CostValues;
import com.sicost.common.validation.BillingValues;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.people.PeopleModels.*;

@RestController
public class PeopleController {
    private final PeopleCostService service;
    private final PeopleExportService export;
    private final Clock clock;
    public PeopleController(PeopleCostService service, PeopleExportService export, Clock clock) {
        this.service = service;
        this.export = export;
        this.clock = clock;
    }
    @GetMapping(value = "/api/costs/people", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<PeopleList> listCostPeople(@RequestParam Map<String,String> parameters) {
        return ApiResponse.ok(service.list(PeopleInput.query(parameters, clock)));
    }
    @GetMapping(value = "/api/costs/people",
        produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> downloadPeople(@RequestParam Map<String,String> parameters) {
        byte[] bytes = export.download(PeopleInput.query(parameters, clock));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("개인원가수익.xlsx", StandardCharsets.UTF_8).build().toString()).body(bytes);
    }
    @GetMapping("/api/costs/people/{devId}/breakdown")
    public ApiResponse<PersonBreakdown> getCostPersonBreakdown(@PathVariable String devId,
            @RequestParam Map<String,String> parameters) {
        Long id = CostValues.optionalId(devId, "devId");
        if (id == null) throw BillingValues.invalid("devId");
        return ApiResponse.ok(service.breakdown(id, PeopleInput.query(parameters, clock)));
    }
}
