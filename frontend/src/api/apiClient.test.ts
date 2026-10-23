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
  it("registers every UI-15 operation without replacing feature-local transports", () => {
    for (const name of [
      "listPartners",
      "getPartner",
      "createPartner",
      "updatePartner",
    ] as const)
      expect(client[name]).toBe(partners[name]);
    for (const name of [
      "listPartnerAssignments",
      "listPartnerHistory",
      "listPartnerOptions",
      "savePartnerDisable",
      "savePartnerEnable",
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
    expect(client.uploadPaths).toBe(uploads.uploadPaths);
  });
  it("keeps multipart validation browser-owned rather than adding the JSON content type", async () => {
    const data = {
      fileName: "partners.xlsx",
      totalRows: 0,
      okRows: 0,
      errorRows: 0,
      warningRows: 0,
      items: [],
      token: "validation-token",
    };
    const fetcher = vi.fn().mockResolvedValue(response(data));
    vi.stubGlobal("fetch", fetcher);
    expect(
      await client.createUploadValidation(
        "B.7",
        new File(["test"], "partners.xlsx"),
      ),
    ).toEqual(data);
    expect(fetcher.mock.calls[0][0]).toBe("/api/uploads/B.7/validate");
    expect(fetcher.mock.calls[0][1].body).toBeInstanceOf(FormData);
    expect(fetcher.mock.calls[0][1].headers).toBeUndefined();
  });
  it("keeps export columns and selected identifiers as repeated query keys", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response("workbook", {
        headers: {
          "Content-Type":
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "Content-Disposition":
            "attachment; filename*=UTF-8''%EC%98%81%EC%97%85%ED%8C%8C%ED%8A%B8_20261023.xlsx",
        },
      }),
    );
    vi.stubGlobal("fetch", fetcher);
    const result = await client.getSalesPartExport(
      ["bsn_part_nm", "ldr_nm"],
      ["42", "77"],
    );
    expect(await result.blob.text()).toBe("workbook");
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/sales-parts/export?columns=bsn_part_nm&columns=ldr_nm&ids=42&ids=77",
    );
  });
  it("routes partner exports to the literal backend path and preserves server field errors", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          success: false,
          code: "INVALID_INPUT",
          field: "columns",
          message: "열을 확인하세요.",
          requestId: "partner-export-request",
        }),
        { status: 400 },
      ),
    );
    vi.stubGlobal("fetch", fetcher);
    await expect(
      client.downloadPartnerFile(
        "partners",
        { kind: "VEND", useYn: "Y", q: "가 나" },
        ["ptnr_nm", "paymentAccount"],
        ["1", "3"],
      ),
    ).rejects.toMatchObject({
      status: 400,
      field: "columns",
      code: "INVALID_INPUT",
      requestId: "partner-export-request",
    });
    expect(fetcher).toHaveBeenCalledOnce();
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/partners/export?kind=VEND&useYn=Y&q=%EA%B0%80+%EB%82%98&columns=ptnr_nm&columns=paymentAccount&ids=1&ids=3",
    );
  });
  it("routes the shared sales-part download to its existing backend without inventing a wildcard endpoint", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          success: false,
          message: "선택을 확인하세요.",
          field: "ids",
          code: "INVALID_INPUT",
        }),
        { status: 400, headers: { "X-Request-Id": "sales-export-request" } },
      ),
    );
    vi.stubGlobal("fetch", fetcher);
    await expect(
      client.downloadPartnerFile(
        "sales-parts",
        {},
        ["bsn_part_nm", "bsn_fee_amt"],
        ["2", "4"],
      ),
    ).rejects.toMatchObject({
      status: 400,
      field: "ids",
      requestId: "sales-export-request",
    });
    expect(fetcher).toHaveBeenCalledOnce();
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/sales-parts/export?columns=bsn_part_nm&columns=bsn_fee_amt&ids=2&ids=4",
    );
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
