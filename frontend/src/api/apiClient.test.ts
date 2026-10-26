import { afterEach, describe, expect, it, vi } from "vitest";
import * as client from "./apiClient";
import * as shared from "../pages/shared/sharedApi";
import * as admin from "../pages/admin/adminApi";
import { ApiFailure } from "../shared/http";

const envelope = (data: unknown) =>
  new Response(
    JSON.stringify({
      success: true,
      meta: {},
      data: { success: true, meta: {}, data },
    }),
  );
afterEach(() => vi.unstubAllGlobals());
describe("API 클라이언트 등록", () => {
  it.each([
    "getMenus",
    "getMonthClose",
    "getHealth",
    "getCodeOptions",
    "getEffectiveParam",
    "useCodes",
  ] as const)("%s는 기존 공유 구현을 그대로 노출한다", (name) => {
    expect(client[name]).toBe(shared[name]);
  });
  it.each([
    "listCodeGroups",
    "listManagedCodes",
    "saveGroupCodes",
    "createCodeGroup",
    "listExcelMaps",
    "createExcelMap",
    "updateExcelMap",
    "listCodeHistory",
    "listCurrentParams",
    "listParamVersions",
    "saveParamVersion",
    "collectAdminPages",
  ] as const)("%s는 기존 관리 구현을 그대로 노출한다", (name) => {
    expect(client[name]).toBe(admin[name]);
  });
  it("공유 조회 결과를 추가로 벗기거나 변형하지 않는다", async () => {
    const data = [{ month: "2026-11", status: "OPEN" }];
    const fetcher = vi.fn().mockResolvedValue(envelope(data));
    vi.stubGlobal("fetch", fetcher);
    expect(await client.getMonthClose("2026-11", "2026-12")).toEqual(data);
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/month-close?from=2026-11&to=2026-12",
    );
  });
  it("관리 조회는 선택한 그룹과 두 겹의 조회 봉투를 보존한다", async () => {
    const data = { items: [], total: 0, page: 0, size: 50 };
    const fetcher = vi.fn().mockResolvedValue(envelope(data));
    vi.stubGlobal("fetch", fetcher);
    expect(await client.listManagedCodes("선택한 그룹")).toEqual(data);
    expect(fetcher.mock.calls[0][0]).toBe(
      `/api/admin/code-groups/${encodeURIComponent("선택한 그룹")}/codes?page=0&size=50`,
    );
  });
  it("저장은 선택한 문자열 map_id와 단일 성공 봉투를 사용한다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ success: true, meta: {} })),
      );
    vi.stubGlobal("fetch", fetcher);
    const selected = { map_id: "9007199254740993" };
    await expect(
      client.updateExcelMap(selected.map_id, { rmrk_desc: "" }),
    ).resolves.toBeUndefined();
    expect(fetcher.mock.calls[0][0]).toBe(
      `/api/admin/excel-maps/${selected.map_id}`,
    );
    expect(fetcher.mock.calls[0][1]).toMatchObject({
      method: "PATCH",
      body: '{"rmrk_desc":""}',
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("오류의 단일 field와 서버 문의 번호를 같은 ApiFailure로 전달하고 재전송하지 않는다", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          success: false,
          meta: {},
          code: "VALIDATION_ERROR",
          field: "[0].cd_nm",
          message: "입력값을 확인하세요.",
        }),
        { status: 400, headers: { "X-Request-Id": "request-1" } },
      ),
    );
    vi.stubGlobal("fetch", fetcher);
    expect(client.ApiFailure).toBe(ApiFailure);
    await expect(
      client.saveGroupCodes("선택한 그룹", []),
    ).rejects.toMatchObject({
      status: 400,
      field: "[0].cd_nm",
      requestId: "request-1",
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
