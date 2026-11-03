export { api, readData, ApiFailure, retryRecovery } from "../shared/api";
export {
  getOpportunities,
  getClosedOpportunities,
  getOpportunity,
  createOpportunity,
  updateOpportunity,
} from "../pages/opportunities/client";
export type {
  OpportunityWrite,
  OpportunityNeedInput,
  OpportunityNeed,
  OpportunityQuote,
  OpportunityDetail,
  OpportunityFilters,
  OpportunityPage,
} from "../pages/opportunities/client";
export {
  listOpportunities,
  getOpportunityForecast,
  updateOpportunityStage,
  updateOpportunityApproval,
  updateOpportunityRejection,
  updateOpportunityLoss,
  updateOpportunityDisable,
  createOpportunityWin,
  exportOpportunities,
  candidateNavigation,
} from "../pages/opportunitiesops/client";
export type {
  ForecastFilters,
  ForecastPage,
  Shortage,
} from "../pages/opportunitiesops/client";
export { getWorkforceForecast } from "../pages/workforceops/client";
export type {
  WorkforceForecastFilters,
  WorkforceForecastPage,
} from "../pages/workforceops/client";
export { getWorkforceBoard, exportWorkforceBoard } from "../pages/board/client";
export type {
  BoardFilters,
  BoardPage,
  BoardRow,
  BoardCell,
} from "../pages/board/client";
export {
  listCandidates,
  getCandidate,
  createCandidate,
  updateCandidate,
  candidateWorkspaceState,
} from "../pages/candidates/client";
export type {
  CandidateInput,
  CandidateDetail,
  CandidateFilters,
  CandidatePage,
} from "../pages/candidates/client";
export {
  getCandidateShortages,
  createCandidateContact,
  createCandidateConversion,
  getContactCandidate,
} from "../pages/candidatesops/client";
export type {
  CandidateContact,
  CandidateShortage,
  CandidateShortageFilters,
  CandidateShortagePage,
} from "../pages/candidatesops/client";
export {
  listTempAssignments,
  createTempAssignment,
  releaseTempAssignment,
  getWithdrawalNextSlots,
  TEMP_ASSIGNMENTS_CHANGED,
} from "../pages/tempassignments/client";
export type {
  TempAssignment,
  TempAssignmentWrite,
  TempAssignmentPage,
  TempAssignmentFilters,
  TempAssignmentNeed,
  NextSlot,
  NextSlotsResult,
} from "../pages/tempassignments/client";
export { listWithdrawals, exportWithdrawals } from "../pages/us1/client";
export type {
  WithdrawalFilters,
  WithdrawalRow,
  WithdrawalPage,
} from "../pages/us1/client";
export {
  listPlanComparison,
  downloadPlanComparison,
} from "../pages/us4/client";
export type {
  PlanComparisonFilters,
  PlanComparisonRow,
  PlanComparisonPage,
} from "../pages/us4/client";
export {
  listMonthlyShortages,
  downloadMonthlyShortages,
} from "../pages/us5/client";
export type {
  MonthlyShortageFilters,
  MonthlyShortageRow,
  MonthlyShortagePage,
} from "../pages/us5/client";
export type { ApiError, ApiResponse, PageResult } from "../shared/types";
export {
  getMenus,
  getCodeOptions,
  getMonthClose,
  getEffectiveParam,
  monthStatus,
  menuScreens,
} from "../pages/shared/api";
export type {
  MenuNode,
  MonthClose,
  CodeOption,
  OperatingParam,
} from "../pages/shared/api";
export { useCodes } from "../pages/shared/useCodes";
export {
  adminPaths,
  allPages,
  listCodeGroups,
  listManagedCodes,
  listParamVersions,
  saveGroupCodes,
  createCodeGroup,
  createExcelMap,
  updateExcelMap,
  saveParamVersion,
} from "../pages/admin/client";
export type {
  CodeGroup,
  CodeRow,
  CodeGroupCreate,
  ExcelMap,
  ExcelMapCreate,
  ExcelMapPatch,
  CodeChange,
} from "../pages/admin/client";
export {
  listPartners,
  getPartner,
  createPartner,
  updatePartner,
} from "../pages/partners/client";
export type {
  PartnerInput,
  PartnerDetail,
  PartnerListItem,
  PartnerFilters,
} from "../pages/partners/client";
export {
  listPartnerOptions,
  listPartnerAssignments,
  listPartnerHistory,
  disablePartners,
  enablePartner,
  downloadList,
} from "../pages/partnersops/client";
export type {
  PartnerAssignment,
  PartnerChange,
  PartnerBlock,
  PartnerDisableResult,
} from "../pages/partnersops/client";
export {
  listSalesParts,
  getSalesPart,
  createSalesPart,
} from "../pages/salesparts/client";
export type {
  SalesPartStandard,
  SalesPartListItem,
  SalesPartDetail,
  SalesPartCreate,
} from "../pages/salesparts/client";
export {
  listSalesPartOptions,
  saveSalesPartStandard,
  updateSalesPartName,
  saveSalesPartDisable,
  saveSalesPartEnable,
} from "../pages/salespartsops/client";
export type {
  SalesPartOption,
  SalesPartStandardSave,
} from "../pages/salespartsops/client";
export {
  createUploadValidation,
  saveUploadRecheck,
  saveValidatedUploadApply,
  downloadUploadFile,
  uploadTemplatePath,
  uploadErrorPath,
} from "../pages/uploadsops/client";
export type {
  UploadIssue,
  UploadValidationResult,
  UploadApplyResult,
} from "../pages/uploadsops/client";
export {
  listDevelopers,
  getDeveloper,
  createDeveloper,
  updateDeveloper,
  enableDeveloper,
} from "../pages/developers/client";
export type {
  DeveloperInput,
  DeveloperDetail,
  DeveloperListItem,
  DeveloperFilters,
} from "../pages/developers/client";
export {
  listDeveloperOptions,
  listDeveloperAssignments,
  listDeveloperMonthlyCosts,
  listDeveloperHistory,
  disableDevelopers,
  downloadDevelopers,
} from "../pages/developersops/client";
export type {
  DeveloperOption,
  DeveloperAssignment,
  DeveloperCost,
  DeveloperChange,
  DeveloperBlock,
  DeveloperDisableResult,
} from "../pages/developersops/client";
export {
  listDeveloperWorkStatuses,
  createDeveloperWorkStatus,
  updateDeveloperWorkStatus,
  updateDeveloperWorkStatusRemoval,
} from "../pages/workstatus/client";
export type {
  WorkStatusInput,
  WorkStatusRow,
  WorkStatusCode,
} from "../pages/workstatus/client";
export { getUploadResult } from "../pages/apiops/client";
export type { UploadResult } from "../pages/apiops/client";
export {
  revalidatePersonnelUpload,
  applyPersonnelUpload,
  listPersonnelUploadRows,
} from "../pages/uploadsops/client";
export {
  listProjects,
  getProject,
  createProject,
  updateProject,
  exportProjects,
  listProjectOptions,
  listProjectAssignments,
  listProjectHistory,
} from "../pages/projects/client";
export type {
  ProjectInput,
  ProjectDetail,
  ProjectListItem,
  ProjectFilters,
  ProjectPage,
  ProjectOption,
  ProjectAssignment,
  ProjectHistory,
} from "../pages/projects/client";
export {
  listProjectBillStages,
  saveProjectBillStages,
} from "../pages/billstages/client";
export type {
  ProjectBillStageInput,
  ProjectBillStage,
  ProjectBillStages,
} from "../pages/billstages/client";
export {
  listProjectExpenses,
  createProjectExpense,
  updateProjectExpense,
  updateProjectExpenseRemoval,
} from "../pages/expenses/client";
export type {
  ProjectExpenseInput,
  ProjectExpense,
} from "../pages/expenses/client";
export {
  listProjectPartChanges,
  createProjectPartChange,
} from "../pages/partchanges/client";
export type {
  ProjectPartChangeRequest,
  ProjectPartChange,
} from "../pages/partchanges/client";
export {
  listProjectContractChanges,
  createProjectContractChange,
  updateProjectContractChange,
  updateProjectContractChangeConfirmation,
  updateProjectContractChangeRemoval,
} from "../pages/contractchanges/client";
export type {
  ContractChangeInput,
  ContractChangeRow,
  ContractChanges,
} from "../pages/contractchanges/client";
export {
  getProjectStaffPlans,
  saveProjectStaffPlans,
} from "../pages/staffplans/client";
export type {
  ProjectStaffPlanInput,
  ProjectStaffPlan,
  ProjectStaffPlans,
} from "../pages/staffplans/client";
export { getProjectProgress } from "../pages/projectsops/progressClient";
export {
  updateProjectPhase,
  updateProjectPhaseCompletion,
  updateProjectPhaseUncompletion,
} from "../pages/phases/client";
export { saveProjectMemo, updateProjectMemo } from "../pages/memos/client";
export {
  listAssignments,
  getAssignment,
  createAssignment,
  checkAssignments,
} from "../pages/assignments/client";
export type {
  AssignmentInput,
  AssignmentItem,
  AssignmentDetail,
  AssignmentFilters,
  AssignmentPage,
  AssignmentSave,
  AssignmentWarning,
} from "../pages/assignments/client";
export {
  getAssignmentMonths,
  saveAssignmentMonths,
} from "../pages/months/client";
export type {
  AssignmentMonth,
  AssignmentMonthsRequest,
  AssignmentMonthsResult,
} from "../pages/months/client";
export { getProjectAssignmentMonths } from "../pages/projectsops/assignmentMonthsClient";
export type {
  ProjectAssignmentMonthsResult,
  ProjectAssignmentMonthRow,
  ProjectMonthCell,
} from "../pages/projectsops/assignmentMonthsClient";
export {
  listAssignmentChanges,
  createAssignmentChange,
  createAssignmentFix,
} from "../pages/changes/client";
export type {
  AssignmentChange,
  ChangeRequest,
  FixRequest,
  ChangeType,
} from "../pages/changes/client";
export {
  listIssues,
  listProjectIssues,
  createIssue,
  updateIssue,
} from "../pages/issues/client";
export { getIssueOwnerOptions } from "../pages/issuesops/client";
export {
  getProjectBoard,
  getProgressParameters,
  getProgressParameters as getParams,
} from "../pages/apiops/boardClient";
export {
  downloadProjectBoard,
  downloadProjectBoard as getProjectBoardExport,
} from "../pages/projectboardops/client";
export {
  listBillings,
  getBilling,
  createBilling,
  updateBilling,
  previewBilling,
  getBillingSummary,
} from "../pages/billings/client";
export type {
  BillingRow,
  BillingDetail,
  BillingDetailLine,
  BillingCreate,
  BillingUpdate,
  BillingFilters,
  BillingPage,
  BillingPreview,
  BillingSummary,
} from "../pages/billings/client";
export {
  listBillingGaps,
  listBillingOverdue,
  confirmBilling,
  cancelBilling,
  createBillingAmendment,
} from "../pages/billingsops/client";
export type {
  BillingGap,
  BillingOverdue,
  GapPage,
  OverduePage,
} from "../pages/billingsops/client";
export {
  listBillingReceipts,
  createBillingReceipt,
  updateBillingReceipt,
  deleteBillingReceipt,
} from "../pages/receipts/client";
export type {
  BillingReceiptInput,
  BillingReceipt,
  ReceiptBillingDetail,
} from "../pages/receipts/client";
export {
  getDispatchAttendance,
  saveDispatchAttendance,
} from "../pages/dispatchattendance/client";
export type {
  DispatchAttendanceInput,
  DispatchAttendanceRow,
  DispatchAttendanceResult,
} from "../pages/dispatchattendance/client";
export {
  validateDispatchAttendanceUpload,
  applyDispatchAttendanceUpload,
  revalidateDispatchAttendanceUpload,
  listDispatchAttendanceUploadRows,
  listDispatchAttendanceUploadIssues,
} from "../pages/dispatchattendance2/client";
export type {
  AttendancePreview,
  AttendanceApplied,
} from "../pages/dispatchattendance2/client";
export {
  listCostPeople,
  getCostPersonBreakdown,
  downloadCostPeople,
} from "../pages/people/client";
export type {
  PeopleFilters,
  PeopleRow,
  PeopleSummary,
  PeopleList,
  PersonBreakdown,
} from "../pages/people/client";
export {
  listCostProjects,
  getCostProject,
  getCostProjectCauses,
  simulateCostProject,
  downloadCostProjects,
} from "../pages/projects/costClient";
export type {
  CostProjectFilters,
  CostProjectRow,
  CostProjectList,
  CostProjectDetail,
  ProjectCauses,
  SimulationInput,
  ProjectSimulation,
} from "../pages/projects/costClient";
export {
  listPartSettlements,
  getPartSettlement,
  updatePartSettlementCard,
  confirmPartSettlement,
  cancelPartSettlement,
  downloadPartSettlements,
} from "../pages/parts/client";
export type {
  Settlement,
  SettlementItem,
  SettlementList,
  SettlementDetail,
} from "../pages/parts/client";
export {
  listCostRenegotiations,
  getCostAllocation,
  downloadCostRenegotiations,
} from "../pages/costsops/client";
export type {
  RenegotiationFilters,
  RenegotiationRow,
  RenegotiationResult,
  AllocationRow,
  AllocationResult,
} from "../pages/costsops/client";
export {
  listFinanceEntries,
  createFinanceEntry,
  updateFinanceEntry,
  deleteFinanceEntry,
  previewPreviousFinanceEntries,
} from "../pages/entries/client";
export type {
  FinanceEntryInput,
  FinanceEntry,
  EntryList,
} from "../pages/entries/client";
export {
  getTaxPlan,
  getTaxPayments,
  saveTaxPlan,
  saveTaxPayments,
} from "../pages/tax/client";
export type {
  TaxPlan,
  TaxMonth,
  TaxHistory,
  TaxPayment,
  TaxPayments,
} from "../pages/tax/client";
export {
  getMonthlyFinanceProfit,
  getFinanceProfitStructure,
  downloadProfit,
} from "../pages/profit/client";
export type {
  ProfitAnnual,
  ProfitMonth,
  ProfitStructure,
  ProfitPeriod,
} from "../pages/profit/client";
export {
  getBusinessPlan,
  getBusinessPlanComparison,
  saveBusinessPlan,
  downloadBusinessPlan,
} from "../pages/businessplan/client";
export type {
  BusinessPlan,
  BusinessPlans,
  BusinessComparison,
} from "../pages/businessplan/client";
export {
  listFinancePayments,
  createFinancePayment,
  updateFinancePayment,
  generateFinancePayments,
  completeFinancePayment,
  reopenFinancePayment,
  carryFinancePayment,
  exportFinancePayments,
} from "../pages/payments/client";
export type {
  PaymentInput,
  Payment,
  PaymentList,
} from "../pages/payments/client";
export {
  getPaymentOrders,
  savePaymentOrders,
} from "../pages/paymentorders/client";
export type {
  PaymentOrder,
  PaymentOrders,
} from "../pages/paymentorders/client";
export {
  getFinanceCashflow,
  getCashOpening,
  saveCashOpening,
  getFinanceOtherInflows,
  postFinanceOtherInflows,
  putFinanceOtherInflows,
  downloadCashflow,
} from "../pages/cashflow/client";
export type {
  CashFlow,
  CashFlowMode,
  CashOpening,
  CashInflow,
} from "../pages/cashflow/client";
export {
  validateFinanceEntriesUpload,
  applyFinanceEntriesUpload,
} from "../pages/financeentries/client";
export {
  validateBusinessPlanUpload,
  applyBusinessPlanUpload,
} from "../pages/businessplan2/client";
export {
  validatePriorProfitUpload,
  applyPriorProfitUpload,
} from "../pages/priorprofit/client";
export { getClosing } from "../pages/apiops/closingClient";
export type { Closing, ClosingCheck } from "../pages/apiops/closingClient";
export {
  previewClosing,
  listClosingHistory,
  runClosingCheck,
  confirmClosing,
  reopenClosing,
} from "../pages/closingops/client";
export type {
  ClosingPreview,
  ClosingCheckResult,
  ClosingResult,
  ClosingHistoryRow,
  ClosingHistoryPage,
} from "../pages/closingops/client";
export {
  listClosingAdjustments,
  createClosingAdjustment,
  cancelClosingAdjustment,
} from "../pages/adjustments/client";
export type {
  AdjustmentType,
  AdjustmentRow,
  AdjustmentInput,
  AdjustmentPage,
  AdjustmentFilters,
} from "../pages/adjustments/client";
