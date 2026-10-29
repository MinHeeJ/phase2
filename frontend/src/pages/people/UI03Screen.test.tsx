import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI03Screen from "./UI03Screen";
import type { PeopleData } from "./client";

let data: PeopleData;
let fail = false;
let detailFail = false;
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  localStorage.clear();
  vi.stubGlobal("innerWidth", 1280);
  fail = false;
  detailFail = false;
  data = {
    items: [
      {
        dev_id: 7,
        name: "시험 개발자",
        affiliation: "자사",
        position: "팀원",
        emp_type: "FT",
        job: "BE",
        grade: "HIGH",
        projects: "운영, 대기",
        period: "2026-01 ~ 2026-02",
        contract_types: "MO",
        mm: 1.2,
        price: 100,
        salary: 80,
        allowance: 0,
        insurance: 0,
        retirement: 0,
        labor: 80,
        expenses: 5,
        cost: 85,
        revenue: 100,
        profit: 15,
        margin: 15,
        overlap: true,
        on_leave: false,
        warnings: [],
      },
    ],
    total: 1,
    page: 0,
    size: 50,
    through: "2026-02",
    summary: {
      people: 1,
      active_people: 1,
      paid_people: 1,
      utilization: 50,
      earning_rate: 50,
      average_price: 100,
      average_cost: 85,
      average_profit: 15,
      loss_people: 0,
      revenue: 100,
      cost: 85,
      profit: 15,
    },
    contributions: [
      { dimension: "job", name: "BE", people: 1, per_person_profit: 15 },
    ],
    warnings: [],
  };
  fetcher = vi.fn(async (path: string) => {
    let body: unknown = [];
    let status = 200;
    if (path.startsWith("/api/codes/"))
      body = [
        { code: "FT", name: "정규직", attr1: null, attr2: null, attr3: null },
      ];
    if (path.startsWith("/api/costs/people?")) {
      body = data;
      if (fail) {
        status = 400;
        body = {
          success: false,
          code: "VALIDATION_ERROR",
          message: "조회 조건 오류",
          field: "year",
          requestId: "test",
        };
      }
    }
    if (path.includes("/breakdown?")) {
      body = {
        dev_id: 7,
        view: "TOTAL",
        through: "2026-02",
        warnings: [],
        items: [
          {
            dev_id: 7,
            month: "2026-02",
            prj_id: 3,
            asgn_id: 4,
            project: "운영",
            classification: "PROJECT",
            contract_type: "MO",
            job: "BE",
            mm: 1.2,
            price: 100,
            labor: 80,
            expenses: 5,
            revenue: 100,
            profit: 15,
            overlap: true,
            free: false,
            pre_contract: false,
          },
          {
            dev_id: 7,
            month: "2026-01",
            prj_id: null,
            asgn_id: null,
            project: "대기",
            classification: "IDLE",
            contract_type: null,
            job: null,
            mm: 0,
            price: null,
            labor: 20,
            expenses: 0,
            revenue: null,
            profit: -20,
            overlap: false,
            free: false,
            pre_contract: false,
          },
        ],
      };
      if (detailFail) {
        status = 409;
        body = {
          success: false,
          code: "VALIDATION_ERROR",
          message: "마감 배분자료 없음",
          field: "month",
        };
      }
    }
    return new Response(
      JSON.stringify(
        status === 200 ? { success: true, meta: {}, data: body } : body,
      ),
      { status, headers: { "Content-Type": "application/json" } },
    );
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const show = () =>
  render(
    <MemoryRouter initialEntries={["/UI-03"]}>
      <UI03Screen />
    </MemoryRouter>,
  );

describe("personal cost analysis", () => {
  it("renders API amounts, shared table styling, default-hidden reference columns and unavailable export", async () => {
    show();
    const row = await screen.findByTestId("people-row-7");
    expect(within(row).getByText("시험 개발자 ▸")).toBeVisible();
    expect(row.closest("table")?.className).toContain("table");
    expect(row.closest("table")?.parentElement?.className).toContain(
      "tableScroll",
    );
    expect(within(row).getByText("1.20")).toBeVisible();
    expect(
      screen.queryByRole("columnheader", { name: "실지급액" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("columnheader", { name: "4대보험 본인" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("columnheader", { name: "원천세" }),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("people-download")).toBeDisabled();
    expect(screen.getByText(/승인된 HTTP 연결/)).toBeVisible();
  });
  it("submits exact contract filters and retains them when switching view", async () => {
    show();
    await screen.findByTestId("people-row-7");
    fireEvent.click(screen.getByTestId("people-loss-only"));
    fireEvent.click(screen.getByTestId("people-overlap-only"));
    fireEvent.click(screen.getByTestId("people-exclude-turnkey"));
    fireEvent.click(screen.getByTestId("people-query"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([p]) =>
            p.includes("lossOnly=true") &&
            p.includes("overlapOnly=true") &&
            p.includes("excludeTurnkeyPrice=true"),
        ),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId("people-view-average"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([p]) => p.includes("view=AVERAGE") && p.includes("lossOnly=true"),
        ),
      ).toBe(true),
    );
  });
  it("expands selected domain identity and shows monthly idle evidence", async () => {
    show();
    fireEvent.click(await screen.findByTestId("people-expand-7"));
    const detail = await screen.findByTestId("people-evidence-7-1");
    expect(within(detail).getByText("대기")).toBeVisible();
    expect(within(detail).getByText("-20")).toBeVisible();
    expect(
      fetcher.mock.calls.some(([p]) =>
        p.startsWith("/api/costs/people/7/breakdown?"),
      ),
    ).toBe(true);
  });
  it("displays and retries an unavailable closed-month breakdown", async () => {
    detailFail = true;
    show();
    fireEvent.click(await screen.findByTestId("people-expand-7"));
    expect(await screen.findByText(/마감 배분자료 없음/)).toBeVisible();
    detailFail = false;
    fireEvent.click(screen.getByTestId("people-retry"));
    expect(await screen.findByTestId("people-evidence-7-0")).toBeVisible();
  });
  it("toggles contribution rows and uses shared styling", async () => {
    show();
    await screen.findByTestId("people-row-7");
    expect(
      screen.queryByTestId("people-contribution-panel"),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("people-contribution-toggle"));
    expect(
      within(screen.getByTestId("people-contribution-panel")).getByText("BE"),
    ).toBeVisible();
  });
  it("renders empty state and resets conditions", async () => {
    data.items = [];
    data.total = 0;
    show();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeVisible();
    fireEvent.click(screen.getByTestId("people-loss-only"));
    fireEvent.click(screen.getByTestId("people-empty-reset"));
    expect(screen.getByTestId("people-loss-only")).not.toBeChecked();
  });
  it("renders singular ApiError.field and retry recovers", async () => {
    fail = true;
    show();
    expect(await screen.findByText("조회 조건 오류 (year)")).toBeVisible();
    fail = false;
    fireEvent.click(screen.getByTestId("people-retry"));
    expect(await screen.findByTestId("people-row-7")).toBeVisible();
  });
  it("shared period change re-queries visible year without a second period bar", async () => {
    show();
    await screen.findByTestId("people-row-7");
    expect(screen.getAllByLabelText("조회 기간")).toHaveLength(1);
    fireEvent.click(screen.getByLabelText("이전 연도"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([p]) =>
            p.startsWith("/api/costs/people?year=") &&
            p.includes(`year=${new Date().getFullYear() - 1}`),
        ),
      ).toBe(true),
    );
  });
  it("zero denominator displays dash and warnings are visible without permission gating", async () => {
    data.summary.utilization = null;
    data.summary.average_price = null;
    data.warnings = ["급여 미업로드"];
    show();
    expect(await screen.findByTestId("people-warnings")).toHaveTextContent(
      "급여 미업로드",
    );
    expect(screen.getByTestId("people-summary")).toHaveTextContent("가동률 –");
    expect(screen.getByTestId("people-query")).toBeEnabled();
  });
});
