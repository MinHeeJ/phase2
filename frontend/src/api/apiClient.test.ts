import { afterEach, describe, expect, it, vi } from "vitest";
import * as client from "./apiClient";
import * as transport from "../shared/api";
import * as shared from "../pages/shared/api";
import * as admin from "../pages/admin/client";
import * as partners from "../pages/partners/client";
import * as partnerLifecycle from "../pages/partnersops/client";
import * as salesParts from "../pages/salesparts/client";
import * as salesPartLifecycle from "../pages/salespartsops/client";
import * as uploads from "../pages/uploadsops/client";
import * as developers from "../pages/developers/client";
import * as developerLifecycle from "../pages/developersops/client";
import * as workStatus from "../pages/workstatus/client";
import * as uploadResults from "../pages/apiops/client";
import * as projects from "../pages/projects/client";
import * as billStages from "../pages/billstages/client";
import * as expenses from "../pages/expenses/client";
import * as partChanges from "../pages/partchanges/client";
import * as contractChanges from "../pages/contractchanges/client";
import * as staffPlans from "../pages/staffplans/client";
import * as billings from "../pages/billings/client";
import * as billingOperations from "../pages/billingsops/client";
import * as receipts from "../pages/receipts/client";
import * as attendance from "../pages/dispatchattendance/client";
import * as attendanceUpload from "../pages/dispatchattendance2/client";
import * as costs from "../pages/costprofit/client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
afterEach(() => vi.unstubAllGlobals());
describe("API client registration", () => {
  it.each([
    ["getCostAllocation", costs.getCostAllocation],
    ["listCostPeople", costs.listCostPeople],
    ["getCostPersonBreakdown", costs.getCostPersonBreakdown],
    ["downloadCostPeople", costs.downloadCostPeople],
    ["listCostProjects", costs.listCostProjects],
    ["getCostProject", costs.getCostProject],
    ["getCostProjectCauses", costs.getCostProjectCauses],
    ["simulateCostProject", costs.simulateCostProject],
    ["downloadCostProjects", costs.downloadCostProjects],
    ["listPartSettlements", costs.listPartSettlements],
    ["getPartSettlement", costs.getPartSettlement],
    ["savePartSettlementCard", costs.savePartSettlementCard],
    ["confirmPartSettlement", costs.confirmPartSettlement],
    ["cancelPartSettlement", costs.cancelPartSettlement],
    ["downloadPartSettlements", costs.downloadPartSettlements],
    ["listCostRenegotiations", costs.listCostRenegotiations],
    ["downloadCostRenegotiations", costs.downloadCostRenegotiations],
    ["listBillings", billings.listBillings],
    ["getBilling", billings.getBilling],
    ["createBilling", billings.createBilling],
    ["updateBilling", billings.updateBilling],
    ["previewBilling", billings.previewBilling],
    ["getBillingSummary", billings.getBillingSummary],
    ["listBillingGaps", billingOperations.listBillingGaps],
    ["listBillingOverdue", billingOperations.listBillingOverdue],
    ["confirmBilling", billingOperations.confirmBilling],
    ["cancelBilling", billingOperations.cancelBilling],
    ["createBillingAmendment", billingOperations.createBillingAmendment],
    ["listBillingReceipts", receipts.listBillingReceipts],
    ["createBillingReceipt", receipts.createBillingReceipt],
    ["updateBillingReceipt", receipts.updateBillingReceipt],
    ["deleteBillingReceipt", receipts.deleteBillingReceipt],
    ["getDispatchAttendance", attendance.getDispatchAttendance],
    ["saveDispatchAttendance", attendance.saveDispatchAttendance],
    [
      "validateDispatchAttendanceUpload",
      attendanceUpload.validateDispatchAttendanceUpload,
    ],
    [
      "applyDispatchAttendanceUpload",
      attendanceUpload.applyDispatchAttendanceUpload,
    ],
    [
      "revalidateDispatchAttendanceUpload",
      attendanceUpload.revalidateDispatchAttendanceUpload,
    ],
    [
      "listDispatchAttendanceUploadRows",
      attendanceUpload.listDispatchAttendanceUploadRows,
    ],
    [
      "listDispatchAttendanceUploadIssues",
      attendanceUpload.listDispatchAttendanceUploadIssues,
    ],
    ["listProjects", projects.listProjects],
    ["getProject", projects.getProject],
    ["createProject", projects.createProject],
    ["updateProject", projects.updateProject],
    ["exportProjects", projects.exportProjects],
    ["listProjectOptions", projects.listProjectOptions],
    ["listProjectAssignments", projects.listProjectAssignments],
    ["listProjectHistory", projects.listProjectHistory],
    ["listProjectBillStages", billStages.listProjectBillStages],
    ["saveProjectBillStages", billStages.saveProjectBillStages],
    ["listProjectExpenses", expenses.listProjectExpenses],
    ["createProjectExpense", expenses.createProjectExpense],
    ["updateProjectExpense", expenses.updateProjectExpense],
    ["updateProjectExpenseRemoval", expenses.updateProjectExpenseRemoval],
    ["listProjectPartChanges", partChanges.listProjectPartChanges],
    ["createProjectPartChange", partChanges.createProjectPartChange],
    ["listProjectContractChanges", contractChanges.listProjectContractChanges],
    [
      "createProjectContractChange",
      contractChanges.createProjectContractChange,
    ],
    [
      "updateProjectContractChange",
      contractChanges.updateProjectContractChange,
    ],
    [
      "updateProjectContractChangeConfirmation",
      contractChanges.updateProjectContractChangeConfirmation,
    ],
    [
      "updateProjectContractChangeRemoval",
      contractChanges.updateProjectContractChangeRemoval,
    ],
    ["getProjectStaffPlans", staffPlans.getProjectStaffPlans],
    ["saveProjectStaffPlans", staffPlans.saveProjectStaffPlans],
    ["listPartners", partners.listPartners],
    ["getPartner", partners.getPartner],
    ["createPartner", partners.createPartner],
    ["updatePartner", partners.updatePartner],
    ["listPartnerOptions", partnerLifecycle.listPartnerOptions],
    ["listPartnerAssignments", partnerLifecycle.listPartnerAssignments],
    ["listPartnerHistory", partnerLifecycle.listPartnerHistory],
    ["disablePartners", partnerLifecycle.disablePartners],
    ["enablePartner", partnerLifecycle.enablePartner],
    ["downloadList", partnerLifecycle.downloadList],
    ["listSalesParts", salesParts.listSalesParts],
    ["getSalesPart", salesParts.getSalesPart],
    ["createSalesPart", salesParts.createSalesPart],
    ["listSalesPartOptions", salesPartLifecycle.listSalesPartOptions],
    ["saveSalesPartStandard", salesPartLifecycle.saveSalesPartStandard],
    ["updateSalesPartName", salesPartLifecycle.updateSalesPartName],
    ["saveSalesPartDisable", salesPartLifecycle.saveSalesPartDisable],
    ["saveSalesPartEnable", salesPartLifecycle.saveSalesPartEnable],
    ["createUploadValidation", uploads.createUploadValidation],
    ["saveUploadRecheck", uploads.saveUploadRecheck],
    ["saveValidatedUploadApply", uploads.saveValidatedUploadApply],
    ["downloadUploadFile", uploads.downloadUploadFile],
    ["uploadTemplatePath", uploads.uploadTemplatePath],
    ["uploadErrorPath", uploads.uploadErrorPath],
    ["listDevelopers", developers.listDevelopers],
    ["getDeveloper", developers.getDeveloper],
    ["createDeveloper", developers.createDeveloper],
    ["updateDeveloper", developers.updateDeveloper],
    ["enableDeveloper", developers.enableDeveloper],
    ["listDeveloperOptions", developerLifecycle.listDeveloperOptions],
    ["listDeveloperAssignments", developerLifecycle.listDeveloperAssignments],
    ["listDeveloperMonthlyCosts", developerLifecycle.listDeveloperMonthlyCosts],
    ["listDeveloperHistory", developerLifecycle.listDeveloperHistory],
    ["disableDevelopers", developerLifecycle.disableDevelopers],
    ["downloadDevelopers", developerLifecycle.downloadDevelopers],
    ["listDeveloperWorkStatuses", workStatus.listDeveloperWorkStatuses],
    ["createDeveloperWorkStatus", workStatus.createDeveloperWorkStatus],
    ["updateDeveloperWorkStatus", workStatus.updateDeveloperWorkStatus],
    [
      "updateDeveloperWorkStatusRemoval",
      workStatus.updateDeveloperWorkStatusRemoval,
    ],
    ["getUploadResult", uploadResults.getUploadResult],
    ["revalidatePersonnelUpload", uploads.revalidatePersonnelUpload],
    ["applyPersonnelUpload", uploads.applyPersonnelUpload],
    ["listPersonnelUploadRows", uploads.listPersonnelUploadRows],
  ] as const)(
    "registers %s directly from its owning client",
    (name, implementation) => {
      expect(client[name]).toBe(implementation);
    },
  );
  it("reuses the existing transport and feature clients without wrapping their envelopes", () => {
    expect(client.api).toBe(transport.api);
    expect(client.readData).toBe(transport.readData);
    expect(client.ApiFailure).toBe(transport.ApiFailure);
    expect(client.retryRecovery).toBe(transport.retryRecovery);
    for (const name of [
      "getMenus",
      "getCodeOptions",
      "getMonthClose",
      "getEffectiveParam",
    ] as const)
      expect(client[name]).toBe(shared[name]);
    for (const name of [
      "listCodeGroups",
      "listManagedCodes",
      "listParamVersions",
      "saveGroupCodes",
      "createCodeGroup",
      "createExcelMap",
      "updateExcelMap",
      "saveParamVersion",
    ] as const)
      expect(client[name]).toBe(admin[name]);
    expect(client.adminPaths).toBe(admin.adminPaths);
  });
  it("exposes the existing paged resource paths for mapping, parameter and history reads", async () => {
    const page = { items: [], total: 0, page: 1, size: 50 };
    const fetcher = vi
      .fn()
      .mockImplementation(() => Promise.resolve(response(page)));
    vi.stubGlobal("fetch", fetcher);
    for (const path of [
      client.adminPaths.maps,
      client.adminPaths.params,
      client.adminPaths.history,
    ]) {
      expect(await client.readData(`${path}?page=1&size=50`)).toEqual(page);
    }
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/admin/excel-maps?page=1&size=50",
      "/api/admin/params?page=1&size=50",
      "/api/admin/code-history?page=1&size=50",
    ]);
  });
  it("preserves selected mapping identity and sends only the explicit PATCH payload", async () => {
    const fetcher = vi.fn().mockResolvedValue(response(undefined));
    vi.stubGlobal("fetch", fetcher);
    const input = {
      map_role_cd: "SUB" as const,
      rmrk_desc: "메모",
      use_yn: "N" as const,
    };
    await client.updateExcelMap("9007199254740993", input);
    expect(fetcher).toHaveBeenCalledOnce();
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/admin/excel-maps/9007199254740993",
    );
    expect(fetcher.mock.calls[0][1]).toMatchObject({
      method: "PATCH",
      body: JSON.stringify(input),
    });
  });
  it("preserves singular field errors and server inquiry identifiers", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "CLOSED_MONTH",
            message: "마감된 월입니다.",
            field: "month",
            requestId: "server-request",
          }),
          { status: 409 },
        ),
      ),
    );
    await expect(
      client.saveParamVersion("VAT_RATE", "2026-09", { num_val: "10" }),
    ).rejects.toMatchObject({
      status: 409,
      field: "month",
      code: "CLOSED_MONTH",
      requestId: "server-request",
    });
  });
  it("accepts the existing data-less write response and presence-aware PATCH request", async () => {
    const fetcher = vi.fn().mockResolvedValue(response(undefined));
    vi.stubGlobal("fetch", fetcher);
    const input: client.ExcelMapPatch = { rmrk_desc: "" };
    const result: client.ApiResponse = await client.updateExcelMap("9", input);
    expect(result).toEqual({ success: true, meta: {} });
    expect(fetcher.mock.calls[0][1]).toMatchObject({
      method: "PATCH",
      body: '{"rmrk_desc":""}',
    });
  });
  it("never replays an uncertain write through the registered recovery helper", async () => {
    const fetcher = vi
      .fn()
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue(response([]));
    vi.stubGlobal("fetch", fetcher);
    try {
      await client.createCodeGroup({
        cd_grp_id: "NEW_GROUP",
        cd_grp_nm: "새 그룹",
        attr1_nm: "",
        attr2_nm: "",
        attr3_nm: "",
      });
      throw new Error("Expected write failure");
    } catch (error) {
      expect(error).toBeInstanceOf(client.ApiFailure);
      expect(error).toMatchObject({ uncertainWrite: true, requestId: null });
      await client.retryRecovery(error as client.ApiFailure, () =>
        client.getMenus(),
      );
    }
    expect(
      fetcher.mock.calls.map(([url, init]) => [url, init.method ?? "GET"]),
    ).toEqual([
      ["/api/admin/code-groups", "POST"],
      ["/api/menus", "GET"],
    ]);
  });
});
