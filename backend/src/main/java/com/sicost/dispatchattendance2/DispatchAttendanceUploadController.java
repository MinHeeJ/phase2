package com.sicost.dispatchattendance2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.security.BillingActions;
import com.sicost.common.security.PermissionService;
import com.sicost.common.validation.BillingValues;
import com.sicost.uploadsops.PersonnelUploadModels.Result;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

@RestController
@RequestMapping("/api/uploads/dispatch-attendance")
public class DispatchAttendanceUploadController {
    private final DispatchAttendanceUploadService service;
    private final PermissionService permissions;
    private final ObjectReader reader;
    public DispatchAttendanceUploadController(DispatchAttendanceUploadService service,
            PermissionService permissions, ObjectMapper json) {
        this.service = service; this.permissions = permissions;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @PostMapping(value = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Result> validateDispatchAttendanceUpload(@RequestParam String month,
            @RequestParam long projectId, @RequestPart MultipartFile file) {
        BillingActions.can(permissions, "validateDispatchAttendanceUpload");
        return ApiResponse.ok(service.validate(new Scope(projectId, month), file));
    }
    @PostMapping("/apply")
    public ApiResponse<Applied> applyDispatchAttendanceUpload(@RequestBody String body) {
        BillingActions.can(permissions, "applyDispatchAttendanceUpload");
        try {
            JsonNode input = reader.readTree(body);
            if (!input.isObject() || input.size() != 2 || !input.path("upload_id").isIntegralNumber()
                    || !input.path("upload_id").canConvertToLong() || input.path("upload_id").asLong() < 1) {
                throw BillingValues.invalid("upload_id");
            }
            if (!input.path("confirm").isBoolean() || !input.path("confirm").booleanValue()) {
                throw BillingValues.invalid("confirm");
            }
            return ApiResponse.ok(service.apply(new ApplyInput(input.path("upload_id").longValue(), true)));
        } catch (JsonProcessingException ex) { throw BillingValues.invalid(null); }
    }
}
