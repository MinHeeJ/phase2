package com.sicost.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.sicost.admin.*;
import com.sicost.apiops.*;
import com.sicost.developers.*;
import com.sicost.developersops.*;
import com.sicost.workstatus.*;
import com.sicost.common.health.HealthController;
import com.sicost.common.security.*;
import com.sicost.partners.*;
import com.sicost.partnersops.*;
import com.sicost.salesparts.*;
import com.sicost.salespartsops.*;
import com.sicost.shared.*;
import com.sicost.uploadsops.*;
import com.sicost.projects.*;
import com.sicost.projectsops.*;
import com.sicost.partchanges.*;
import com.sicost.contractchanges.*;
import com.sicost.allocations.*;
import com.sicost.billstages.*;
import com.sicost.expenses.*;
import com.sicost.staffplans.*;
import com.sicost.memos.*;
import com.sicost.phases.*;
import com.sicost.issues.*;
import com.sicost.issuesops.*;
import com.sicost.projectboardops.*;
import com.sicost.assignments.*;
import com.sicost.assignmentsops.*;
import com.sicost.changes.*;
import com.sicost.months.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

@WebMvcTest(controllers={AdminController.class, SharedReadController.class, HealthController.class,
    PartnerController.class, PartnerLifecycleController.class, SalesPartController.class,
    SalesPartLifecycleController.class, UploadController.class, DeveloperController.class,
    DeveloperLifecycleController.class, WorkStatusController.class, UploadResultController.class,
    ProjectController.class, ProjectLifecycleController.class, PartChangeController.class,
    ProjectContractChangeController.class, ProjectAllocationController.class,
    ProjectBillStageController.class, ProjectExpenseController.class, ProjectStaffPlanController.class,
    ProjectMemoController.class, ProjectPhaseController.class, ProjectProgressController.class,
    IssueController.class, IssueOwnerController.class, ProjectBoardController.class,
    ProjectBoardExportController.class, AssignmentController.class, AssignmentOperationController.class,
    AssignmentChangeController.class, AssignmentMonthsController.class, ProjectAssignmentMonthController.class,
    com.sicost.billings.BillingController.class, com.sicost.billingsops.BillingController.class,
    com.sicost.receipts.BillingReceiptController.class,
    com.sicost.dispatchattendance.DispatchAttendanceController.class,
    com.sicost.dispatchattendance2.DispatchAttendanceUploadController.class})
@Import(CurrentUser.class)
class OpenApiHandlerContractTest {
    @Autowired RequestMappingHandlerMapping handlers;
    @MockBean java.time.Clock clock;
    @MockBean AdminService admin;
    @MockBean SharedReadService shared;
    @MockBean PartnerService partners;
    @MockBean PartnerLifecycleService partnerLifecycle;
    @MockBean SalesPartService salesParts;
    @MockBean SalesPartLifecycleService salesPartLifecycle;
    @MockBean SalesPartExportService exports;
    @MockBean UploadService uploads;
    @MockBean PermissionService permissions;
    @MockBean DeveloperService developers;
    @MockBean DeveloperLifecycleService developerLifecycle;
    @MockBean WorkStatusService workStatuses;
    @MockBean UploadResultService uploadResults;
    @MockBean ProjectService projects;
    @MockBean ProjectLifecycleService projectLifecycle;
    @MockBean PartChangeService partChanges;
    @MockBean ProjectContractChangeService contractChanges;
    @MockBean ProjectAllocationService allocations;
    @MockBean ProjectBillStageService billStages;
    @MockBean ProjectExpenseService expenses;
    @MockBean ProjectStaffPlanService staffPlans;
    @MockBean ProjectMemoService memos;
    @MockBean ProjectPhaseService phases;
    @MockBean ProjectProgressService progress;
    @MockBean IssueService issues;
    @MockBean IssueOwnerService issueOwners;
    @MockBean ProjectBoardService board;
    @MockBean ProgressParameterService parameters;
    @MockBean ProjectBoardExportService boardExports;
    @MockBean AssignmentService assignments;
    @MockBean AssignmentOperationService assignmentOperations;
    @MockBean AssignmentExportService assignmentExports;
    @MockBean AssignmentChangeService assignmentChanges;
    @MockBean AssignmentMonthsService assignmentMonths;
    @MockBean ProjectAssignmentMonthService projectAssignmentMonths;
    @MockBean com.sicost.billings.BillingService billings;
    @MockBean com.sicost.billingsops.BillingAmountService billingAmounts;
    @MockBean com.sicost.billingsops.BillingGapService billingGaps;
    @MockBean com.sicost.billingsops.BillingReceivableService billingReceivables;
    @MockBean com.sicost.billingsops.BillingRevenueQuery billingRevenue;
    @MockBean com.sicost.billingsops.BillingCommandService billingCommands;
    @MockBean com.sicost.receipts.BillingReceiptService billingReceipts;
    @MockBean com.sicost.dispatchattendance.DispatchAttendanceService attendance;
    @MockBean com.sicost.dispatchattendance2.DispatchAttendanceUploadService attendanceUploads;

    @Test void everyDurableOperationHasOneRegisteredLiteralHandler() throws Exception {
        Map<?,?> contract;
        try(var input=new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract=new Yaml().load(input);
        }
        Set<String> expected=new TreeSet<>();
        Map<?,?> paths=(Map<?,?>)contract.get("paths");
        assertThat(paths).isNotEmpty();
        paths.forEach((path,item)->((Map<?,?>)item).forEach((method,definition)->{
            if(Set.of("get","post","put","patch","delete").contains(method.toString())) {
                var operation=(Map<?,?>)definition;
                assertThat(operation.get("operationId")).isInstanceOf(String.class);
                assertThat(operation.get("responses")).isInstanceOf(Map.class);
                expected.add(method.toString().toUpperCase(Locale.ROOT)+" "+normalize(path.toString()));
            }
        }));
        Map<String,String> actual=new TreeMap<>();
        handlers.getHandlerMethods().forEach((mapping,handler)->{
            if(mapping.getPatternValues().stream().anyMatch(path->path.startsWith("/api/"))) {
                assertThat(mapping.getPatternValues()).as("one path per operation").hasSize(1);
                assertThat(mapping.getMethodsCondition().getMethods()).as("one method per operation").hasSize(1);
                String path=mapping.getPatternValues().iterator().next();
                String method=mapping.getMethodsCondition().getMethods().iterator().next().name();
                assertThat(actual.put(method+" "+normalize(path),handler.getMethod().getName())).isNull();
            }
        });
        // Existing phases expose additional APIs; they must not hide a missing contract operation.
        assertThat(actual.keySet()).containsAll(expected);
    }
    private static String normalize(String path) { return path.replaceAll("\\{[^/{}]+}", "{}"); }
}
