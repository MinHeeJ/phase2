package com.sicost.issuesops;

import static com.sicost.issuesops.IssueOwnerModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = IssueOwnerController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, IssueOwnerService.class,
    PermissionService.class, IssueOwnerApiTest.Time.class})
class IssueOwnerApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneOffset.UTC); }
    }
    @Autowired MockMvc mvc;
    @MockBean IssueOwnerMapper mapper;
    @BeforeEach void setup() { when(mapper.projectExists(37)).thenReturn(true); }

    @Test void anonymousOptionsExposeNamesOnlyAndUseSeoulDate() throws Exception {
        when(mapper.options(any())).thenReturn(List.of(new IssueOwnerOption("김담당"), new IssueOwnerOption("박피엠")));
        mvc.perform(get("/api/issues/owner-options").param("projectId", "37"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].name").value("김담당"))
            .andExpect(jsonPath("$.data[0].userId").doesNotExist());
        verify(mapper).options(new IssueOwnerQuery(37, LocalDate.of(2026,10,23)));
    }
    @Test void existingProjectWithoutOwnersHasEmptyArray() throws Exception {
        when(mapper.options(any())).thenReturn(List.of());
        mvc.perform(get("/api/issues/owner-options").param("projectId", "37"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void missingProjectParameterIsBadRequest() throws Exception {
        mvc.perform(get("/api/issues/owner-options")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("projectId")).andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings={"", "abc", "1.5", "9223372036854775808", "0", "-1"})
    void invalidProjectParameterIsBadRequest(String value) throws Exception {
        mvc.perform(get("/api/issues/owner-options").param("projectId",value))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("projectId")).andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(mapper);
    }
    @Test void absentProjectIsNotFoundNotAnEmptySuccess() throws Exception {
        mvc.perform(get("/api/issues/owner-options").param("projectId","99"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(mapper,never()).options(any());
    }
}
