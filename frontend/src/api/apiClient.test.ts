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

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
afterEach(() => vi.unstubAllGlobals());
describe("API client registration", () => {
  it.each([
    ["listPartners", partners.listPartners],
    ["defaultPartnerFilters", partners.defaultPartnerFilters],
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
  it("uses the partner screen's default filters through the registered client", async () => {
    const page = { items: [], total: 0, page: 1, size: 50 };
    const fetcher = vi.fn().mockResolvedValue(response(page));
    vi.stubGlobal("fetch", fetcher);
    expect(await client.listPartners(client.defaultPartnerFilters)).toEqual(
      page,
    );
    const [path] = fetcher.mock.calls[0];
    const [resource, query] = path.split("?");
    expect(resource).toBe("/api/partners");
    expect(Object.fromEntries(new URLSearchParams(query))).toEqual({
      kind: "",
      useYn: "Y",
      q: "",
      page: "1",
      size: "50",
      sort: "ptnr_nm,asc",
    });
  });
  it("keeps both public selection APIs on their existing read paths", async () => {
    const fetcher = vi
      .fn()
      .mockImplementation(() => Promise.resolve(response([])));
    vi.stubGlobal("fetch", fetcher);
    expect(await client.listPartnerOptions("CUST")).toEqual([]);
    expect(await client.listPartnerOptions("VEND")).toEqual([]);
    expect(await client.listSalesPartOptions()).toEqual([]);
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/partners/options?kind=CUST",
      "/api/partners/options?kind=VEND",
      "/api/sales-parts/options",
    ]);
  });
  it("passes the selected file, returned upload id and returned token across the upload registrations", async () => {
    const rejected: client.UploadValidationResult = {
      fileName: "거래처.xlsx",
      upldId: "9007199254740993",
      totalRows: 1,
      okRows: 0,
      errorRows: 1,
      warningRows: 0,
      items: [],
    };
    const validated: client.UploadValidationResult = {
      fileName: rejected.fileName,
      token: "6f925e37-e30d-4b06-a32b-0e599e0ab899",
      totalRows: 1,
      okRows: 1,
      errorRows: 0,
      warningRows: 0,
      items: [],
    };
    const applied: client.UploadApplyResult = {
      upldId: "9007199254740994",
      appliedRows: 1,
    };
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(rejected))
      .mockResolvedValueOnce(response(validated))
      .mockResolvedValueOnce(response(applied));
    vi.stubGlobal("fetch", fetcher);
    const file = new File(["selected workbook"], rejected.fileName);
    const inspection = await client.createUploadValidation("B.7", file);
    const recheck = await client.saveUploadRecheck(inspection.upldId!);
    expect(await client.saveValidatedUploadApply(recheck.token!)).toEqual(
      applied,
    );
    expect(fetcher.mock.calls.map(([url, init]) => [url, init.method])).toEqual(
      [
        ["/api/uploads/B.7/validate", "POST"],
        [`/api/uploads/${rejected.upldId}/recheck`, "POST"],
        [`/api/uploads/validated/${validated.token}/apply`, "POST"],
      ],
    );
    const validationRequest = fetcher.mock.calls[0][1];
    expect(validationRequest.body).toBeInstanceOf(FormData);
    expect(validationRequest.body.get("file")).toBe(file);
    expect(validationRequest.headers).toBeUndefined();
    expect(client.uploadErrorPath(inspection.upldId!)).toBe(
      `/api/uploads/${rejected.upldId}/error-file`,
    );
    expect(client.uploadTemplatePath("B.7")).toBe("/api/uploads/templates/B.7");
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
