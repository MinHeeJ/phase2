package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

@WebMvcTest(controllers = UploadErrorContractTest.UploadEndpoint.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, UploadErrorContractTest.UploadEndpoint.class})
class UploadErrorContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean UploadProbe probe;

    interface UploadProbe { void inspect(MultipartFile file); }

    @RestController
    static class UploadEndpoint {
        private final UploadProbe probe;
        UploadEndpoint(UploadProbe probe) { this.probe = probe; }
        @PostMapping("/__test/upload")
        ApiResponse<Void> upload(@RequestPart("file") MultipartFile file) {
            probe.inspect(file);
            return ApiResponse.ok();
        }
    }

    MockMultipartFile file() {
        return new MockMultipartFile("file", "검사.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1});
    }

    @Test void publicMultipartRetainsSuccessEnvelope() throws Exception {
        mvc.perform(multipart("/__test/upload").file(file()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data").doesNotExist());
        verify(probe).inspect(any());
    }

    @Test void missingFileIsFieldErrorBeforeInspection() throws Exception {
        mvc.perform(multipart("/__test/upload"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.field").value("file"));
        verifyNoInteractions(probe);
    }

    @Test void containerSizeRejectionIs413WithSafeFlatEnvelope() throws Exception {
        doThrow(new MaxUploadSizeExceededException(10485760)).when(probe).inspect(any());
        var response = mvc.perform(multipart("/__test/upload").file(file()).header("X-Request-Id", "untrusted"))
            .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.field").value("file")).andExpect(jsonPath("$.fields").doesNotExist())
            .andExpect(jsonPath("$.error").doesNotExist()).andReturn().getResponse();
        String requestId = response.getHeader("X-Request-Id");
        assertThat(requestId).matches("[a-f0-9-]{36}").isNotEqualTo("untrusted");
        assertThat(json.readTree(response.getContentAsByteArray()).get("requestId").asText()).isEqualTo(requestId);
    }

    @Test void malformedMultipartDoesNotLeakInternalFilePath() throws Exception {
        doThrow(new MultipartException("private upload path", new IllegalStateException("private parser details"))).when(probe).inspect(any());
        var response = mvc.perform(multipart("/__test/upload").file(file()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.field").value("file")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("private", "parser details");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNSUPPORTED_FILE_TYPE", "HEADER_NOT_FOUND", "REQUIRED_COLUMN_MISSING", "EMPTY_FILE", "TOO_MANY_ROWS"})
    void fileErrorsPreserveDeclaredCodes(String code) throws Exception {
        doThrow(new ApiException(400, code, "업로드 파일을 확인해 주세요.", "file")).when(probe).inspect(any());
        mvc.perform(multipart("/__test/upload").file(file()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.field").value("file"));
    }

    @Test void expiredValidationRetains410() throws Exception {
        doThrow(new ApiException(410, "VALIDATION_EXPIRED", "파일을 다시 올려 주세요.", null)).when(probe).inspect(any());
        mvc.perform(multipart("/__test/upload").file(file()))
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("VALIDATION_EXPIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUPLICATE_BIZ_NO", "PARTNER_IN_USE", "DUPLICATE_PART_NAME", "PART_IN_USE", "HQ_PART_LOCKED", "CLOSED_MONTH", "SAME_STANDARD", "STALE_VALIDATION"})
    void businessConflictsPreserve409(String code) throws Exception {
        doThrow(ApiException.conflict(code, "입력 내용을 확인해 주세요.", null)).when(probe).inspect(any());
        mvc.perform(multipart("/__test/upload").file(file()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
    }
}
