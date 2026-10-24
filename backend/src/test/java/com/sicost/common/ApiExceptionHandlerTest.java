package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sicost.common.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

@WebMvcTest(controllers = ApiExceptionHandlerTest.Errors.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ApiExceptionHandlerTest.Errors.class})
class ApiExceptionHandlerTest {
    @Autowired MockMvc mvc;

    @RestController
    static class Errors {
        @GetMapping("/__test/errors/input") void input() {
            throw ApiException.badRequest("구분을 골라 주세요.", "ptnr_se_cd");
        }
        @GetMapping("/__test/errors/missing") void missing() {
            throw ApiException.notFound("거래처가 없습니다.");
        }
        @GetMapping("/__test/errors/conflict/{code}") void conflict(@PathVariable String code) {
            throw ApiException.conflict(code, "업무 조건을 확인해 주세요.", "aply_bgn_ym");
        }
        @GetMapping("/__test/errors/expired") void expired() {
            throw new ApiException(410, "VALIDATION_EXPIRED", "검사 결과가 만료되었습니다. 파일을 다시 올려 주세요.", null);
        }
        @GetMapping("/__test/errors/file/{code}") void fileError(@PathVariable String code) {
            throw new ApiException(400, code, "파일을 확인해 주세요.", "file");
        }
        @PostMapping("/__test/errors/size") void size() {
            throw new MaxUploadSizeExceededException(10L * 1024 * 1024);
        }
        @PostMapping("/__test/errors/multipart") void malformedMultipart() {
            throw new MultipartException("private upload directory /app/data/uploads/internal.xlsx");
        }
        @PostMapping("/__test/errors/upload") ApiResponse<String> upload(@RequestPart("file") MultipartFile file) {
            return ApiResponse.ok(file.getContentType());
        }
    }

    @Test void inputKeepsSingularFieldAndMatchingRequestId() throws Exception {
        var result = mvc.perform(get("/__test/errors/input").header("X-Request-Id", "caller-value"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.field").value("ptnr_se_cd"))
            .andExpect(jsonPath("$.message").value("구분을 골라 주세요."))
            .andExpect(jsonPath("$.fields").doesNotExist())
            .andReturn();
        String id = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(id).matches("[a-f0-9-]{36}").isNotEqualTo("caller-value");
        assertThat(result.getResponse().getContentAsString()).contains(id);
    }

    @Test void missingKeeps404InsteadOfAuthenticationDenial() throws Exception {
        mvc.perform(get("/__test/errors/missing"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUPLICATE_BIZ_NO", "PARTNER_IN_USE", "DUPLICATE_PART_NAME", "PART_IN_USE",
        "HQ_PART_LOCKED", "CLOSED_MONTH", "SAME_STANDARD", "STALE_VALIDATION"})
    void businessConflictsKeepTheirDistinctCodes(String code) throws Exception {
        mvc.perform(get("/__test/errors/conflict/" + code))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.field").value("aply_bgn_ym"));
    }

    @Test void expiredValidationKeeps410AndMessage() throws Exception {
        mvc.perform(get("/__test/errors/expired"))
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("VALIDATION_EXPIRED"))
            .andExpect(jsonPath("$.message").value("검사 결과가 만료되었습니다. 파일을 다시 올려 주세요."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNSUPPORTED_FILE_TYPE", "HEADER_NOT_FOUND", "REQUIRED_COLUMN_MISSING", "EMPTY_FILE", "TOO_MANY_ROWS"})
    void fileLevelFailuresRemain400(String code) throws Exception {
        mvc.perform(get("/__test/errors/file/" + code))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.field").value("file"));
    }

    @Test void multipartContainerLimitIs413JsonWithFileField() throws Exception {
        var result = mvc.perform(post("/__test/errors/size"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.field").value("file"))
            .andExpect(jsonPath("$.message").value("파일은 10MB 이하만 올릴 수 있습니다."))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains(result.getResponse().getHeader(RequestIdFilter.HEADER));
    }

    @Test void missingFilePartIs400Not500() throws Exception {
        mvc.perform(multipart("/__test/errors/upload"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.field").value("file"));
    }

    @Test void malformedMultipartDoesNotExposeServerPaths() throws Exception {
        var result = mvc.perform(post("/__test/errors/multipart"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file"))
            .andExpect(jsonPath("$.message").value("올바른 파일 요청을 보내 주세요."))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("/app/data", "internal.xlsx", "private");
    }

    @Test void validMultipartRemainsPublicAndUsesExistingEnvelope() throws Exception {
        var file = new MockMultipartFile("file", "거래처.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1});
        mvc.perform(multipart("/__test/errors/upload").file(file))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data").value(file.getContentType()));
    }
}
