import { afterEach, describe, expect, it, vi } from "vitest";
import * as client from "./apiClient";
import * as transport from "../shared/api";
import * as shared from "../pages/shared/api";
import * as admin from "../pages/admin/client";
import * as partners from "../pages/partners/client";
import * as partnerActions from "../pages/partnersops/client";
import * as salesParts from "../pages/salesparts/client";
import * as salesPartActions from "../pages/salespartsops/client";
import * as uploads from "../pages/uploadsops/client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
afterEach(() => vi.unstubAllGlobals());
describe("API client registration", () => {
  it("registers the existing partner, sales part and upload clients without reverse imports", () => {
    for (const name of [
      "listPartners",
      "getPartner",
      "createPartner",
      "updatePartner",
      "listPartnerAssignments",
      "listPartnerHistory",
    ] as const)
      expect(client[name]).toBe(partners[name]);
    for (const name of [
      "listPartnerOptions",
      "savePartnerDisable",
      "savePartnerEnable",
      "partnerExportPath",
      "downloadPartnerFile",
    ] as const)
      expect(client[name]).toBe(partnerActions[name]);
    for (const name of [
      "listSalesParts",
      "getSalesPart",
      "createSalesPart",
    ] as const)
      expect(client[name]).toBe(salesParts[name]);
    for (const name of [
      "listSalesPartOptions",
      "saveSalesPartStandard",
      "updateSalesPartName",
      "saveSalesPartDisable",
      "saveSalesPartEnable",
      "getSalesPartExport",
    ] as const)
      expect(client[name]).toBe(salesPartActions[name]);
    for (const name of [
      "createUploadValidation",
      "saveUploadRecheck",
      "saveValidatedUploadApply",
      "getUploadTemplate",
      "getUploadErrorFile",
    ] as const)
      expect(client[name]).toBe(uploads[name]);
  });
  it("keeps multipart requests free of JSON headers through the registered upload client", async () => {
    const result = {
      fileName: "거래처.xlsx",
      totalRows: 0,
      okRows: 0,
      errorRows: 0,
      warningRows: 0,
      items: [],
      token: "validation-token",
    };
    const fetcher = vi.fn().mockResolvedValue(response(result));
    vi.stubGlobal("fetch", fetcher);
    const file = new File([""], "거래처.xlsx");
    expect(await client.createUploadValidation("B.7", file)).toEqual(result);
    expect(fetcher.mock.calls[0][0]).toBe("/api/uploads/B.7/validate");
    const init = fetcher.mock.calls[0][1];
    expect(init.method).toBe("POST");
    expect(init.body).toBeInstanceOf(FormData);
    expect(init.body.get("file")).toBe(file);
    expect(init.headers).toBeUndefined();
  });
  it("preserves selected BIGINT identity and singular errors on partner writes", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          success: false,
          code: "NOT_FOUND",
          field: null,
          message: "없습니다.",
          requestId: "request-partner",
        }),
        { status: 404 },
      ),
    );
    vi.stubGlobal("fetch", fetcher);
    await expect(
      client.savePartnerEnable("9007199254740993"),
    ).rejects.toMatchObject({
      status: 404,
      code: "NOT_FOUND",
      requestId: "request-partner",
    });
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/partners/9007199254740993/enable",
    );
    expect(fetcher.mock.calls[0][1].method).toBe("POST");
    expect(fetcher).toHaveBeenCalledOnce();
  });
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
