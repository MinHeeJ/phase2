package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sicost.common.api.*;
import java.io.ByteArrayOutputStream;
import java.sql.SQLException;
import java.util.Map;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

@WebMvcTest(controllers = MultipartApiContractTest.MultipartEndpoint.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, MultipartApiContractTest.MultipartEndpoint.class})
class MultipartApiContractTest {
    @Autowired MockMvc mvc;
    @MockBean FileReceiver receiver;

    interface FileReceiver { void receive(MultipartFile file); }
    @RestController
    static class MultipartEndpoint {
        private final FileReceiver receiver;
        MultipartEndpoint(FileReceiver receiver) { this.receiver=receiver; }
        @PostMapping(value="/__test/multipart", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
        ApiResponse<Map<String, Boolean>> receive(@RequestPart("file") MultipartFile file) {
            receiver.receive(file);
            return ApiResponse.ok(Map.of("received", true));
        }
    }
    ResultActions upload() throws Exception {
        try (var workbook=new SXSSFWorkbook(); var output=new ByteArrayOutputStream()) {
            try {
                workbook.createSheet("거래처").createRow(0).createCell(0).setCellValue("사업자번호");
                workbook.write(output);
                return mvc.perform(multipart("/__test/multipart").file(new MockMultipartFile("file", "거래처.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray())));
            } finally { workbook.dispose(); }
        }
    }
    @Test void multipartReachesReceiverWithoutAuthentication() throws Exception {
        upload().andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.received").value(true));
        var file=org.mockito.ArgumentCaptor.forClass(MultipartFile.class);
        verify(receiver).receive(file.capture());
        try (var workbook=new XSSFWorkbook(file.getValue().getInputStream())) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("사업자번호");
        }
    }
    @Test void missingFileUsesFlatFieldErrorWithoutReceivingAnything() throws Exception {
        var result=mvc.perform(multipart("/__test/multipart")).andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.field").value("file"))
            .andExpect(jsonPath("$.message").value("파일을 선택하세요."))
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.error").doesNotExist()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains(result.getResponse().getHeader("X-Request-Id"));
        verifyNoInteractions(receiver);
    }
    @Test void servletSizeLimitUses413AndDoesNotExposeCause() throws Exception {
        doThrow(new MaxUploadSizeExceededException(10L*1024*1024, new IllegalStateException("/private/uploads/original.xlsx")))
            .when(receiver).receive(any());
        var result=upload().andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
            .andExpect(jsonPath("$.field").value("file"))
            .andExpect(jsonPath("$.message").value("파일은 10MB 이하로 올려 주세요."))
            .andExpect(jsonPath("$.success").value(false)).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("/private", "original.xlsx");
        assertThat(result.getResponse().getContentAsString()).contains(result.getResponse().getHeader("X-Request-Id"));
    }
    @Test void malformedMultipartDoesNotExposeParserDetails() throws Exception {
        doThrow(new MultipartException("private parser failure: /private/uploads/original.xlsx")).when(receiver).receive(any());
        var result=upload().andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.field").value("file")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private", "original.xlsx", "parser");
    }
    @ParameterizedTest
    @ValueSource(strings={"UNSUPPORTED_FILE_TYPE", "HEADER_NOT_FOUND", "REQUIRED_COLUMN_MISSING", "EMPTY_FILE", "TOO_MANY_ROWS"})
    void fileValidationCodesAreNotReplacedByGenericErrors(String code) throws Exception {
        doThrow(new ApiException(400, code, "업로드 파일을 확인하세요.", "file")).when(receiver).receive(any());
        upload().andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.field").value("file"));
    }
    @ParameterizedTest
    @ValueSource(strings={"DUPLICATE_BIZ_NO", "PARTNER_IN_USE", "DUPLICATE_PART_NAME", "PART_IN_USE", "HQ_PART_LOCKED", "CLOSED_MONTH", "SAME_STANDARD", "STALE_VALIDATION"})
    void businessConflictsKeepTheirDistinctCodes(String code) throws Exception {
        doThrow(ApiException.conflict(code, "입력 내용을 확인하세요.", null)).when(receiver).receive(any());
        upload().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
    }
    @Test void validationExpiryRemains410() throws Exception {
        doThrow(new ApiException(410, "VALIDATION_EXPIRED", "검사 후 30분이 지났습니다. 다시 올려 주세요.", null)).when(receiver).receive(any());
        upload().andExpect(status().isGone()).andExpect(jsonPath("$.code").value("VALIDATION_EXPIRED"))
            .andExpect(jsonPath("$.message").value("검사 후 30분이 지났습니다. 다시 올려 주세요."));
    }
    @Test void existingUniqueConstraintContractIsPreserved() throws Exception {
        doThrow(new DataIntegrityViolationException("private constraint", new SQLException("private table", "23505"))).when(receiver).receive(any());
        upload().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
    }
    @Test void otherDatabaseErrorsRemainSanitizedServerErrors() throws Exception {
        doThrow(new DataIntegrityViolationException("private constraint", new SQLException("private table", "23503"))).when(receiver).receive(any());
        var result=upload().andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SERVER_ERROR")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private", "23503");
    }
}
