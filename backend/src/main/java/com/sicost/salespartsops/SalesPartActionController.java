package com.sicost.salespartsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.sicost.salespartsops.SalesPartActionModels.*;

@RestController
@RequestMapping("/api/sales-parts")
public class SalesPartActionController {
    private final SalesPartActionService service;
    private final ObjectReader reader;
    public SalesPartActionController(SalesPartActionService service,ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private JsonNode body(String input) {
        try {return reader.readTree(input);} catch(JsonProcessingException e) {throw ApiException.badRequest("입력값을 확인하세요.",null);}
    }
    @GetMapping("/options")
    public ApiResponse<List<Option>> listSalesPartOptions() {return ApiResponse.ok(service.options());}
    @PostMapping("/{id}/disable")
    public ApiResponse<Detail> saveSalesPartDisable(@PathVariable String id) {return ApiResponse.ok(service.disable(id));}
    @PostMapping("/{id}/enable")
    public ApiResponse<Detail> saveSalesPartEnable(@PathVariable String id) {return ApiResponse.ok(service.enable(id));}
    @PostMapping("/{id}/standards")
    public ApiResponse<Detail> saveSalesPartStandard(@PathVariable String id,@RequestBody String input) {return ApiResponse.ok(service.saveStandard(id,body(input)));}
    @PutMapping("/{id}/name")
    public ApiResponse<Detail> updateSalesPartName(@PathVariable String id,@RequestBody String input) {return ApiResponse.ok(service.rename(id,body(input)));}
    @GetMapping("/export")
    public ResponseEntity<byte[]> getSalesPartExport(@RequestParam MultiValueMap<String,String> query) {
        File file=service.export(query.get("columns"),query.get("ids"));
        String filename=URLEncoder.encode(file.filename(),StandardCharsets.UTF_8).replace("+","%20");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+filename).body(file.bytes());
    }
}
