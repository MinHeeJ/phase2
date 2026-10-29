package com.sicost.dispatchattendance2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.validation.BillingValues;
import com.sicost.uploadsops.PersonnelUploadModels.Result;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

@RestController
@RequestMapping("/api/uploads/dispatch-attendance")
public class DispatchAttendanceUploadController {
    private final DispatchAttendanceUploadService service;
    private final ObjectReader reader;
    public DispatchAttendanceUploadController(DispatchAttendanceUploadService service, ObjectMapper json) {
        this.service=service;
        this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @PostMapping(value="/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Result> validateDispatchAttendanceUpload(@RequestPart MultipartFile file,
            @RequestParam String month, @RequestParam long projectId) {
        return ApiResponse.ok(service.validate(month,projectId,file));
    }
    @PostMapping("/apply")
    public ApiResponse<ApplyResult> applyDispatchAttendanceUpload(@RequestBody String body) {
        try {
            JsonNode input=reader.readTree(body);
            if (input==null || !input.isObject()) throw BillingValues.invalid("upload_id");
            input.fieldNames().forEachRemaining(key->{
                if (!Set.of("upload_id","confirm").contains(key)) throw BillingValues.invalid(key);
            });
            JsonNode id=input.get("upload_id"),confirm=input.get("confirm");
            if (id==null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue()<1)
                throw BillingValues.invalid("upload_id");
            if (confirm==null || !confirm.isBoolean() || !confirm.booleanValue()) throw BillingValues.invalid("confirm");
            return ApiResponse.ok(service.apply(id.longValue()));
        } catch (JsonProcessingException e) { throw BillingValues.invalid("upload_id"); }
    }
}
