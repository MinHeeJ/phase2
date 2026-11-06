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
    com.sicost.dispatchattendance2.DispatchAttendanceUploadController.class,
    com.sicost.costallocation.CostAllocationController.class,
    com.sicost.people.PeopleController.class, CostProjectController.class,
    com.sicost.parts.PartSettlementController.class,
    com.sicost.costsops.RenegotiationController.class,
    com.sicost.billingsops.BillingExportController.class,
    com.sicost.entries.FinanceEntryController.class,
    com.sicost.cashflow.CashFlowController.class,
    com.sicost.businessplan.BusinessPlanController.class,
    com.sicost.businessplan2.BusinessPlanUploadController.class,
    com.sicost.financeentries.FinanceEntriesUploadController.class,
    com.sicost.priorprofit.PriorProfitUploadController.class,
    com.sicost.paymentorders.PaymentOrderController.class,
    com.sicost.payments.FinancePaymentController.class,
    com.sicost.tax.CorporateTaxController.class,
    com.sicost.profit.MonthlyProfitController.class,
    com.sicost.us4.PlanComparisonController.class,
    com.sicost.us5.MonthlyShortageController.class,
    ClosingQueryController.class, com.sicost.closingops.ClosingController.class,
    com.sicost.adjustments.AdjustmentController.class,
    com.sicost.opportunities.OpportunityController.class,
    com.sicost.opportunitiesops.OpportunityLifecycleController.class,
    com.sicost.tempassignments.TempAssignmentController.class,
    com.sicost.withdrawalsops.NextSlotController.class,
    com.sicost.workforceops.WorkforceDemandController.class,
    com.sicost.board.WorkforceBoardController.class,
    com.sicost.candidates.CandidateController.class,
    com.sicost.candidatesops.CandidateContactController.class,
    com.sicost.authentication.AuthenticationController.class,
    com.sicost.useraccounts.UserAccountsController.class,
    com.sicost.rolepermissions.RolePermissionsController.class,
    com.sicost.menumanagement.MenuManagementController.class,
    com.sicost.menumanagement.NavigationController.class,
    com.sicost.notifications.NotificationController.class,
    com.sicost.businesscontacts.BusinessContactController.class,
    DashboardController.class, com.sicost.dashboardops.DashboardExportController.class,
    com.sicost.aiops.RecommendationOptionsController.class,
    com.sicost.staffrecommendations.StaffRecommendationController.class,
    com.sicost.analysis.AnalysisController.class})
@Import(CurrentUser.class)
class OpenApiHandlerContractTest {
    @Autowired RequestMappingHandlerMapping handlers;
    @MockBean java.time.Clock clock;
    @MockBean DashboardQueryService dashboardQuery;
    @MockBean com.sicost.dashboardops.DashboardExportService dashboardExport;
    @MockBean com.sicost.aiops.RecommendationOptionsService recommendationOptions;
    @MockBean com.sicost.staffrecommendations.StaffRecommendationService recommendations;
    @MockBean com.sicost.staffrecommendations.RecommendationCriteriaService recommendationCriteria;
    @MockBean com.sicost.staffrecommendations.CareerCardService careerCards;
    @MockBean com.sicost.analysis.AnalysisRouter analysisRouter;
    @MockBean com.sicost.analysis.ConversationService conversations;
    @MockBean com.sicost.analysis.AnalysisExportService analysisExport;
    @MockBean com.sicost.common.ai.AiAccessGuard aiAccess;
    @MockBean com.sicost.analysis.AnalysisModelClient analysisModel;
    @MockBean com.sicost.authentication.AuthenticationService authentication;
    @MockBean com.sicost.useraccounts.ManagementService management;
    @MockBean com.sicost.notifications.NotificationService notifications;
    @MockBean com.sicost.businesscontacts.BusinessContactService businessContacts;
    @MockBean AdminService admin;
    @MockBean SharedReadService shared;
    @MockBean com.sicost.menumanagement.NavigationService navigation;
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
    @MockBean com.sicost.dispatchattendance.DispatchAttendanceService dispatchAttendance;
    @MockBean com.sicost.dispatchattendance2.DispatchAttendanceUploadService dispatchAttendanceUploads;
    @MockBean com.sicost.costallocation.CostAllocationService costAllocations;
    @MockBean com.sicost.people.PeopleCostService costPeople;
    @MockBean com.sicost.people.PeopleExportService costPeopleExports;
    @MockBean CostProjectService costProjects;
    @MockBean CostProjectExportService costProjectExports;
    @MockBean com.sicost.parts.PartSettlementService partSettlements;
    @MockBean com.sicost.parts.PartSettlementExportService partSettlementExports;
    @MockBean com.sicost.costsops.RenegotiationService renegotiations;
    @MockBean com.sicost.costsops.RenegotiationExportService renegotiationExports;
    @MockBean com.sicost.common.excel.ExcelExportService excel;
    @MockBean com.sicost.billingsops.BillingMapper billingMapper;
    @MockBean com.sicost.entries.FinanceEntryService financeEntries;
    @MockBean com.sicost.cashflow.CashFlowService cashFlow;
    @MockBean com.sicost.businessplan.BusinessPlanService businessPlan;
    @MockBean com.sicost.businessplan.BusinessPlanComparisonService businessPlanComparison;
    @MockBean com.sicost.businessplan2.BusinessPlanUploadService businessPlanUploads;
    @MockBean com.sicost.financeentries.FinanceEntriesUploadService financeEntryUploads;
    @MockBean com.sicost.priorprofit.PriorProfitUploadService priorProfitUploads;
    @MockBean com.sicost.paymentorders.PaymentOrderService paymentOrders;
    @MockBean com.sicost.payments.FinancePaymentService financePayments;
    @MockBean com.sicost.tax.CorporateTaxService corporateTax;
    @MockBean com.sicost.profit.MonthlyProfitService monthlyProfit;
    @MockBean com.sicost.us4.PlanComparisonService planComparison;
    @MockBean com.sicost.us4.PlanComparisonExportService planComparisonExports;
    @MockBean com.sicost.us5.MonthlyShortageService monthlyShortages;
    @MockBean com.sicost.us5.MonthlyShortageExportService monthlyShortageExports;
    @MockBean ClosingQueryService closingQuery;
    @MockBean com.sicost.closingops.ClosingService closing;
    @MockBean com.sicost.closingops.ClosingHistoryService closingHistory;
    @MockBean com.sicost.closingops.ClosingSnapshotService closingSnapshots;
    @MockBean com.sicost.adjustments.AdjustmentService closingAdjustments;
    @MockBean com.sicost.opportunities.OpportunityService opportunities;
    @MockBean com.sicost.opportunitiesops.OpportunityLifecycleService opportunityLifecycle;
    @MockBean com.sicost.opportunitiesops.OpportunityWinService opportunityWins;
    @MockBean com.sicost.opportunitiesops.WorkforceForecastService workforceForecast;
    @MockBean com.sicost.opportunitiesops.OpportunityExportService opportunityExports;
    @MockBean com.sicost.tempassignments.TempAssignmentService tempAssignments;
    @MockBean com.sicost.withdrawalsops.NextSlotService nextSlots;
    @MockBean com.sicost.board.WorkforceBoardService workforceBoard;
    @MockBean com.sicost.board.WorkforceBoardExportService workforceBoardExports;
    @MockBean com.sicost.candidates.CandidateService candidates;
    @MockBean com.sicost.candidatesops.CandidateContactService candidateContacts;
    @MockBean com.sicost.candidatesops.CandidateShortageService candidateShortages;

    @Test void everyDurableOperationHasOneRegisteredLiteralHandler() throws Exception {
        Map<?,?> contract;
        try(var input=new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract=new Yaml().load(input);
        }
        Set<String> expected=new TreeSet<>();
        Map<String,String> legacyHandlers=Map.of(
            "GET /api/costs/projects/{}", "getCostProject",
            "GET /api/costs/projects/{}/causes", "getCostProjectCauses",
            "POST /api/costs/projects/{}/simulate", "simulateCostProject",
            "GET /api/dispatch-attendance", "getDispatchAttendance",
            "PUT /api/dispatch-attendance", "saveDispatchAttendance",
            "GET /api/plan-vs-actual", "listPlanComparison",
            "GET /api/plan-vs-actual/export", "exportPlanComparison",
            "GET /api/plan-vs-actual/shortages", "listMonthlyShortages",
            "GET /api/plan-vs-actual/shortages/export", "exportMonthlyShortages");
        Map<?,?> paths=(Map<?,?>)contract.get("paths");
        assertThat(paths).isNotEmpty();
        paths.forEach((path,item)->((Map<?,?>)item).forEach((method,definition)->{
            if(Set.of("get","post","put","patch","delete").contains(method.toString())) {
                var operation=(Map<?,?>)definition;
                String key=method.toString().toUpperCase(Locale.ROOT)+" "+normalize(path.toString());
                if (operation.get("operationId")==null) assertThat(legacyHandlers).containsKey(key);
                else assertThat(operation.get("operationId")).isInstanceOf(String.class);
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
                // A list operation can negotiate JSON and XLSX at the same method/path.
                // Check the JSON handler here; Spring registers each representation separately.
                var produces = mapping.getProducesCondition().getProducibleMediaTypes();
                if (produces.isEmpty() || produces.stream().anyMatch(
                        media -> media.isCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))) {
                    assertThat(actual.put(method+" "+normalize(path),handler.getMethod().getName())).isNull();
                }
            }
        });
        // Existing phases expose additional APIs; they must not hide a missing contract operation.
        assertThat(actual.keySet()).containsAll(expected);
        legacyHandlers.forEach((key,handler)->assertThat(actual).containsEntry(key,handler));
    }
    @Test void systemManagementOperationsHaveExactlyOneRegisteredHandler() throws Exception {
        List<String> operations;
        try (var input = new ClassPathResource("contracts/system-management-operations.txt").getInputStream();
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
            operations = reader.lines().filter(line -> !line.isBlank()).toList();
        }
        assertThat(operations).isNotEmpty().doesNotHaveDuplicates();
        for (String operation : operations) {
            int separator = operation.indexOf(' ');
            String method = operation.substring(0, separator);
            String path = normalize(operation.substring(separator + 1));
            var matches = handlers.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getKey().getMethodsCondition().getMethods().stream()
                    .anyMatch(value -> value.name().equals(method)))
                .filter(entry -> entry.getKey().getPatternValues().stream()
                    .anyMatch(value -> normalize(value).equals(path)))
                .toList();
            assertThat(matches).as(operation).hasSize(1);
        }
    }
    private static String normalize(String path) { return path.replaceAll("\\{[^/{}]+}", "{}"); }
}
