package com.sicost.candidatesops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.candidatesops.CandidateContactModels.*;

@RestController
public class CandidateContactController {
    private final CandidateContactService contacts;
    private final CandidateShortageService shortages;
    private final ObjectReader reader;
    public CandidateContactController(CandidateContactService contacts, CandidateShortageService shortages, ObjectMapper json) {
        this.contacts=contacts;this.shortages=shortages;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private JsonNode body(String body) {
        try{return reader.readTree(body);}catch(JsonProcessingException e){throw CandidateContactInput.invalid(null);}
    }
    @PostMapping("/api/candidates/{id}/contacts")
    public ApiResponse<CandidateDetail> createCandidateContact(@PathVariable long id,@RequestBody String body) {
        return ApiResponse.ok(contacts.contact(id,body(body)));
    }
    @PostMapping("/api/candidates/{id}/convert")
    public ApiResponse<ConversionResult> createCandidateConversion(@PathVariable long id,@RequestBody String body) {
        return ApiResponse.ok(contacts.convert(id,body(body)));
    }
    @GetMapping("/api/candidates/shortages")
    public ApiResponse<ShortagePage> getCandidateShortages(@RequestParam Map<String,String> query) {
        return ApiResponse.ok(shortages.shortages(query));
    }
}
