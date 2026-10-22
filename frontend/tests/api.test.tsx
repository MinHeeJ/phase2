import { useState } from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiRequestError } from "../src/services/api";
import { ErrorBanner } from "../src/components/ErrorBanner";

afterEach(() => vi.unstubAllGlobals());
describe("서버 오류와 연결 실패", () => {
  it("성공 data는 중복 봉투 없이 반환", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({ success: true, meta: {}, data: ["value"] }),
            { status: 200 },
          ),
        ),
    );
    expect(await api("/api/menus")).toEqual(["value"]);
  });
  it("서버 field/requestId를 보존", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "VALIDATION_ERROR",
            field: "[0].cd_nm",
            message: "이름을 입력하세요.",
            requestId: "server-123",
          }),
          { status: 400, headers: { "X-Request-Id": "header-123" } },
        ),
      ),
    );
    await expect(api("/api/admin/code-groups")).rejects.toMatchObject({
      requestId: "server-123",
      field: "[0].cd_nm",
      code: "VALIDATION_ERROR",
    });
  });
  it("본문 ID가 없으면 서버 헤더 사용", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("{}", {
          status: 500,
          headers: { "X-Request-Id": "header-123" },
        }),
      ),
    );
    await expect(api("/api/menus")).rejects.toMatchObject({
      requestId: "header-123",
    });
  });
  it("연결 실패는 가짜 서버 번호를 생성하지 않음", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    await expect(api("/api/menus")).rejects.toMatchObject({ requestId: null });
  });
  it("연결 실패 배너/재조회는 입력 draft를 보존하며 쓰기 재전송 없음", () => {
    const retry = vi.fn();
    function Editor() {
      const [value, setValue] = useState("편집 중");
      return (
        <>
          <input
            aria-label="이름"
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
          <ErrorBanner
            error={new ApiRequestError("연결 실패", null)}
            onRetry={retry}
          />
        </>
      );
    }
    render(<Editor />);
    fireEvent.change(screen.getByLabelText("이름"), {
      target: { value: "새 이름" },
    });
    fireEvent.click(screen.getByText("다시 시도"));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "문의 번호: 서버 연결 전 실패",
    );
    expect(screen.getByLabelText("이름")).toHaveValue("새 이름");
    expect(retry).toHaveBeenCalledOnce();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
  it("서버 문의번호 표시", () => {
    render(
      <ErrorBanner
        error={new ApiRequestError("서버 오류", "server-123")}
        onRetry={vi.fn()}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "문의 번호: server-123",
    );
  });
  it("쓰기 요청은 연결 실패에 자동 재전송하지 않음", async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError("network"));
    vi.stubGlobal("fetch", fetch);
    await expect(
      api("/api/admin/code-groups", { method: "POST", body: "{}" }),
    ).rejects.toBeInstanceOf(ApiRequestError);
    expect(fetch).toHaveBeenCalledOnce();
  });
  it("절대 URL 금지", async () => {
    await expect(api("https://other.example/api/menus")).rejects.toThrow(
      "상대 API",
    );
  });
});
