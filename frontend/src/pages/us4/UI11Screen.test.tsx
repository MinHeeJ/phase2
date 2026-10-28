import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI11Screen from "./UI11Screen";
import type { PlanComparisonPage } from "./client";

const response = (data: unknown, meta: Record<string, unknown> = {}) =>
  new Response(JSON.stringify({ success: true, data, meta }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let data: PlanComparisonPage;
let fetcher: ReturnType<typeof vi.fn>;
let failRead: boolean;
let paramsMissing: boolean;
let failExport: boolean;
const mount = (props: Parameters<typeof UI11Screen>[0] = {}) =>
  render(
    <MemoryRouter>
      <UI11Screen {...props} />
    </MemoryRouter>,
  );
const listCalls = () =>
  fetcher.mock.calls.filter(([url]) =>
    String(url).startsWith("/api/plan-vs-actual?"),
  );
const lastQuery = () =>
  new URLSearchParams(String(listCalls().at(-1)?.[0]).split("?")[1]);
beforeEach(() => {
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-14T00:00:00Z"));
  failRead = false;
  paramsMissing = false;
  failExport = false;
  data = {
    rows: [
      {
        prj_id: 7,
        prj_no: "P-2607",
        prj_nm: "연간 프로젝트",
        job_cd: "BE",
        job_nm: "백엔드 개발",
        planMm: 3,
        actualMm: 2.5,
        gapMm: -0.5,
        gap: "SHORT",
      },
    ],
    total: 51,
    page: 0,
    size: 50,
    totals: { planMm: 153, actualMm: 127.5 },
  };
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/params")
      return response(
        paramsMissing
          ? []
          : [{ oprt_std_id: "PLAN_GAP_TOLERANCE", num_val: 0.1 }],
      );
    if (url === "/api/codes/JOB")
      return response([{ code: "BE", name: "백엔드 개발" }]);
    if (url.startsWith("/api/plan-vs-actual/export?")) {
      if (failExport)
        return new Response(
          JSON.stringify({
            success: false,
            message: "다운로드 오류",
            field: "year",
          }),
          { status: 400 },
        );
      return new Response(new Uint8Array([80, 75]), {
        headers: {
          "Content-Type":
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent("계획대비투입_2026_20261014.xlsx")}`,
        },
      });
    }
    if (url.startsWith("/api/plan-vs-actual?")) {
      if (failRead)
        return new Response(
          JSON.stringify({
            success: false,
            message: "연도를 확인하세요.",
            field: "year",
            code: "VALIDATION_ERROR",
          }),
          { status: 400 },
        );
      const page = Number(new URLSearchParams(url.split("?")[1]).get("page"));
      return response(
        { ...data, page },
        {
          today: "2026-10-14",
          projects: [{ prj_id: 7, prj_no: "P-2607", prj_nm: "연간 프로젝트" }],
        },
      );
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("annual comparison screen", () => {
  it("renders real contract columns, badges and off-page totals without edits", async () => {
    mount();
    await screen.findByText("부족 0.50");
    expect(
      screen.getByRole("heading", { name: "인원 계획 대비 투입" }),
    ).toBeVisible();
    expect(
      screen.getByRole("columnheader", { name: "2026년 계획 MM" }),
    ).toBeVisible();
    expect(screen.getByText(/차이가 ±0.10 MM/)).toBeVisible();
    const footer = screen.getByTestId("comparison-totals");
    expect(within(footer).getByText("153.00")).toBeVisible();
    expect(within(footer).getByText("127.50")).toBeVisible();
    expect(screen.getByRole("link", { name: "연간 프로젝트" })).toHaveAttribute(
      "href",
      "/UI-01?open=7&tab=staff-plans",
    );
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("keeps filters draft until query and resets them", async () => {
    mount();
    await screen.findByText("부족 0.50");
    const before = listCalls().length;
    fireEvent.change(screen.getByLabelText("차이"), {
      target: { value: "SHORT" },
    });
    fireEvent.change(screen.getByLabelText("프로젝트"), {
      target: { value: "7" },
    });
    fireEvent.change(screen.getByLabelText("직무"), {
      target: { value: "BE" },
    });
    expect(listCalls()).toHaveLength(before);
    fireEvent.click(screen.getByTestId("comparison-query"));
    await waitFor(() => expect(lastQuery().get("gap")).toBe("SHORT"));
    expect(lastQuery().get("projectId")).toBe("7");
    expect(lastQuery().get("job")).toBe("BE");
    fireEvent.click(screen.getByTestId("comparison-reset"));
    await waitFor(() => expect(lastQuery().has("gap")).toBe(false));
    expect(lastQuery().has("job")).toBe(false);
  });
  it("paginates with zero-based API pages and the fixed size of 50", async () => {
    mount();
    await screen.findByText("부족 0.50");
    expect(screen.getByTestId("comparison-previous-page")).toBeDisabled();
    expect(screen.queryByLabelText("목록 건수")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("comparison-next-page"));
    await waitFor(() => expect(lastQuery().get("page")).toBe("1"));
    await waitFor(() =>
      expect(screen.getByTestId("comparison-next-page")).toBeDisabled(),
    );
    expect(lastQuery().get("size")).toBe("50");
    fireEvent.click(screen.getByTestId("comparison-previous-page"));
    await waitFor(() => expect(lastQuery().get("page")).toBe("0"));
    await waitFor(() =>
      expect(screen.getByTestId("comparison-previous-page")).toBeDisabled(),
    );
    expect(lastQuery().get("size")).toBe("50");
  });
  it("sorts through the API and resets the page", async () => {
    mount();
    await screen.findByText("부족 0.50");
    fireEvent.click(screen.getByTestId("comparison-sort-actual-mm"));
    await waitFor(() => expect(lastQuery().get("sort")).toBe("actualMm,asc"));
    fireEvent.click(screen.getByTestId("comparison-sort-actual-mm"));
    await waitFor(() => expect(lastQuery().get("sort")).toBe("actualMm,desc"));
    expect(lastQuery().get("page")).toBe("0");
  });
  it("shows history and next-year badges and persists year without hiding download", async () => {
    mount();
    await screen.findByText("부족 0.50");
    fireEvent.click(screen.getByLabelText("이전 해"));
    await screen.findByText("지난 해 · 읽기 전용");
    expect(lastQuery().get("year")).toBe("2025");
    expect(screen.getByTestId("plan-comparison-download")).toBeEnabled();
    fireEvent.click(screen.getByLabelText("다음 해"));
    await waitFor(() => expect(lastQuery().get("year")).toBe("2026"));
    fireEvent.click(screen.getByLabelText("다음 해"));
    await screen.findByText("계획", { selector: "span" });
    expect(sessionStorage.getItem("UI-11.year")).toBe("2027");
  });
  it("renders an empty result with reset action", async () => {
    data = { ...data, rows: [], total: 0, totals: { planMm: 0, actualMm: 0 } };
    mount();
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    fireEvent.click(screen.getByTestId("comparison-empty-reset"));
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(1));
  });
  it("renders validation failure without stale table and retries safely", async () => {
    failRead = true;
    mount();
    await screen.findByText("year: 연도를 확인하세요.");
    expect(screen.queryByTestId("comparison-totals")).not.toBeInTheDocument();
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("부족 0.50");
  });
  it("does not fabricate tolerance or badge when the parameter is missing", async () => {
    paramsMissing = true;
    mount();
    await screen.findByText("계획 대비 투입 기준값을 확인하세요.");
    expect(screen.queryByText("부족 0.50")).not.toBeInTheDocument();
  });
  it("keeps project/job/year across the monthly renderer handoff and restores annual gap", async () => {
    mount({
      renderShortages: ({ filters }) => (
        <p>{`monthly:${filters.year}:${filters.projectId}:${filters.job}:${filters.page}`}</p>
      ),
    });
    await screen.findByText("부족 0.50");
    fireEvent.change(screen.getByLabelText("프로젝트"), {
      target: { value: "7" },
    });
    fireEvent.change(screen.getByLabelText("직무"), {
      target: { value: "BE" },
    });
    fireEvent.change(screen.getByLabelText("차이"), {
      target: { value: "SHORT" },
    });
    fireEvent.click(screen.getByTestId("comparison-query"));
    await waitFor(() => expect(lastQuery().get("gap")).toBe("SHORT"));
    fireEvent.click(screen.getByRole("tab", { name: "미충원 직무" }));
    await screen.findByText("monthly:2026:7:BE:0");
    expect(screen.queryByLabelText("차이")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "계획 대비 투입" }));
    expect(screen.getByLabelText("차이")).toHaveValue("SHORT");
  });
  it("downloads all applied filters without page or size", async () => {
    const create = vi.fn(() => "blob:annual");
    const revoke = vi.fn();
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: create,
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: revoke,
    });
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});
    mount();
    await screen.findByText("부족 0.50");
    fireEvent.click(screen.getByTestId("plan-comparison-download"));
    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    const call = fetcher.mock.calls.find(([url]) =>
      String(url).startsWith("/api/plan-vs-actual/export?"),
    );
    expect(String(call?.[0])).not.toContain("page=");
    expect(String(call?.[0])).not.toContain("size=");
    expect(revoke).toHaveBeenCalledWith("blob:annual");
  });
  it("shows download failure and permits retry", async () => {
    failExport = true;
    mount();
    await screen.findByText("부족 0.50");
    fireEvent.click(screen.getByTestId("plan-comparison-download"));
    await screen.findByText("다운로드 오류");
    expect(screen.getByTestId("plan-comparison-download")).toBeEnabled();
  });
});
