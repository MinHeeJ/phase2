import { afterEach, describe, expect, it, vi } from "vitest";
import {
  api,
  ApiRequestError,
  adminApi,
  getMenus,
  getMonthClose,
  getCodeOptions,
  getEffectiveParam,
  getHealth,
  useCodes,
} from "../src/api/apiClient";
import * as transport from "../src/services/api";
import * as shared from "../src/pages/shared/api";
import { useCodes as sharedUseCodes } from "../src/pages/shared/useCodes";
import { adminApi as featureAdminApi } from "../src/pages/admin/adminApi";

const operations = [
  {
    name: "getMenus",
    run: () => getMenus(),
    path: "/api/menus",
    method: "GET",
  },
  {
    name: "getMonthClose",
    run: () => getMonthClose("0001-01", "9999-12"),
    path: "/api/month-close?from=0001-01&to=9999-12",
    method: "GET",
  },
  {
    name: "getCodeOptions",
    run: () => getCodeOptions("A/B"),
    path: "/api/codes/A%2FB",
    method: "GET",
  },
  {
    name: "getEffectiveParam",
    run: () => getEffectiveParam("A/B", "2026-11"),
    path: "/api/params/A%2FB?month=2026-11",
    method: "GET",
  },
  {
    name: "getHealth",
    run: () => getHealth(),
    path: "/api/health",
    method: "GET",
  },
  {
    name: "listCodeGroups",
    run: () => adminApi.listCodeGroups(),
    path: "/api/admin/code-groups?page=1&size=100",
    method: "GET",
  },
  {
    name: "listManagedCodes",
    run: () => adminApi.listManagedCodes("A/B"),
    path: "/api/admin/code-groups/A%2FB/codes?page=1&size=100",
    method: "GET",
  },
  {
    name: "saveGroupCodes",
    run: () => adminApi.saveGroupCodes("A/B", []),
    path: "/api/admin/code-groups/A%2FB/codes",
    method: "PUT",
    body: [],
  },
  {
    name: "createCodeGroup",
    run: () =>
      adminApi.createCodeGroup({
        cd_grp_id: "NEW_GROUP",
        cd_grp_nm: "신규",
        attr1_nm: null,
        attr2_nm: null,
        attr3_nm: null,
      }),
    path: "/api/admin/code-groups",
    method: "POST",
    body: { cd_grp_id: "NEW_GROUP", cd_grp_nm: "신규" },
  },
  {
    name: "listExcelMaps",
    run: () => adminApi.listExcelMaps(2, 20),
    path: "/api/admin/excel-maps?page=2&size=20",
    method: "GET",
  },
  {
    name: "createExcelMap",
    run: () =>
      adminApi.createExcelMap({
        excel_lbl_nm: "테스트표기",
        trns_cd_grp_id: "JOB",
        trns_cd: "BE",
        map_role_cd: "SUB",
      }),
    path: "/api/admin/excel-maps",
    method: "POST",
    body: {
      excel_lbl_nm: "테스트표기",
      trns_cd_grp_id: "JOB",
      trns_cd: "BE",
      map_role_cd: "SUB",
    },
  },
  {
    name: "updateExcelMap",
    run: () => adminApi.updateExcelMap("11", { use_yn: "N", rmrk_desc: "" }),
    path: "/api/admin/excel-maps/11",
    method: "PATCH",
    body: { use_yn: "N", rmrk_desc: "" },
  },
  {
    name: "listCodeHistory",
    run: () => adminApi.listCodeHistory("A/B", 2, 50),
    path: "/api/admin/code-history?page=2&size=50&group=A%2FB",
    method: "GET",
  },
  {
    name: "listCurrentParams",
    run: () => adminApi.listCurrentParams(1, 100),
    path: "/api/admin/params?page=1&size=100",
    method: "GET",
  },
  {
    name: "listParamVersions",
    run: () => adminApi.listParamVersions("A/B"),
    path: "/api/admin/params/A%2FB/versions?page=1&size=100",
    method: "GET",
  },
  {
    name: "saveParamVersion",
    run: () =>
      adminApi.saveParamVersion("A/B", "2026-11", { num_val: "10.1234" }),
    path: "/api/admin/params/A%2FB/versions/2026-11",
    method: "PUT",
    body: { num_val: 10.1234 },
  },
];
afterEach(() => vi.unstubAllGlobals());

describe("API 클라이언트 등록", () => {
  it("기존 feature와 transport를 그대로 노출하여 역방향 import를 만들지 않음", () => {
    expect(api).toBe(transport.api);
    expect(ApiRequestError).toBe(transport.ApiRequestError);
    expect(adminApi).toBe(featureAdminApi);
    expect(useCodes).toBe(sharedUseCodes);
    expect(getMenus).toBe(shared.getMenus);
    expect(getMonthClose).toBe(shared.getMonthClose);
    expect(getCodeOptions).toBe(shared.getCodeOptions);
    expect(getEffectiveParam).toBe(shared.getEffectiveParam);
    expect(getHealth).toBe(shared.getHealth);
  });
  it.each(operations)(
    "$name은 기존 메서드/상대 경로/본문을 보존",
    async (operation) => {
      const data = operation.path.includes("size=100")
        ? { items: [], total: 0, page: 1, size: 100 }
        : [];
      const fetcher = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ success: true, meta: {}, data }), {
          status: 200,
        }),
      );
      vi.stubGlobal("fetch", fetcher);
      await operation.run();
      expect(fetcher).toHaveBeenCalledOnce();
      const [path, options] = fetcher.mock.calls[0] as [string, RequestInit];
      expect(path).toBe(operation.path);
      expect(options.method ?? "GET").toBe(operation.method);
      if ("body" in operation)
        expect(JSON.parse(options.body as string)).toEqual(operation.body);
      else expect(options.body).toBeUndefined();
      expect(new Headers(options.headers).has("Authorization")).toBe(false);
    },
  );
  it("서버 오류는 같은 클래스와 field/requestId로 전달", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "SYSTEM_CODE_LOCKED",
            message: "시스템 코드는 사용 중지할 수 없습니다.",
            field: "[0].use_yn",
            requestId: "server-123",
          }),
          { status: 409 },
        ),
      ),
    );
    await expect(
      adminApi.saveGroupCodes("CONTRACT_TYPE", []),
    ).rejects.toBeInstanceOf(ApiRequestError);
  });
  it.each(operations.filter((operation) => operation.method !== "GET"))(
    "$name 연결 실패시 자동 쓰기 재전송 없음",
    async (operation) => {
      const fetcher = vi.fn().mockRejectedValue(new TypeError("network"));
      vi.stubGlobal("fetch", fetcher);
      await expect(operation.run()).rejects.toMatchObject({
        name: "ApiRequestError",
        requestId: null,
      });
      expect(fetcher).toHaveBeenCalledOnce();
    },
  );
});
