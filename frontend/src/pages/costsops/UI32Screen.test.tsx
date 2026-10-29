import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI32Screen from "./UI32Screen";
import { getCostAllocation, type RenegotiationData } from "./client";
import tables from "../developers/Developers.module.css";

vi.mock("../developers/DeveloperDrawer", () => ({
  DeveloperDrawer: ({ id }: { id: number }) => (
    <aside data-testid="selected-developer">개발자 상세 {id}</aside>
  ),
}));
vi.mock("../changes/AssignmentChangeForms", () => ({
  AssignmentChangeForm: ({
    assignment,
  }: {
    assignment: { asgn_id: number };
  }) => (
    <aside data-testid="selected-assignment">
      ⑦ 변경 창 {assignment.asgn_id}
    </aside>
  ),
}));
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let data: RenegotiationData;
let fetcher: ReturnType<typeof vi.fn>;
let failure: boolean;
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  sessionStorage.clear();
  failure = false;
  data = {
    month: "2026-10",
    closed: false,
    total: 1,
    page: 0,
    size: 20,
    warnings: [],
    summary: {
      people_count: 1,
      weighted_margin_rt: 10,
      annual_increase_amt: 60,
      target_annual_increase_amt: 150,
    },
    items: [
      {
        asgn_id: 91,
        dev_id: 27,
        dev_nm: "대상 개발자",
        emp_se_cd: "FT",
        prj_id: 53,
        prj_nm: "실제 프로젝트",
        cntrct_ty_cd: "MO",
        end_dt: "2026-12-31",
        days_remaining: 69,
        mm_qty: 1,
        bill_untprc_amt: 100,
        allocated_cost_amt: 90,
        margin_rt: 10,
        target_untprc_amt: 10000,
        required_raise_rt: 12.5,
        annual_increase_amt: 60,
        target_annual_increase_amt: 150,
        warnings: [],
      },
    ],
  };
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/codes/EMP_TYPE")
      return response([
        { code: "FT", name: "정규", attr1: null, attr2: null, attr3: null },
      ]);
    if (url === "/api/projects/options")
      return response([
        { id: 53, no: "P-2653", name: "실제 프로젝트", type: "MO" },
      ]);
    if (url === "/api/assignments/91") return response({ asgn_id: 91 });
    if (url.startsWith("/api/costs/allocation?"))
      return response({ month: "2026-10", items: [] });
    if (url.startsWith("/api/costs/renegotiations?")) {
      if (failure)
        return new Response(
          JSON.stringify({
            success: false,
            code: "VALIDATION_ERROR",
            message: "조건 오류",
            field: "raiseRate",
            requestId: "cost-read-1",
          }),
          { status: 400, headers: { "Content-Type": "application/json" } },
        );
      const rate = Number(
        new URLSearchParams(url.split("?")[1]).get("raiseRate"),
      );
      return response({
        ...data,
        summary: { ...data.summary, annual_increase_amt: rate * 12 },
      });
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const open = () =>
  render(
    <MemoryRouter>
      <UI32Screen />
    </MemoryRouter>,
  );

describe("재협상 조회 화면", () => {
  it("uses API defaults and shared table CSS, leaves conflicting route and download unavailable", async () => {
    open();
    expect(
      await screen.findByRole("button", { name: "대상 개발자" }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("renegotiation-window")).toHaveValue("6");
    expect(screen.getByTestId("renegotiation-margin")).toHaveValue("15");
    expect(screen.getByTestId("renegotiation-raise")).toHaveValue(5);
    expect(screen.getByRole("table")).toHaveClass(tables.table);
    expect(screen.getByTestId("renegotiation-download")).toBeDisabled();
    expect(screen.getByText(/화면 주소 승인 대기/)).toBeInTheDocument();
  });
  it("recalculates assumptions with GET and never writes", async () => {
    open();
    await screen.findByRole("button", { name: "대상 개발자" });
    fireEvent.change(screen.getByTestId("renegotiation-raise"), {
      target: { value: "10" },
    });
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) =>
          String(url).includes("raiseRate=10"),
        ),
      ).toBe(true),
    );
    expect(
      fetcher.mock.calls.every(
        ([, init]) => !init || !init.method || init.method === "GET",
      ),
    ).toBe(true);
    expect(screen.getByText(/12개월 유지 가정/)).toBeInTheDocument();
  });
  it("keeps invalid assumption input without sending it", async () => {
    open();
    await screen.findByRole("button", { name: "대상 개발자" });
    fireEvent.change(screen.getByTestId("renegotiation-raise"), {
      target: { value: "51" },
    });
    expect(
      screen.getByText("인상률은 0~50%로 입력하세요."),
    ).toBeInTheDocument();
    expect(screen.getByTestId("renegotiation-raise")).toHaveValue(51);
    expect(
      fetcher.mock.calls.some(([url]) => String(url).includes("raiseRate=51")),
    ).toBe(false);
  });
  it("applies selected domain filters and resets them", async () => {
    open();
    await screen.findByRole("option", { name: "실제 프로젝트" });
    fireEvent.change(screen.getByTestId("renegotiation-project"), {
      target: { value: "53" },
    });
    fireEvent.change(screen.getByTestId("renegotiation-employee"), {
      target: { value: "FT" },
    });
    fireEvent.click(screen.getByTestId("renegotiation-search"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) =>
            String(url).includes("projectId=53") &&
            String(url).includes("empType=FT"),
        ),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId("renegotiation-reset"));
    expect(screen.getByTestId("renegotiation-project")).toHaveValue("");
  });
  it("renders API error field and retries with input preserved", async () => {
    failure = true;
    open();
    expect(await screen.findByText("조건 오류")).toBeInTheDocument();
    expect(screen.getByText("입력 항목: raiseRate")).toBeInTheDocument();
    expect(screen.getByText("문의 번호 cost-read-1")).toBeInTheDocument();
    failure = false;
    fireEvent.click(screen.getByTestId("renegotiation-retry"));
    expect(
      await screen.findByRole("button", { name: "대상 개발자" }),
    ).toBeInTheDocument();
  });
  it("renders an empty result instead of sample rows", async () => {
    data.items = [];
    data.total = 0;
    open();
    expect(await screen.findByText("조회 결과가 없습니다.")).toHaveClass(
      tables.empty,
    );
  });
  it("marks closed snapshots as read only and hides change", async () => {
    data.closed = true;
    open();
    await screen.findByRole("button", { name: "대상 개발자" });
    expect(
      screen.queryByRole("button", { name: "단가 변경" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("마감 조회 전용")).toBeInTheDocument();
  });
  it("mobile still has the shared readable table but hides change", async () => {
    window.innerWidth = 375;
    open();
    await screen.findByRole("button", { name: "대상 개발자" });
    expect(screen.getByRole("table")).toHaveClass(tables.table);
    expect(
      screen.queryByRole("button", { name: "단가 변경" }),
    ).not.toBeInTheDocument();
  });
  it("opens the selected developer and delegates rate changes to the existing assignment form", async () => {
    open();
    fireEvent.click(await screen.findByRole("button", { name: "대상 개발자" }));
    expect(screen.getByTestId("selected-developer")).toHaveTextContent("27");
    fireEvent.click(screen.getByTestId("renegotiation-rate-91"));
    expect(await screen.findByTestId("selected-assignment")).toHaveTextContent(
      "91",
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/assignments/91",
      expect.anything(),
    );
  });
  it("shows missing evidence and zero denominators as dashes, plus calculation table", async () => {
    data.items[0].margin_rt = null;
    data.items[0].target_untprc_amt = null;
    data.warnings = ["단가 미등록"];
    open();
    await screen.findByRole("button", { name: "대상 개발자" });
    expect(screen.getByTestId("renegotiation-warning")).toHaveTextContent(
      "단가 미등록",
    );
    expect(screen.getByTestId("renegotiation-row-91")).toHaveTextContent("–");
    fireEvent.click(screen.getByTestId("renegotiation-method"));
    expect(
      screen.getByTestId("renegotiation-method-panel").querySelector("table"),
    ).toHaveClass(tables.table);
  });
  it("allocation client preserves month and selected project parameters", async () => {
    await getCostAllocation("2026-10", "53");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/costs/allocation?month=2026-10&projectId=53",
      expect.anything(),
    );
  });
});
