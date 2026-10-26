import { beforeEach, expect, it, vi } from "vitest";
import {
  collectAdminPages,
  createCodeGroup,
  createExcelMap,
  listCodeGroups,
  listManagedCodes,
  listCodeHistory,
  listCurrentParams,
  listExcelMaps,
  listParamVersions,
  saveGroupCodes,
  saveParamVersion,
  updateExcelMap,
} from "./adminApi";

beforeEach(() => {
  vi.restoreAllMocks();
});
function reply(data: unknown) {
  return new Response(
    JSON.stringify({
      success: true,
      meta: {},
      data: { success: true, meta: {}, data },
    }),
    { status: 200, headers: { "Content-Type": "application/json" } },
  );
}
it("each admin read uses the approved relative path and normalizes exactly two envelopes", async () => {
  const result = { items: [], total: 0, page: 0, size: 50 };
  const fetch = vi.fn().mockImplementation(async () => reply(result));
  vi.stubGlobal("fetch", fetch);
  expect(await listCodeGroups()).toEqual(result);
  await listManagedCodes("선택 그룹");
  await listExcelMaps();
  await listCodeHistory("PARAM");
  await listCurrentParams();
  await listParamVersions("선택 항목");
  expect(fetch.mock.calls.map(([path]) => path)).toEqual([
    "/api/admin/code-groups?page=0&size=50",
    `/api/admin/code-groups/${encodeURIComponent("선택 그룹")}/codes?page=0&size=50`,
    "/api/admin/excel-maps?page=0&size=50",
    "/api/admin/code-history?page=0&size=50&group=PARAM",
    "/api/admin/params?page=0&size=50",
    `/api/admin/params/${encodeURIComponent("선택 항목")}/versions?page=0&size=50`,
  ]);
});
it("writes use selected resource IDs and only a single success envelope", async () => {
  const fetch = vi.fn().mockImplementation(
    async () =>
      new Response(JSON.stringify({ success: true, meta: {} }), {
        status: 200,
      }),
  );
  vi.stubGlobal("fetch", fetch);
  await createCodeGroup({ cd_grp_id: "SELECTED", cd_grp_nm: "선택" });
  await saveGroupCodes("SELECTED", []);
  await createExcelMap({
    excel_lbl_nm: "표기",
    trns_cd_grp_id: "SELECTED",
    trns_cd: "CODE",
    map_role_cd: "MAIN",
  });
  await updateExcelMap("77", { rmrk_desc: "" });
  await saveParamVersion("SELECTED", "2026-11", { num_val: 0 });
  expect(fetch.mock.calls.map((call) => call[1].method)).toEqual([
    "POST",
    "PUT",
    "POST",
    "PATCH",
    "PUT",
  ]);
  expect(fetch.mock.calls[3][0]).toBe("/api/admin/excel-maps/77");
  expect(JSON.parse(fetch.mock.calls[3][1].body)).toEqual({ rmrk_desc: "" });
});
it("collects all pages and refuses an incomplete full-list snapshot", async () => {
  const load = vi
    .fn()
    .mockResolvedValueOnce({ items: ["a"], total: 2 })
    .mockResolvedValueOnce({ items: ["b"], total: 2 });
  expect(await collectAdminPages(load)).toEqual(["a", "b"]);
  expect(load.mock.calls).toEqual([[0], [1]]);
  await expect(
    collectAdminPages(async () => ({ items: [], total: 1, page: 0, size: 50 })),
  ).rejects.toThrow("전체 목록");
});
