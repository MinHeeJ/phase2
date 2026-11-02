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
import { AdjustmentPanel } from "./AdjustmentPanel";
import type { AdjustmentRow } from "./client";
import tables from "../developers/Developers.module.css";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let rows: AdjustmentRow[];
let fetcher: ReturnType<typeof vi.fn>;
let closed: boolean, failRead: boolean, failWrite: boolean;
function row(
  id: number,
  applyMonth = "2026-10",
  status: "ACTIVE" | "CANCELLED" = "ACTIVE",
): AdjustmentRow {
  return {
    id,
    originalMonth: "2026-08",
    applyMonth,
    type: "REVENUE",
    amount: 100,
    reason: "원본 사유",
    status,
    createdAt: "2026-10-29T09:00:00+09:00",
  };
}
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-29T00:00:00Z"));
  rows = [row(7)];
  closed = false;
  failRead = false;
  failWrite = false;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") {
      if (failWrite)
        return new Response(
          JSON.stringify({
            success: false,
            code: "CLOSED_MONTH",
            field: "reason",
            message: "마감 상태가 변경되었습니다.",
            requestId: "adjustment-request",
          }),
          { status: 409 },
        );
      const body = JSON.parse(String(init.body));
      if (url.endsWith("/cancel")) {
        const selected = rows.find((r) => url.includes(`/${r.id}/cancel`))!;
        selected.status = "CANCELLED";
        selected.cancelledAt = "2026-10-29T09:01:00+09:00";
        return response(selected);
      }
      const created = {
        ...body,
        id: Math.max(0, ...rows.map((r) => r.id)) + 1,
        status: "ACTIVE",
      };
      rows.unshift(created);
      return response(created);
    }
    if (url.startsWith("/api/month-close"))
      return response([
        { month: "2026-08", status: "CLOSED" },
        { month: "2026-09", status: "CLOSED" },
        ...(closed ? [{ month: "2026-10", status: "CLOSED" }] : []),
      ]);
    if (failRead)
      return new Response(
        JSON.stringify({
          success: false,
          code: "INTERNAL_ERROR",
          message: "조회 실패",
          requestId: "adjustment-read",
        }),
        { status: 500 },
      );
    const query = new URL(url, "https://test.invalid").searchParams;
    const filtered = rows.filter(
      (r) =>
        r.applyMonth.startsWith(query.get("year")!) &&
        (!query.get("month") || r.applyMonth === query.get("month")),
    );
    const page = Number(query.get("page")),
      size = Number(query.get("size"));
    return response({
      items: filtered.slice(page * size, (page + 1) * size),
      page,
      size,
      total: filtered.length,
    });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
const mount = (month = "2026-10", refresh?: () => Promise<unknown>) =>
  render(
    <MemoryRouter>
      <AdjustmentPanel month={month} onRefresh={refresh} />
    </MemoryRouter>,
  );
const posts = () =>
  fetcher.mock.calls.filter(([, init]) => init?.method === "POST");
async function openCreate() {
  fireEvent.click(await screen.findByRole("button", { name: "조정 등록" }));
  return screen.getByRole("dialog", { name: "전월 조정 등록" });
}
function fillCreate(amount = "100", reason = "등록 사유") {
  fireEvent.change(screen.getByLabelText("원월"), {
    target: { value: "2026-08" },
  });
  fireEvent.change(screen.getByLabelText("금액"), {
    target: { value: amount },
  });
  fireEvent.change(screen.getByLabelText("사유"), {
    target: { value: reason },
  });
}
describe("UI-14 전월 조정 패널", () => {
  it("renders API rows and shared table styles without a separate route", async () => {
    mount();
    await screen.findByText("원본 사유");
    expect(screen.getByRole("table").className).toBe(tables.table);
    expect(screen.getByText("유효")).toBeInTheDocument();
    expect(
      fetcher.mock.calls.some(
        ([url]) =>
          url.includes("year=2026") &&
          url.includes("month=2026-10") &&
          url.includes("page=0") &&
          url.includes("size=20"),
      ),
    ).toBe(true);
  });
  it("shows empty state from the API", async () => {
    rows = [];
    mount();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(screen.getByText("총 0건 · 단위: 원")).toBeInTheDocument();
  });
  it("shows loading while API is pending", () => {
    fetcher.mockImplementation(() => new Promise(() => {}));
    mount();
    expect(screen.getByText("불러오는 중")).toBeInTheDocument();
  });
  it("shows read failure and permits retry", async () => {
    failRead = true;
    mount();
    await screen.findByText("조회 실패");
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("원본 사유")).toBeInTheDocument();
  });
  it("locks applyMonth to current month and posts only allowed create fields", async () => {
    const refresh = vi.fn(async () => {});
    mount("2026-10", refresh);
    await openCreate();
    fillCreate("70", "  정상 사유  ");
    expect(screen.getByLabelText("반영월")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("반영월")).toHaveValue("2026-10");
    fireEvent.click(screen.getByRole("button", { name: /^등록$/ }));
    await screen.findByText("조정을 등록했습니다.");
    expect(JSON.parse(String(posts()[0][1]?.body))).toEqual({
      originalMonth: "2026-08",
      applyMonth: "2026-10",
      type: "REVENUE",
      amount: 70,
      reason: "정상 사유",
    });
    expect(refresh).toHaveBeenCalledTimes(1);
  });
  it("offers only DB closed original months", async () => {
    mount();
    await openCreate();
    const original = screen.getByLabelText("원월");
    expect(
      within(original).getByRole("option", { name: "2026-08" }),
    ).toBeInTheDocument();
    expect(
      within(original).queryByRole("option", { name: "2026-10" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /^등록$/ }));
    expect(
      screen.getByText("과거 CLOSED 원월을 선택하세요."),
    ).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });
  it.each(["0", "1.5", "abc", "9007199254740992"])(
    "rejects invalid amount %s",
    async (amount) => {
      mount();
      await openCreate();
      fillCreate(amount);
      fireEvent.click(screen.getByRole("button", { name: /^등록$/ }));
      expect(
        screen.getByText("금액은 0이 아닌 정수로 입력하세요."),
      ).toBeInTheDocument();
      expect(posts()).toHaveLength(0);
    },
  );
  it("requires nonblank reason before create", async () => {
    mount();
    await openCreate();
    fillCreate("100", " ");
    fireEvent.click(screen.getByRole("button", { name: /^등록$/ }));
    expect(
      screen.getByText("사유를 1~500자로 입력하세요."),
    ).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });
  it("keeps failed create input and renders API field error", async () => {
    failWrite = true;
    mount();
    await openCreate();
    fillCreate("70", "입력 유지");
    fireEvent.click(screen.getByRole("button", { name: /^등록$/ }));
    await screen.findByText("마감 상태가 변경되었습니다.");
    expect(screen.getByLabelText("사유")).toHaveValue("입력 유지");
    expect(screen.getByLabelText("사유")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });
  it("confirms cancellation with a trimmed mandatory reason and refreshes status", async () => {
    mount();
    await screen.findByText("원본 사유");
    fireEvent.click(screen.getByTestId("adjustment-cancel-7"));
    fireEvent.click(screen.getByRole("button", { name: /^조정 취소$/ }));
    expect(posts()).toHaveLength(0);
    expect(
      await screen.findByText("취소 사유를 1~500자로 입력하세요."),
    ).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("취소 사유"), {
      target: { value: "  잘못 입력  " },
    });
    fireEvent.click(screen.getByRole("button", { name: /^조정 취소$/ }));
    await screen.findByText("조정을 취소했습니다.");
    expect(posts()[0][0]).toBe("/api/closing/adjustments/7/cancel");
    expect(JSON.parse(String(posts()[0][1]?.body))).toEqual({
      reason: "잘못 입력",
    });
    expect(screen.queryByTestId("adjustment-cancel-7")).not.toBeInTheDocument();
    expect(screen.getByText("원본 사유")).toBeInTheDocument();
  });
  it("keeps cancellation reason after server rejects changed state", async () => {
    failWrite = true;
    mount();
    fireEvent.click(await screen.findByTestId("adjustment-cancel-7"));
    fireEvent.change(screen.getByLabelText("취소 사유"), {
      target: { value: "사유 보존" },
    });
    fireEvent.click(screen.getByRole("button", { name: /^조정 취소$/ }));
    await screen.findByText("마감 상태가 변경되었습니다.");
    expect(screen.getByLabelText("취소 사유")).toHaveValue("사유 보존");
  });
  it("preserves cancelled rows and details but hides duplicate cancel", async () => {
    rows = [row(7, "2026-10", "CANCELLED")];
    mount();
    await screen.findByText("원본 사유");
    expect(screen.queryByTestId("adjustment-cancel-7")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "상세" }));
    expect(
      screen.getByRole("dialog", { name: "전월 조정 상세" }),
    ).toBeInTheDocument();
  });
  it("hides cancellation for closed apply month and creates ordinary linked correction", async () => {
    rows = [row(7, "2026-09")];
    mount("2026-09");
    await screen.findByText("원본 사유");
    expect(screen.queryByTestId("adjustment-cancel-7")).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "정정 등록" }));
    expect(
      screen.getByText(/역방향 금액과 정상 금액을 각각 별도 등록/),
    ).toBeInTheDocument();
    fillCreate("-100", "역방향 정정");
    fireEvent.click(screen.getByRole("button", { name: /^등록$/ }));
    await screen.findByText("조정을 등록했습니다.");
    expect(JSON.parse(String(posts()[0][1]?.body))).toMatchObject({
      originalAdjustmentId: 7,
      amount: -100,
      applyMonth: "2026-10",
    });
    expect(rows.find((r) => r.id === 7)?.status).toBe("ACTIVE");
  });
  it("hides mutation when current month is CLOSED", async () => {
    closed = true;
    mount();
    await screen.findByText("원본 사유");
    expect(
      screen.queryByRole("button", { name: "조정 등록" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId("adjustment-cancel-7")).not.toBeInTheDocument();
  });
  it("mobile keeps read-only rows and details but hides mutation", async () => {
    window.innerWidth = 767;
    mount();
    await screen.findByText("원본 사유");
    expect(screen.getByText(/조회 전용입니다/)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "조정 등록" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId("adjustment-cancel-7")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "상세" }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
  it("uses server total for paging and resets to zero for year filter", async () => {
    rows = Array.from({ length: 23 }, (_, i) => row(i + 1));
    rows.push(row(50, "2026-09"));
    mount();
    await screen.findByText("총 23건 · 단위: 원");
    fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));
    await waitFor(() =>
      expect(screen.getAllByTestId(/^adjustment-row-/)).toHaveLength(3),
    );
    expect(fetcher.mock.calls.some(([url]) => url.includes("page=1"))).toBe(
      true,
    );
    fireEvent.click(screen.getByLabelText("선택 연도 전체"));
    await screen.findByText("총 24건 · 단위: 원");
    const latest = fetcher.mock.calls
      .filter(([url]) => url.startsWith("/api/closing/adjustments?"))
      .at(-1)![0];
    expect(latest).not.toContain("month=");
    expect(latest).toContain("page=0");
  });
  it("reloads selected month without stale old-month rows", async () => {
    rows.push(row(8, "2026-09"));
    const view = mount();
    await screen.findByTestId("adjustment-row-7");
    view.rerender(
      <MemoryRouter>
        <AdjustmentPanel month="2026-09" />
      </MemoryRouter>,
    );
    await screen.findByTestId("adjustment-row-8");
    expect(screen.queryByTestId("adjustment-row-7")).not.toBeInTheDocument();
  });
});
