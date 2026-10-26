import { describe, expect, it, vi, afterEach } from "vitest";
import { request, query, mutate, ApiFailure, NetworkFailure } from "./http";

afterEach(() => vi.unstubAllGlobals());
describe("공통 HTTP 경계", () => {
  it("알려진 두 조회 봉투만 정규화한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: true,
            meta: {},
            data: { success: true, meta: {}, data: [{ code: "DB" }] },
          }),
        ),
      ),
    );
    expect(await query("/api/menus")).toEqual([{ code: "DB" }]);
  });
  it("업무 객체의 data는 재귀적으로 벗기지 않는다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: true,
            meta: {},
            data: { success: true, meta: {}, data: { data: "업무 필드" } },
          }),
        ),
      ),
    );
    expect(await query("/api/menus")).toEqual({ data: "업무 필드" });
  });
  it("쓰기와 health는 한 봉투를 사용한다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ success: true, meta: {} })),
      );
    vi.stubGlobal("fetch", fetcher);
    await mutate("/api/admin/code-groups", "POST", { cd_grp_id: "NEW" });
    expect(fetcher).toHaveBeenCalledWith(
      "/api/admin/code-groups",
      expect.objectContaining({ method: "POST" }),
    );
  });
  it("오류의 field와 헤더 문의 번호를 보존한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "VALIDATION_ERROR",
            message: "입력값을 확인하세요.",
            field: "[0].cd_nm",
          }),
          { status: 400, headers: { "X-Request-Id": "server-id" } },
        ),
      ),
    );
    try {
      await request("/api/menus");
      throw new Error("Expected failure");
    } catch (error) {
      expect(error).toBeInstanceOf(ApiFailure);
      expect((error as ApiFailure).field).toBe("[0].cd_nm");
      expect((error as ApiFailure).requestId).toBe("server-id");
    }
  });
  it("단절 시 문의 번호를 생성하지 않는다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    await expect(request("/api/health")).rejects.toBeInstanceOf(NetworkFailure);
  });
  it("절대 URL을 호출하지 않는다", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    await expect(request("https://example.invalid/api")).rejects.toThrow(
      "상대 API 경로",
    );
    expect(fetcher).not.toHaveBeenCalled();
  });
});
