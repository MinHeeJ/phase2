package com.sicost.dispatchattendance2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.security.BillingActions;
import com.sicost.common.security.PermissionService;
import com.sicost.common.validation.BillingRules;
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
        this.service=service; this.permissions=permissions;
        this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @PostMapping(value="/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<com.sicost.uploadsops.PersonnelUploadModels.Result> validateDispatchAttendanceUpload(
            @RequestPart MultipartFile file, @RequestParam String month, @RequestParam long projectId) {
        BillingActions.can(permissions,"validateDispatchAttendanceUpload");
        return ApiResponse.ok(service.validate(file,month,projectId));
    }
    @PostMapping("/apply")
    public ApiResponse<Application> applyDispatchAttendanceUpload(@RequestBody String body) {
        BillingActions.can(permissions,"applyDispatchAttendanceUpload");
        try {
            JsonNode node=reader.readTree(body);
            if (node==null || !node.isObject()) throw BillingRules.invalid(null);
            node.fieldNames().forEachRemaining(key -> {
                if (!key.equals("upload_id") && !key.equals("confirm")) throw BillingRules.invalid(key);
            });
            long id=BillingRules.won(node.get("upload_id"),"upload_id");
            if (id<1) throw BillingRules.invalid("upload_id");
            if (!node.path("confirm").isBoolean() || !node.path("confirm").booleanValue()) throw BillingRules.invalid("confirm");
            return ApiResponse.ok(service.apply(new Apply(id,true)));
        } catch (JsonProcessingException ex) { throw BillingRules.invalid(null); }
    }
}
