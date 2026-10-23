package com.sicost.salespartsops;

import com.sicost.common.api.ApiResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.salespartsops.SalesPartModels.*;

@RestController
@RequestMapping("/api/sales-parts")
public class SalesPartManagementController {
    private final SalesPartManagementService service;
    private final SalesPartExcelService excel;
    public SalesPartManagementController(SalesPartManagementService service, SalesPartExcelService excel) { this.service=service;this.excel=excel; }
    @GetMapping("/options")
    public ApiResponse<List<Option>> listSalesPartOptions() { return ApiResponse.ok(service.options()); }
    @PostMapping("/{id}/disable")
    public ApiResponse<Detail> saveSalesPartDisable(@PathVariable String id) { return ApiResponse.ok(service.disable(id)); }
    @PostMapping("/{id}/enable")
    public ApiResponse<Detail> saveSalesPartEnable(@PathVariable String id) { return ApiResponse.ok(service.enable(id)); }
    @PostMapping("/{id}/standards")
    public ApiResponse<Detail> saveSalesPartStandard(@PathVariable String id,@RequestBody String body) { return ApiResponse.ok(service.saveStandard(id,body)); }
    @PutMapping("/{id}/name")
    public ApiResponse<Detail> updateSalesPartName(@PathVariable String id,@RequestBody String body) { return ApiResponse.ok(service.rename(id,body)); }
    @GetMapping("/export")
    public ResponseEntity<byte[]> getSalesPartExport(@RequestParam(required=false) List<String> columns,@RequestParam(required=false) List<String> ids,@RequestParam(required=false) String sort) {
        Download file=excel.export(columns,ids,sort);
        String encoded=URLEncoder.encode(file.filename(),StandardCharsets.UTF_8).replace("+","%20");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+encoded)
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(file.bytes());
    }
}
