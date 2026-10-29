package com.sicost.parts;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sicost.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import static com.sicost.parts.PartSettlementModels.*;

@RestController
public class PartSettlementController {
    private final PartSettlementService service;
    private final ObjectReader reader;
    public PartSettlementController(PartSettlementService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @GetMapping("/api/settlements/parts")
    public ApiResponse<?> listPartSettlements(@RequestParam(required = false) String month) {
        return ApiResponse.ok(service.list(month));
    }
    @GetMapping("/api/settlements/parts/{partId}")
    public ApiResponse<?> getPartSettlement(@PathVariable String partId,
            @RequestParam(required = false) String month, @RequestParam(required = false) String view) {
        return ApiResponse.ok(service.get(PartSettlementService.key(partId, month), view));
    }
    @PutMapping("/api/settlements/parts/{partId}/card")
    public ApiResponse<?> updatePartSettlementCard(@PathVariable String partId,
            @RequestParam(required = false) String month, @RequestBody(required = false) String body) {
        Key key = PartSettlementService.key(partId, month);
        unsupported(body);
        service.unavailable(key, "updatePartSettlementCard");
        throw new IllegalStateException("Unavailable operation returned");
    }
    @PostMapping("/api/settlements/parts/{partId}/confirm")
    public ApiResponse<?> confirmPartSettlement(@PathVariable String partId,
            @RequestParam(required = false) String month, @RequestBody(required = false) String body) {
        Key key = PartSettlementService.key(partId, month);
        unsupported(body);
        service.pendingConfirmation(key);
        throw new IllegalStateException("Unavailable operation returned");
    }
    @PostMapping("/api/settlements/parts/{partId}/cancel")
    public ApiResponse<?> cancelPartSettlement(@PathVariable String partId,
            @RequestParam(required = false) String month, @RequestBody(required = false) String body) {
        Key key = PartSettlementService.key(partId, month);
        unsupported(body);
        service.unavailable(key, "cancelPartSettlement");
        throw new IllegalStateException("Unavailable operation returned");
    }
    private void unsupported(String body) {
        if (body == null || body.isBlank()) return;
        try {
            JsonNode node = reader.readTree(body);
            if (node != null && node.isObject() && node.isEmpty()) return;
        } catch (JsonProcessingException ex) {
            throw PartSettlementService.invalid("입력 JSON을 확인하세요.", null);
        }
        throw PartSettlementService.invalid("승인되지 않은 요청 필드는 사용할 수 없습니다. 본문 스키마 승인이 필요합니다.", null);
    }
}
