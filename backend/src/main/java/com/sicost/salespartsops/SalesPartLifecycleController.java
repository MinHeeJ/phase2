package com.sicost.salespartsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.salesparts.SalesPartModels.SalesPartDetail;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.salespartsops.SalesPartLifecycleModels.*;

@RestController
@RequestMapping("/api/sales-parts")
public class SalesPartLifecycleController {
    private final SalesPartLifecycleService service;
    private final SalesPartExportService exports;
    private final ObjectReader reader;
    public SalesPartLifecycleController(SalesPartLifecycleService service,SalesPartExportService exports,ObjectMapper json) {
        this.service=service;this.exports=exports;
        this.reader=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private JsonNode body(String input) {
        try {return reader.readTree(input);}
        catch(JsonProcessingException e) {throw ApiException.badRequest("입력값을 확인하세요.",null);}
    }
    @GetMapping("/options") public ApiResponse<List<SalesPartOption>> listSalesPartOptions() {return ApiResponse.ok(service.options());}
    @PostMapping("/{id}/disable") public ApiResponse<SalesPartDetail> saveSalesPartDisable(@PathVariable String id) {return ApiResponse.ok(service.disable(id));}
    @PostMapping("/{id}/enable") public ApiResponse<SalesPartDetail> saveSalesPartEnable(@PathVariable String id) {return ApiResponse.ok(service.enable(id));}
    @PutMapping("/{id}/name") public ApiResponse<SalesPartDetail> updateSalesPartName(@PathVariable String id,@RequestBody String input) {return ApiResponse.ok(service.rename(id,SalesPartLifecycleInput.name(body(input))));}
    @PostMapping("/{id}/standards") public ApiResponse<SalesPartDetail> saveSalesPartStandard(@PathVariable String id,@RequestBody String input) {return ApiResponse.ok(service.standard(id,SalesPartLifecycleInput.standard(body(input))));}
    @GetMapping("/export") public ResponseEntity<byte[]> getSalesPartExport(@RequestParam(required=false) List<String> columns,@RequestParam(required=false) List<String> ids) {
        var file=exports.export(columns,ids);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+URLEncoder.encode(file.filename(),StandardCharsets.UTF_8).replace("+","%20"))
            .body(file.bytes());
    }
}
