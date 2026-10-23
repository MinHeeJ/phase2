import {
  downloadPartnerFile,
  partnerExportPath,
  type PartnerFilters,
} from "../pages/partnersops/client";
export { api, readData, ApiFailure, retryRecovery } from "../shared/api";
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
  listPartnerAssignments,
  listPartnerHistory,
  disablePartners,
  enablePartner,
} from "../pages/partners/client";
export type {
  PartnerInput,
  PartnerDetail,
  PartnerListItem,
  PartnerFilters,
  PartnerAssignment,
  PartnerChange,
} from "../pages/partners/client";
export {
  listPartnerOptions,
  savePartnerDisable,
  savePartnerEnable,
  downloadPartnerFile,
  partnerExportPath,
} from "../pages/partnersops/client";
export type {
  PartnerSelection,
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
  SalesPartDraft,
} from "../pages/salesparts/client";
export {
  listSalesPartOptions,
  saveSalesPartStandard,
  updateSalesPartName,
  saveSalesPartDisable,
  saveSalesPartEnable,
  getSalesPartExport,
} from "../pages/salespartsops/client";
export type {
  SalesPartStandardSave,
  SalesPartOption,
} from "../pages/salespartsops/client";
export {
  createUploadValidation,
  saveUploadRecheck,
  saveValidatedUploadApply,
  getUploadTemplate,
  getUploadErrorFile,
  checkUploadFile,
  MAX_UPLOAD_BYTES,
} from "../pages/uploadsops/client";
export type {
  UploadGroup,
  UploadIssue,
  UploadResult,
} from "../pages/uploadsops/client";

export const getPartnerExport = (
  filters: PartnerFilters,
  columns: readonly string[],
  ids?: readonly string[],
) => downloadPartnerFile(partnerExportPath("partners", filters, columns, ids));
