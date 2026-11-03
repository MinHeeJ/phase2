// Existing screens and dialogs remain their original implementations; these exports are integration points, not new routes.
// SCR-UI-07
export { default as PipelineProfitStructure } from "../profit/UI07Screen";
// SCR-UI-09
export { default as PipelineNetProfit } from "../profit/UI09Screen";
// SCR-UI-16, SCR-UI-22, SCR-UI-23, SCR-UI-24, SCR-UI-25: retain the existing menu-driven shared screen and mobile guards.
export { SharedScreen as PipelineSharedScreen } from "../shared/SharedScreen";
// SCR-UI-17
export { default as PipelineWorkforceDemand } from "./WorkforceDemandScreen";
// SCR-UI-21
export { default as PipelineCashFlow } from "../cashflow/UI21Screen";
// SCR-UI-27, SCR-UI-27-M5: the original parameters tab continues to own its value-change dialog.
export { default as PipelineCommonCodes } from "../admin/UI27Screen";
export { ParamsTab as PipelineOperatingCriteria } from "../admin/ParamsTab";
// SCR-UI-28: preserve the YEAR calendar, filters and board download.
export { default as PipelineWorkforceBoard } from "../board/UI28Screen";
// SCR-UI-02-M8, SCR-UI-27-M1
export { UnsavedDialog as PipelineUnsavedDialog } from "../admin/AdminDialogs";
// SCR-UI-16-M2, SCR-UI-16-M6
export {
  TabOverflowDialog as PipelineTabOverflowDialog,
  ScreenSearchDialog as PipelineScreenSearchDialog,
} from "../admin/AdminDialogs";
// SCR-UI-01-M7, SCR-UI-01-M8, SCR-UI-01-M9, SCR-UI-01-M10, SCR-UI-12-M7: retain the existing form type and validation/result/apply stages.
export { UploadDialogs as PipelineUploadDialogs } from "../uploadsops/UploadDialogs";
// SCR-UI-12-M2, SCR-UI-12-M4
export {
  AssignmentChangeForm as PipelineAssignmentChange,
  AssignmentFixForm as PipelineAssignmentCorrection,
} from "../changes/AssignmentChangeForms";
// SCR-UI-27-M3, SCR-UI-27-M4
export {
  GroupDialog as PipelineCodeGroupDialog,
  ExcelMapDialog as PipelineExcelMapDialog,
} from "../admin/CreateDialogs";
export {
  default as OpportunityPipelineScreen,
  OpportunityActionDialog,
} from "./OpportunityPipelineScreen";
export { ForecastContent, OpportunityScenarios } from "./WorkforceDemandScreen";
