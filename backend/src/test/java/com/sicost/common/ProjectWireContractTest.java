package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

class ProjectWireContractTest {
    @Test void costServiceAdaptersHaveDistinctBeansAndShareTheBoardCalculation() {
        var allocation = mock(com.sicost.common.costallocation.CostAllocationService.class);
        when(allocation.projectTotal(1, "2026-10")).thenReturn(
            new com.sicost.common.costallocation.CostAllocationModels.ProjectTotal(123, List.of("2026-10")));
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(com.sicost.common.costallocation.CostAllocationService.class, () -> allocation);
            context.register(com.sicost.apiops.ProjectCostService.class, com.sicost.projectcost.ProjectCostService.class);
            context.refresh();
            assertThat(context.getBean(com.sicost.apiops.ProjectCostService.class)).isNotNull();
            var exportCosts = context.getBean(com.sicost.projectcost.ProjectCostService.class);
            var result = exportCosts.calculate(1, java.time.YearMonth.of(2026, 10));
            assertThat(result.cumulativeCostAmount()).isEqualTo(123);
            assertThat(result.missingSalaryMonths()).containsExactly("2026-10");
            verify(allocation).projectTotal(1, "2026-10");
            verifyNoMoreInteractions(allocation);
        }
    }

    @Test void pagingDefaultsAreZeroBasedWithApprovedOrder() {
        var paging = ProjectPaging.defaults();
        assertThat(paging.page()).isZero();
        assertThat(paging.size()).isEqualTo(50);
        assertThat(paging.offset()).isZero();
        assertThat(paging.sort()).isEqualTo("cntrct_bgn_dt,desc");
        assertThat(paging.order()).isEqualTo("\"cntrct_bgn_dt\" desc NULLS LAST, prj_id ASC");
    }
    @ParameterizedTest @ValueSource(ints = {20, 50, 100})
    void pagingSupportsEveryApprovedSize(int size) {
        assertThat(new ProjectPaging(2, size, "custName,asc").offset()).isEqualTo(2L * size);
        assertThat(new ProjectPaging(2, size, "custName,asc").order())
            .isEqualTo("\"custName\" asc NULLS LAST, prj_id ASC");
    }
    @Test void negativePageIsRejected() { invalidPaging(-1, 50, null, "page"); }
    @Test void pageOverflowIsRejected() { invalidPaging(Integer.MAX_VALUE, 50, null, "page"); }
    @Test void unsupportedSizeIsRejected() { invalidPaging(0, 30, null, "size"); }
    @ParameterizedTest @ValueSource(strings = {"prj_no;DROP TABLE prj.prj,asc", "unknown,asc", "prj_nm,DESC", "prj_nm,asc,prj_id", "custname,asc"})
    void sortingRejectsUnlistedIdentifiersAndDirections(String sort) { invalidPaging(0, 50, sort, "sort"); }
    @ParameterizedTest @ValueSource(strings = {"prj_no", "prj_nm", "custName", "cntrct_ty_cd", "cntrct_bgn_dt", "cur_end_dt", "cur_cntrct_amt", "assignmentCount", "partName", "prj_stts_cd"})
    void everyDocumentedColumnCanSortBeforePagination(String column) {
        for (var direction : List.of("asc", "desc")) {
            assertThat(new ProjectPaging(0, 50, column + "," + direction).order())
                .isEqualTo("\"" + column + "\" " + direction + " NULLS LAST, prj_id ASC");
        }
    }
    private void invalidPaging(int page, int size, String sort, String field) {
        assertThatThrownBy(() -> new ProjectPaging(page, size, sort))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(400);
                assertThat(error.field()).isEqualTo(field);
            });
    }

    @Test void workbookArtifactUsesExactJsonFieldsAndExistingExcelWriter() throws Exception {
        var bytes = new ExcelExportService().create("연도=2026", new CurrentUser().name(),
            LocalDateTime.of(2026, 10, 23, 0, 0),
            List.of(new ExportColumn<String>("prj_nm", "프로젝트명", false, false, name -> name)),
            List.of("=프로젝트"));
        var artifact = DownloadArtifact.workbook("프로젝트.xlsx", bytes);
        var json = JsonMapper.builder().build();
        var tree = json.readTree(json.writeValueAsString(ApiResponse.ok(artifact)));
        assertThat(tree.get("success").booleanValue()).isTrue();
        var data = tree.get("data");
        var names = new ArrayList<String>();
        data.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("file_name", "media_type", "content_base64");
        assertThat(data.get("file_name").asText()).isEqualTo("프로젝트.xlsx");
        assertThat(data.get("media_type").asText()).isEqualTo(DownloadArtifact.XLSX);
        byte[] decoded = Base64.getDecoder().decode(data.get("content_base64").asText());
        assertThat(decoded).isEqualTo(bytes);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(decoded))) {
            var cell = workbook.getSheetAt(0).getRow(4).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).isEqualTo("=프로젝트");
        }
    }

    @Test void scopedOperationBindingsMatchDurableContractAndRemainAnonymous() throws Exception {
        Map<?, ?> contract;
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract = new Yaml().load(input);
        }
        var permissions = new PermissionService();
        var paths = (Map<?, ?>) contract.get("paths");
        var checked = new HashSet<String>();
        for (var pathEntry : paths.entrySet()) {
            String path = pathEntry.getKey().toString();
            if (!path.startsWith("/api/projects") && !path.startsWith("/api/uploads")) continue;
            var methods = (Map<?, ?>) pathEntry.getValue();
            for (String method : List.of("get", "post", "put", "delete")) {
                if (!(methods.get(method) instanceof Map<?, ?> operation)) continue;
                String id = operation.get("operationId").toString();
                // Project URLs can expose assignment-owned operations (UI-12).
                boolean assignment = "getProjectAssignmentMonths".equals(id);
                String key = assignment ? AssignmentActions.key(id) : ProjectActions.key(id);
                assertThat((List<?>) operation.get("security")).isNullOrEmpty();
                assertThat(operation.get("x-roles")).isNull();
                assertThat(PermissionService.AUTH_COLUMNS).containsKey(key.substring(key.indexOf('.') + 1));
                assertThat(assignment ? AssignmentActions.can(permissions, id)
                    : ProjectActions.can(permissions, id, false)).isTrue();
                assertThat(key).startsWith(assignment ? "UI-12." : "UI-01.");
                checked.add(id);
            }
        }
        assertThat(checked).contains("createUploadValidation", "getUploadResult", "listUploadIssues",
            "listUploadRows", "createUploadRevalidation", "saveUploadApplication", "getUploadTemplate", "getUploadErrorFile",
            "getProjectAssignmentMonths");
        assertThat(new CurrentUser().userId()).isNull();
        assertThat(new CurrentUser().name()).isEqualTo("기본 사용자");
    }

    @ParameterizedTest @ValueSource(strings = {"createProjectContractChange", "updateProjectContractChange"})
    void confirmPayloadBindsBothUpdateAndConfirm(String operation) {
        var permissions = spy(new PermissionService());
        assertThat(ProjectActions.can(permissions, operation, true)).isTrue();
        verify(permissions).can("UI-01.update");
        verify(permissions).can("UI-01.confirm");
    }
    @Test void lifecycleActionsBindDeleteRatherThanUpdate() {
        assertThat(ProjectActions.key("updateProjectDisable")).isEqualTo("UI-01.delete");
        assertThat(ProjectActions.key("updateProjectEnable")).isEqualTo("UI-01.delete");
    }
    @Test void projectReadCreateConfirmAndDownloadBindTheirOwnActions() {
        assertThat(ProjectActions.key("listProjects")).isEqualTo("UI-01.read");
        assertThat(ProjectActions.key("listProjectOptions")).isEqualTo("UI-01.read");
        assertThat(ProjectActions.key("createProject")).isEqualTo("UI-01.create");
        assertThat(ProjectActions.key("updateProjectContractChangeConfirmation")).isEqualTo("UI-01.confirm");
        assertThat(ProjectActions.key("getProjectExport")).isEqualTo("UI-01.download");
    }

    @ParameterizedTest
    @CsvSource({
        "getProjectProgress,UI-01.read",
        "updateProjectPhase,UI-01.update",
        "updateProjectPhaseCompletion,UI-01.update",
        "updateProjectPhaseUncompletion,UI-01.update",
        "saveProjectMemo,UI-01.update",
        "updateProjectMemo,UI-01.update",
        "listProjectIssues,UI-01.read",
        "createIssue,UI-01.update",
        "updateIssue,UI-01.update",
        "listIssues,UI-31.read",
        "getProjectBoard,UI-31.read",
        "getProjectBoardExport,UI-31.download",
        "getIssueOwnerOptions,UI-01.read",
        "getParams,UI-01.read"
    })
    void progressAndBoardOperationsBindKeysWithoutAuthentication(String operation, String expected) {
        var permissions = spy(new PermissionService());
        assertThat(ProjectActions.key(operation)).isEqualTo(expected);
        assertThat(ProjectActions.can(permissions, operation, false)).isTrue();
        verify(permissions).can(expected);
        verifyNoMoreInteractions(permissions);
    }

    @Test void sharedIssueCreationKeepsDetailAndBoardOriginsSeparateAndAllowed() {
        var permissions = spy(new PermissionService());
        assertThat(ProjectActions.key("createIssue")).isEqualTo("UI-01.update");
        assertThat(ProjectActions.key("createIssue", "UI-01")).isEqualTo("UI-01.update");
        String boardKey = ProjectActions.key("createIssue", "UI-31");
        assertThat(boardKey).isEqualTo("UI-31.create");
        assertThat(permissions.can(boardKey)).isTrue();
        verify(permissions).can("UI-31.create");
        verifyNoMoreInteractions(permissions);
    }

    @ParameterizedTest
    @CsvSource({"getProjectBoard,UI-31", "listIssues,UI-31", "getProjectBoardExport,UI-31",
        "getProjectProgress,UI-01", "saveProjectMemo,UI-01", "updateIssue,UI-01"})
    void explicitOriginKeepsCanonicalOperationBinding(String operation, String screen) {
        assertThat(ProjectActions.key(operation, screen)).isEqualTo(ProjectActions.key(operation));
    }

    @ParameterizedTest
    @CsvSource({"createIssue,UI-04", "getProjectProgress,UI-31", "getProjectBoard,UI-01",
        "updateIssue,UI-31", "saveProjectMemo,UI-31"})
    void originCannotReclassifyUnrelatedOperations(String operation, String screen) {
        assertThatIllegalArgumentException().isThrownBy(() -> ProjectActions.key(operation, screen));
    }

    @ParameterizedTest @ValueSource(strings = {"unknown", "createProjectNotification", "saveProjectCostAllocation"})
    void unregisteredOperationsDoNotGainPermissionBindings(String operation) {
        var permissions = spy(new PermissionService());
        assertThatIllegalArgumentException().isThrownBy(() -> ProjectActions.can(permissions, operation, false));
        verifyNoInteractions(permissions);
    }
}
