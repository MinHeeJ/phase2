import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import ClosingWorkspace from "./ClosingWorkspace";
import type { Closing } from "../apiops/closingClient";

const codes = [
  ["SALARY_UPLOAD", "급여 업로드"],
  ["MM_OVER", "MM 1.0 초과자"],
  ["DISPATCH_ATT", "파견 근태"],
  ["BILL_UNCONFIRMED", "청구 미확정"],
  ["TAX_INVOICE_NO", "세금계산서 번호 누락"],
  ["FIN_ENTRY", "재무 항목"],
  ["PART_SETTLE", "파트 정산"],
  ["PAYMENT", "지급 미처리"],
  ["BILL_MISSING", "청구 누락"],
].map(([code, name]) => ({
  code,
  name,
  attr1: null,
  attr2: null,
  attr3: null,
}));
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let closing: Closing;
let fetcher: ReturnType<typeof vi.fn>;
let failure: string | null;
beforeEach(() => {
  sessionStorage.clear();
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-30T00:00:00Z"));
  closing = {
    month: "2026-10",
    status: "OPEN",
    canReopen: false,
    checks: codes.map((c) => ({
      code: c.code,
      status: "OK",
      count: 0,
      description: "정상",
    })),
  };
  failure = null;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/codes/CLOSE_CHECK") return response(codes);
    if (url.startsWith("/api/month-close?")) return response([]);
    if (url.startsWith("/api/closing?")) return response(closing);
    if (url.startsWith("/api/closing/history?"))
      return response({ items: [], page: 0, size: 20, total: 0 });
    if (url.startsWith("/api/closing/preview?"))
      return response({
        month: "2026-10",
        profit: 10,
        cost: 20,
        tax: 30,
        cash: 40,
        warnings: [],
      });
    if (url.startsWith("/api/closing/adjustments?"))
      return response({ items: [], page: 0, size: 20, total: 0 });
    if (init?.method === "POST") {
      if (failure)
        return new Response(
          JSON.stringify({
            success: false,
            code: failure,
            message: "마감 상태를 확인하세요.",
            field: failure === "REASON" ? "reason" : "month",
            requestId: "closing-write",
          }),
          { status: 409 },
        );
      if (url === "/api/closing/confirm")
        closing = { ...closing, status: "CLOSED", canReopen: true };
      if (url === "/api/closing/reopen")
        closing = { ...closing, status: "OPEN", canReopen: false };
      return response(closing);
    }
    throw new Error(`Unexpected URL ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
async function open() {
  render(
    <MemoryRouter initialEntries={["/UI-14"]}>
      <ClosingWorkspace />
    </MemoryRouter>,
  );
  await screen.findByRole("heading", { name: "마감 점검" });
}
async function dialog() {
  fireEvent.click(screen.getByTestId("closing-confirm-button"));
  return screen.findByTestId("closing-operation-dialog");
}
const posts = () =>
  fetcher.mock.calls.filter(([, init]) => init?.method === "POST");
describe("closing commands composed into existing UI-14", () => {
  it("reads without any mutation and renders empty history", async () => {
    await open();
    await screen.findByText("마감 이력이 없습니다.");
    expect(posts()).toHaveLength(0);
  });
  it("runs explicit recheck then refreshes stored monthly results", async () => {
    await open();
    fireEvent.click(screen.getByTestId("closing-recheck-button"));
    await screen.findByText("점검 결과를 저장했습니다.");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/closing/check?month=2026-10",
      expect.objectContaining({ method: "POST" }),
    );
    expect(posts()).toHaveLength(1);
  });
  it("disables closing while unchecked", async () => {
    closing = { ...closing, checks: [] };
    await open();
    expect(screen.getByTestId("closing-confirm-button")).toBeDisabled();
  });
  it("disables closing on FAIL", async () => {
    closing.checks[0].status = "FAIL";
    await open();
    expect(screen.getByTestId("closing-confirm-button")).toBeDisabled();
  });
  it("requires per-request warning acknowledgement and sends Boolean true", async () => {
    closing.checks[1].status = "WARN";
    await open();
    const panel = await dialog();
    await within(panel).findByText("40원");
    expect(screen.getByTestId("closing-dialog-submit-button")).toBeDisabled();
    fireEvent.click(screen.getByTestId("closing-warning-ack-input"));
    fireEvent.click(screen.getByTestId("closing-dialog-submit-button"));
    await screen.findByText("월 마감을 확정했습니다.");
    expect(posts()[0][1].body).toBe(
      JSON.stringify({ month: "2026-10", ackWarnings: true }),
    );
    expect(
      within(screen.getByRole("region", { name: "조회 기간" })).getByText(
        "마감",
      ),
    ).toBeVisible();
  });
  it("shows nine-check summary, snapshot targets, lock warning and scalar preview", async () => {
    await open();
    const panel = await dialog();
    await within(panel).findByText("40원");
    expect(within(panel).getByText(/9종 점검 · OK 9/)).toBeInTheDocument();
    expect(
      within(panel).getByText(/원가 배분·손익 상세·법인세 월할/),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText(/마감한 달의 원천은 수정할 수 없습니다/),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("closing-dialog-submit-button"));
    await screen.findByText("월 마감을 확정했습니다.");
    expect(posts()[0][1].body).toBe(
      JSON.stringify({ month: "2026-10", ackWarnings: false }),
    );
  });
  it("resets WARN acknowledgement when confirmation is cancelled and reopened", async () => {
    closing.checks[1].status = "WARN";
    await open();
    await dialog();
    await screen.findByText("40원");
    fireEvent.click(screen.getByTestId("closing-warning-ack-input"));
    fireEvent.click(screen.getByTestId("closing-dialog-cancel-button"));
    await dialog();
    await screen.findByText("40원");
    expect(screen.getByTestId("closing-warning-ack-input")).not.toBeChecked();
    expect(posts()).toHaveLength(0);
  });
  it("hides reopen for older or referenced closed month", async () => {
    closing = { ...closing, status: "CLOSED", canReopen: false };
    await open();
    expect(
      screen.queryByTestId("closing-reopen-button"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("closing-recheck-button"),
    ).not.toBeInTheDocument();
  });
  it("requires trimmed reason for latest closed month and refreshes on success", async () => {
    closing = { ...closing, status: "CLOSED", canReopen: true };
    await open();
    fireEvent.click(screen.getByTestId("closing-reopen-button"));
    fireEvent.click(screen.getByTestId("closing-dialog-submit-button"));
    expect(
      await screen.findByText("해제 사유를 1~500자로 입력하세요."),
    ).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
    fireEvent.change(screen.getByTestId("closing-reopen-reason-input"), {
      target: { value: "  정정 사유  " },
    });
    fireEvent.click(screen.getByTestId("closing-dialog-submit-button"));
    await screen.findByText("마감을 해제했습니다.");
    expect(
      within(screen.getByRole("region", { name: "조회 기간" })).getByText(
        "작업중",
      ),
    ).toBeVisible();
    expect(posts()[0][1].body).toBe(
      JSON.stringify({ month: "2026-10", reason: "정정 사유" }),
    );
  });
  it("keeps reopen reason after a business conflict", async () => {
    closing = { ...closing, status: "CLOSED", canReopen: true };
    failure = "ADJUSTMENT_REFERENCE_EXISTS";
    await open();
    fireEvent.click(screen.getByTestId("closing-reopen-button"));
    fireEvent.change(screen.getByTestId("closing-reopen-reason-input"), {
      target: { value: "정정 사유" },
    });
    fireEvent.click(screen.getByTestId("closing-dialog-submit-button"));
    await screen.findAllByText("마감 상태를 확인하세요.");
    expect(screen.getByTestId("closing-reopen-reason-input")).toHaveValue(
      "정정 사유",
    );
  });
  it("prevents duplicate mutations during a pending confirmation", async () => {
    await open();
    await dialog();
    await screen.findByText("40원");
    const original = fetcher.getMockImplementation()!;
    let complete!: (value: Response) => void;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      url === "/api/closing/confirm"
        ? new Promise<Response>((resolve) => {
            complete = resolve;
          })
        : original(url, init),
    );
    fireEvent.click(screen.getByTestId("closing-dialog-submit-button"));
    fireEvent.click(screen.getByTestId("closing-dialog-submit-button"));
    expect(screen.getByTestId("closing-dialog-submit-button")).toBeDisabled();
    expect(screen.getByTestId("closing-dialog-cancel-button")).toBeDisabled();
    expect(posts()).toHaveLength(1);
    complete(response(closing));
    await screen.findByText("월 마감을 확정했습니다.");
  });
  it("mobile keeps read content but hides every mutation control", async () => {
    window.innerWidth = 375;
    await open();
    await screen.findByText("마감 이력이 없습니다.");
    expect(
      screen.queryByTestId("closing-confirm-button"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("closing-recheck-button"),
    ).not.toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });
  it("handles preview failure without enabling confirmation", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      url.startsWith("/api/closing/preview?")
        ? Promise.resolve(
            new Response(
              JSON.stringify({
                success: false,
                code: "OPENING_BALANCE_REQUIRED",
                message: "기초 잔액 필요",
              }),
              { status: 409 },
            ),
          )
        : original(url, init),
    );
    await open();
    await dialog();
    await screen.findAllByText("기초 잔액 필요");
    expect(screen.getByTestId("closing-dialog-submit-button")).toBeDisabled();
    expect(posts()).toHaveLength(0);
  });
  it("handles failed history separately without fabricating an empty success", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      url.startsWith("/api/closing/history?")
        ? Promise.resolve(
            new Response(
              JSON.stringify({ success: false, message: "이력 조회 실패" }),
              { status: 500 },
            ),
          )
        : original(url, init),
    );
    await open();
    await screen.findByText("이력 조회 실패");
    expect(screen.queryByText("마감 이력이 없습니다.")).not.toBeInTheDocument();
  });
  it("uses year-only pagination without inventing a month query", async () => {
    await open();
    await screen.findByText("마감 이력이 없습니다.");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/closing/history?year=2026&page=0&size=20",
      expect.anything(),
    );
  });
  it("composes existing adjustments instead of creating another route", async () => {
    await open();
    fireEvent.click(screen.getByTestId("closing-adjustments-tab"));
    await screen.findByTestId("closing-adjustments-panel");
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) =>
          url.startsWith("/api/closing/adjustments?"),
        ),
      ).toBe(true),
    );
  });
});
