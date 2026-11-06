import { useState } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiFailure, retryRecovery } from "../src/shared/api";
import { ErrorBanner } from "../src/shared/ErrorBanner";
import { createUploadValidation } from "../src/pages/uploadsops/client";
import { validateFinanceEntriesUpload } from "../src/pages/financeentries/client";
import { validatePriorProfitUpload } from "../src/pages/priorprofit/client";
import { validateBusinessPlanUpload } from "../src/pages/businessplan2/client";
import { validateDispatchAttendanceUpload } from "../src/pages/dispatchattendance2/client";

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; Max-Age=0; Path=/";
  vi.unstubAllGlobals();
});
describe("API failure recovery", () => {
  it("uses the current session CSRF cookie for writes without caching or replay", async () => {
    const fetcher = vi.fn(
      async (_path: string, _init?: RequestInit) =>
        new Response(JSON.stringify({ success: true, meta: {} })),
    );
    vi.stubGlobal("fetch", fetcher);
    const first = "a".repeat(43),
      rotated = "b".repeat(43);
    document.cookie = `XSRF-TOKEN=${first}; Path=/`;
    await api("/api/admin/excel-maps/9", { method: "PATCH", body: "{}" });
    document.cookie = `XSRF-TOKEN=${rotated}; Path=/`;
    await api("/api/admin/excel-maps/9", { method: "PATCH", body: "{}" });
    await api("/api/menus");
    expect(fetcher).toHaveBeenCalledTimes(3);
    expect(fetcher.mock.calls[0][1]?.headers).toMatchObject({
      "X-CSRF-TOKEN": first,
      "Content-Type": "application/json",
    });
    expect(fetcher.mock.calls[1][1]?.headers).toMatchObject({
      "X-CSRF-TOKEN": rotated,
    });
    expect(fetcher.mock.calls[2][1]?.headers).not.toHaveProperty(
      "X-CSRF-TOKEN",
    );
  });
  it("preserves the explicit CSRF proof supplied by the authentication client", async () => {
    const fetcher = vi.fn(
      async (_path: string, _init?: RequestInit) =>
        new Response(JSON.stringify({ success: true, meta: {} })),
    );
    vi.stubGlobal("fetch", fetcher);
    document.cookie = `XSRF-TOKEN=${"a".repeat(43)}; Path=/`;
    await api("/api/auth/login", {
      method: "POST",
      headers: { "X-CSRF-TOKEN": "b".repeat(43) },
    });
    expect(fetcher.mock.calls[0][1]?.headers).toMatchObject({
      "X-CSRF-TOKEN": "b".repeat(43),
    });
  });
  it.each([
    ["personnel", (file: File) => createUploadValidation("B.8", file)],
    ["finance entries", validateFinanceEntriesUpload],
    ["prior profit", validatePriorProfitUpload],
    ["business plan", validateBusinessPlanUpload],
    [
      "attendance",
      (file: File) => validateDispatchAttendanceUpload(file, "2026-09", 1),
    ],
  ] as const)(
    "attaches session CSRF without setting a multipart content type: %s",
    async (_, upload) => {
      const fetcher = vi.fn(async (_path: string, init?: RequestInit) => {
        if (init?.method === "POST") {
          expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe(
            "a".repeat(43),
          );
          expect(new Headers(init.headers).has("Content-Type")).toBe(false);
          expect(init.body).toBeInstanceOf(FormData);
        }
        return new Response(
          JSON.stringify({
            success: true,
            meta: {},
            data:
              init?.method === "POST"
                ? { upload_id: 1 }
                : { items: [], total: 0 },
          }),
        );
      });
      vi.stubGlobal("fetch", fetcher);
      document.cookie = `XSRF-TOKEN=${"a".repeat(43)}; Path=/`;
      await upload(new File([""], "upload.xlsx"));
      expect(
        fetcher.mock.calls.filter(([, init]) => init?.method === "POST"),
      ).toHaveLength(1);
    },
  );
  it("uses relative paths and flat data", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ success: true, meta: {}, data: [1] })),
      );
    vi.stubGlobal("fetch", fetcher);
    expect((await api<number[]>("/api/menus")).data).toEqual([1]);
    expect(fetcher).toHaveBeenCalledOnce();
    await expect(api("https://invalid/api")).rejects.toThrow("상대");
  });
  it("uses error requestId and singular field", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "DUPLICATE_CODE",
            message: "중복",
            field: "rows[0].cd_nm",
            requestId: "server-id",
          }),
          { status: 409, headers: { "X-Request-Id": "server-id" } },
        ),
      ),
    );
    await expect(
      api("/api/admin/code-groups", { method: "POST" }),
    ).rejects.toMatchObject({
      requestId: "server-id",
      field: "rows[0].cd_nm",
      status: 409,
    });
  });
  it("uses response header if malformed JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("invalid", {
          status: 500,
          headers: { "X-Request-Id": "header-id" },
        }),
      ),
    );
    await expect(api("/api/health")).rejects.toMatchObject({
      requestId: "header-id",
    });
  });
  it("does not invent a request identifier or replay uncertain writes", async () => {
    const fetcher = vi.fn().mockRejectedValue(new TypeError("network"));
    vi.stubGlobal("fetch", fetcher);
    let error: ApiFailure | null = null;
    try {
      await api("/api/admin/excel-maps", { method: "POST", body: "{}" });
    } catch (caught) {
      error = caught as ApiFailure;
    }
    expect(error).toMatchObject({ requestId: null, uncertainWrite: true });
    expect(fetcher).toHaveBeenCalledOnce();
    const read = vi.fn().mockResolvedValue(undefined);
    await retryRecovery(error!, read);
    expect(read).toHaveBeenCalledOnce();
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it("red banner retry retains edited text with no error toast", () => {
    const retry = vi.fn();
    function Editor() {
      const [value, setValue] = useState("입력");
      return (
        <>
          <input
            aria-label="이름"
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
          <ErrorBanner
            error={new ApiFailure("연결 오류", null, null, null, null, true)}
            onRetry={retry}
          />
        </>
      );
    }
    render(<Editor />);
    fireEvent.change(screen.getByLabelText("이름"), {
      target: { value: "편집 유지" },
    });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "문의 번호 없음 (서버 응답 없음)",
    );
    fireEvent.click(screen.getByText("다시 시도"));
    expect(retry).toHaveBeenCalledOnce();
    expect(screen.getByLabelText("이름")).toHaveValue("편집 유지");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
  it("shows server-issued inquiry number and disables pending retry", () => {
    render(
      <ErrorBanner
        error={new ApiFailure("서버 오류", 500, "server-id")}
        onRetry={() => {}}
        pending
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("문의 번호 server-id");
    expect(screen.getByRole("button")).toBeDisabled();
  });
});
