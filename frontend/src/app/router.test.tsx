import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppRouter } from "./router";
import { screenIds } from "../pages/shared/registry";
import { useSharedPreferences } from "../pages/shared/preferences";
import type { MenuNode } from "../pages/shared/api";
import type { BillingDetail } from "../pages/billings/client";
process.env.DEBUG_PRINT_LIMIT = "300";
import {
  developerFixture,
  codeFixtures,
} from "../pages/developers/DeveloperTestSupport";

const menus: MenuNode[] = [
  {
    menuId: 1,
    name: "업무",
    screenId: null,
    icon: "folder",
    mobile: true,
    children: screenIds.map((id, index) => ({
      menuId: index + 2,
      name: id === "UI-27" ? "공통코드" : id,
      screenId: id,
      icon: null,
      mobile: id !== "UI-27",
      children: [],
    })),
  },
];
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
let fetcher: ReturnType<typeof vi.fn>;
let billing: BillingDetail;
let billingReadFails = false;
function Location() {
  return <output aria-label="현재 주소">{useLocation().pathname}</output>;
}
function mount(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Location />
      <AppRouter />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-29T00:00:00Z"));
  sessionStorage.clear();
  billingReadFails = false;
  billing = {
    billing: {
      bill_id: 7,
      bill_stg_id: null,
      virtual_plan: false,
      bill_no: "B-2610-07",
      prj_id: 9,
      project_name: "청구 프로젝트",
      customer_id: 2,
      customer_name: "고객사",
      contract_type: "DP",
      atrb_ym: "2026-10",
      bill_dt: "2026-10-01",
      sply_amt: 100,
      vat_amt: 10,
      tot_amt: 110,
      bill_stts_cd: "DRAFT",
      txinv_no: null,
      txinv_dt: null,
      rcpt_plan_dt: null,
      received_amt: 0,
      adjusted_total_amt: 110,
      receivable_amt: 110,
      reminder_stage: null,
    },
    details: [],
    receipts: [],
    amendments: [],
    original_bill_id: null,
    change_history: [],
    warnings: [],
  };
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    value: 1280,
  });
  localStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/menus") return response(menus);
    if (
      url.startsWith("/api/costs/people?") ||
      url.startsWith("/api/costs/projects?") ||
      url.startsWith("/api/settlements/parts?")
    )
      return response({
        month: "2026-10",
        status: "OPEN",
        referenceMonth: "2026-10",
        unit: "KRW",
        warnings: [],
        items: [],
        total: 0,
        page: 0,
        size: 50,
        summary: { contributions: [] },
      });
    if (url === "/api/projects/options")
      return response([
        { id: 9, no: "P-2609", name: "청구 프로젝트", type: "DP" },
      ]);
    if (url === "/api/billings/7/confirm") {
      billing.billing.bill_stts_cd = "CONFIRM";
      return response(billing);
    }
    if (url === "/api/billings/7/receipts") return response(billing);
    if (url === "/api/billings/7") {
      if (init?.method === "PUT") {
        Object.assign(billing.billing, JSON.parse(init.body as string));
        billing.billing.tot_amt =
          billing.billing.sply_amt + billing.billing.vat_amt;
      }
      return response(billing);
    }
    if (url === "/api/projects/9")
      return response({
        prj_id: 9,
        prj_nm: "청구 프로젝트",
        cntrct_ty_cd: "DP",
        stlm_mthd_cd: "DAY",
        use_yn: "Y",
      });
    if (url === "/api/projects/9/assignments") return response([]);
    if (url.startsWith("/api/dispatch-attendance?"))
      return response({
        month: "2026-10",
        project_id: 9,
        items: [],
        warnings: [],
      });
    if (url.startsWith("/api/billings/summary?"))
      return response({
        annual_confirmed_supply_amt: 100,
        current_month_confirmed_supply_amt: 100,
        confirmed_receivable_amt: 110,
        overdue_amt: 0,
        planned_supply_amt: 0,
        draft_supply_amt: 100,
      });
    if (url.startsWith("/api/billings/gaps?"))
      return response({
        month: "2026-10",
        total_difference_amt: 100,
        items: [
          {
            project_id: 9,
            project_name: "청구 프로젝트",
            month: "2026-10",
            basis_supply_amt: 100,
            confirmed_supply_amt: 0,
            difference_amt: 100,
            tolerance: 10000,
            judgment: "청구 없음",
            explanation: "미청구",
            attendance_missing: false,
            suspected_people: [],
            billing_ids: [],
          },
        ],
        total: 1,
        page: 0,
        size: 50,
      });
    if (url.startsWith("/api/billings/overdue?"))
      return response({
        items: [
          {
            billing: billing.billing,
            overdue_days: null,
            stage: null,
            due_status: "예정일 미입력",
            recipient_roles: [],
            next_notification_date: null,
            sent_history: [],
            sales_part: null,
          },
        ],
        customer_totals: [],
        stage_counts: [],
        total: 1,
        page: 0,
        size: 50,
      });
    if (url.startsWith("/api/billings?")) {
      if (billingReadFails) throw new Error("offline");
      return response({
        items: [billing.billing],
        total: 1,
        page: 0,
        size: 50,
      });
    }
    if (url.startsWith("/api/withdrawals?"))
      return response({
        rows: [],
        total: 0,
        page: 0,
        size: 50,
        summary: { soonPeople: 0, noNextPeople: 0 },
      });
    if (url.startsWith("/api/plan-vs-actual?"))
      return response({
        rows: [],
        total: 0,
        page: 0,
        size: 50,
        totals: { planMm: 0, actualMm: 0 },
      });
    if (url === "/api/params")
      return response([{ oprt_std_id: "PLAN_GAP_TOLERANCE", num_val: 0.1 }]);
    if (url.startsWith("/api/month-close?")) return response([]);
    if (url.startsWith("/api/assignments?"))
      return response({ items: [], total: 0, page: 0, size: 50 });
    if (url.startsWith("/api/projects?"))
      return response({
        items: [],
        total: 0,
        page: 0,
        size: 50,
        unassignedCount: 0,
      });
    if (
      url === "/api/projects/options" ||
      url.startsWith("/api/developers/options") ||
      url === "/api/partners/options?kind=CUST"
    )
      return response([]);
    if (url.startsWith("/api/params/BUDGET_RATE"))
      return response({ num_val: 80 });
    if (url.startsWith("/api/codes/"))
      return response(codeFixtures[url.split("/").at(-1)!] ?? []);
    if (url.startsWith("/api/developers?"))
      return response({
        items: [developerFixture],
        total: 1,
        page: 0,
        size: 50,
      });
    if (url === "/api/developers/1") return response(developerFixture);
    if (
      /^\/api\/developers\/1\/(assignments|monthly-costs|history|work-status)\?/.test(
        url,
      )
    )
      return response({ items: [], total: 0, page: 0, size: 50 });
    if (
      url === "/api/partners/options?kind=VEND" ||
      url === "/api/sales-parts/options"
    )
      return response([]);
    if (url.startsWith("/api/partners?") || url.startsWith("/api/sales-parts?"))
      return response({ items: [], total: 0, page: 1, size: 50 });
    if (url.startsWith("/api/admin/code-groups?"))
      return response({
        items: [
          {
            cd_grp_id: "JOB",
            cd_grp_nm: "직무",
            cd_grp_se_cd: "BIZ",
            attr1_nm: null,
            attr2_nm: null,
            attr3_nm: null,
            sort_seq: 1,
            use_yn: "Y",
            codeCount: 1,
          },
        ],
        total: 1,
        page: 1,
        size: 100,
      });
    if (url.startsWith("/api/admin/code-groups/JOB/codes?"))
      return response({
        items: [
          {
            dtl_cd: "BE",
            cd_nm: "백엔드",
            sort_seq: 1,
            use_yn: "Y",
            attr1_val: null,
            attr2_val: null,
            attr3_val: null,
            aply_bgn_dt: null,
          },
        ],
        total: 1,
        page: 1,
        size: 100,
      });
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("application route registration", () => {
  it.each([
    ["UI-03", "people-screen", "/api/costs/people?", "people-download"],
    [
      "UI-04",
      "project-costs-screen",
      "/api/costs/projects?",
      "project-costs-download",
    ],
    [
      "UI-19",
      "part-settlements-screen",
      "/api/settlements/parts?",
      "settlement-download",
    ],
  ])(
    "connects %s to its business screen and relative API",
    async (id, root, endpoint, download) => {
      mount(`/${id}`);
      expect(await screen.findByTestId(root)).toBeInTheDocument();
      await waitFor(() =>
        expect(
          fetcher.mock.calls.some(([url]) => url.startsWith(endpoint)),
        ).toBe(true),
      );
      expect(screen.getByTestId(download)).toBeInTheDocument();
      expect(
        await screen.findByText(/조건에 맞는 결과가 없습니다/),
      ).toBeInTheDocument();
      expect(
        fetcher.mock.calls.some(([url]) =>
          url.startsWith("/api/costs/renegotiations"),
        ),
      ).toBe(false);
    },
  );
  it("composes billing tabs, the source-aware editor and attendance upload on UI-13", async () => {
    mount("/UI-13");
    await screen.findByTestId("billing-row-7");
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    fireEvent.click(await screen.findByTestId("billing-gap-action-9"));
    expect(await screen.findByLabelText(/프로젝트/)).toHaveValue("9");
    expect(screen.getByLabelText(/귀속월/)).toHaveValue("2026-10");
    fireEvent.click(screen.getByTestId("billing-editor-close"));
    fireEvent.click(screen.getByTestId("billing-upload"));
    fireEvent.change(await screen.findByTestId("attendance-project"), {
      target: { value: "9" },
    });
    fireEvent.click(screen.getByTestId("attendance-query"));
    expect(await screen.findByTestId("attendance-upload")).toHaveTextContent(
      "B.3 파견 근태 업로드",
    );
    fireEvent.click(screen.getByTestId("attendance-close"));
    expect(
      screen.queryByTestId("dispatch-attendance-dialog"),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("billing-upload"));
    expect(
      await screen.findByTestId("dispatch-attendance-dialog"),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-13");
  });
  it("connects confirmation without a body and reloads the receipt controls", async () => {
    mount("/UI-13");
    fireEvent.click(await screen.findByTestId("billing-open-0"));
    fireEvent.click(await screen.findByTestId("billing-confirm"));
    fireEvent.click(screen.getByRole("button", { name: "확정 확인" }));
    expect(await screen.findByTestId("receipt-add")).toBeInTheDocument();
    const confirm = fetcher.mock.calls.find(
      ([url]) => url === "/api/billings/7/confirm",
    );
    expect(confirm?.[1]).toMatchObject({ method: "POST" });
    expect(confirm?.[1]?.body).toBeUndefined();
    fireEvent.click(screen.getByTestId("receipt-add"));
    expect(await screen.findByTestId("receipt-modal")).toBeInTheDocument();
  });
  it("refreshes the composed detail after saving in the separate billing editor", async () => {
    mount("/UI-13");
    fireEvent.click(await screen.findByTestId("billing-open-0"));
    fireEvent.click(await screen.findByTestId("billing-edit"));
    const supply = await screen.findByLabelText(/공급가액/);
    fireEvent.change(supply, { target: { value: "250" } });
    await waitFor(() =>
      expect(screen.getByTestId("billing-editor-save")).not.toBeDisabled(),
    );
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await waitFor(() =>
      expect(screen.queryByTestId("billing-editor")).not.toBeInTheDocument(),
    );
    expect(
      await within(screen.getByTestId("billing-detail")).findByText("250"),
    ).toBeInTheDocument();
  });
  it("retains read recovery and inert reminders through the registered billing route", async () => {
    billingReadFails = true;
    mount("/UI-13");
    await screen.findByText("다시 시도");
    billingReadFails = false;
    fireEvent.click(await screen.findByText("다시 시도"));
    await screen.findByTestId("billing-row-7");
    fireEvent.click(screen.getByTestId("billing-tab-overdue"));
    expect(await screen.findByText("보낸 알림 없음")).toBeInTheDocument();
    expect(screen.getByTestId("billing-remind-7")).toBeDisabled();
  });
  it.each(["mobile", "past-year"])(
    "hides composed billing writes in %s mode",
    async (mode) => {
      if (mode === "mobile")
        Object.defineProperty(window, "innerWidth", {
          configurable: true,
          value: 375,
        });
      mount("/UI-13");
      await screen.findByTestId("billing-row-7");
      if (mode === "past-year")
        fireEvent.click(screen.getByTestId("billing-year-prev"));
      expect(screen.queryByTestId("billing-upload")).not.toBeInTheDocument();
      expect(screen.queryByTestId("billing-add")).not.toBeInTheDocument();
      fireEvent.click(await screen.findByTestId("billing-open-0"));
      await screen.findByTestId("billing-detail");
      expect(screen.queryByTestId("billing-confirm")).not.toBeInTheDocument();
      expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
      expect(screen.getByTestId("billing-export")).toBeInTheDocument();
    },
  );
  it.each([...screenIds, "UI-22"])(
    "renders /%s through the single shared shell",
    async (id) => {
      mount(`/${id}`);
      expect(
        await screen.findByRole("heading", {
          name:
            id === "UI-27"
              ? /공통코드/
              : id === "UI-32"
                ? /개발자 등록/
                : id === "UI-33"
                  ? /프로젝트 등록/
                  : id,
        }),
      ).toBeInTheDocument();
      expect(screen.getAllByLabelText("열린 화면")).toHaveLength(1);
      expect(
        screen.queryByText("화면을 찾을 수 없습니다."),
      ).not.toBeInTheDocument();
      if (id === "UI-22")
        expect(
          within(
            screen.getByRole("navigation", { name: "열린 화면" }),
          ).getByText("UI-22"),
        ).toBeInTheDocument();
    },
  );
  it("redirects root to the dashboard without introducing a login", async () => {
    mount("/");
    await screen.findByRole("heading", { name: "UI-16" });
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-16");
    expect(
      fetcher.mock.calls.some(([url]) => String(url).includes("/auth/")),
    ).toBe(false);
  });
  it.each([
    "/UI-06",
    "/UI-99",
    "/UI-27/M3",
    "/UI-15/M1",
    "/UI-15/M13",
    "/login",
  ])("does not register unsupported route %s", async (path) => {
    mount(path);
    expect(
      await screen.findByText("화면을 찾을 수 없습니다."),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: /공통코드 관리/ }),
    ).not.toBeInTheDocument();
  });
  it("composes both partner tabs and the existing upload flow on the same route", async () => {
    mount("/UI-15");
    expect(await screen.findByTestId("partners-screen")).toBeInTheDocument();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "영업 파트" }));
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("sales-part-add")).toBeInTheDocument();
    expect(screen.getByTestId("sales-parts-panel")).toBeInTheDocument();
    expect(
      screen.queryByText("영업 파트 화면을 연결할 수 없습니다."),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "거래처" }));
    fireEvent.click(screen.getByTestId("partner-upload"));
    expect(screen.getByTestId("upload-selection")).toBeInTheDocument();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-15");
    fireEvent.click(screen.getByTestId("upload-cancel"));
    expect(screen.queryByTestId("upload-selection")).not.toBeInTheDocument();
  });
  it("keeps partner drafts mounted and propagates dirty close protection", async () => {
    mount("/UI-15");
    fireEvent.click(await screen.findByTestId("partner-create"));
    fireEvent.change(screen.getByLabelText(/상호/), {
      target: { value: "작성 중" },
    });
    const tabs = screen.getByLabelText("열린 화면");
    await waitFor(() => expect(tabs).toHaveTextContent("● UI-15"));
    fireEvent.click(within(tabs).getByRole("button", { name: "UI-16" }));
    fireEvent.click(within(tabs).getByRole("button", { name: "● UI-15" }));
    expect(screen.getByLabelText(/상호/)).toHaveValue("작성 중");
    fireEvent.click(screen.getByLabelText("UI-15 닫기"));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "저장하지 않은 내용이 있습니다.",
    );
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByLabelText(/상호/)).toHaveValue("작성 중");
  });
  it("keeps the real code editor mounted, propagates dirty state and guards close", async () => {
    mount("/UI-27");
    fireEvent.change(await screen.findByLabelText("이름 1"), {
      target: { value: "작성 중" },
    });
    const tabs = screen.getByLabelText("열린 화면");
    await waitFor(() => expect(tabs).toHaveTextContent("● 공통코드"));
    fireEvent.click(within(tabs).getByRole("button", { name: "UI-16" }));
    fireEvent.click(within(tabs).getByRole("button", { name: "● 공통코드" }));
    expect(screen.getByLabelText("이름 1")).toHaveValue("작성 중");
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).includes("/JOB/codes?")),
    ).toHaveLength(1);
    fireEvent.click(screen.getByLabelText("공통코드 닫기"));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "저장하지 않은 내용이 있습니다.",
    );
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByLabelText("이름 1")).toHaveValue("작성 중");
    fireEvent.click(screen.getByLabelText("공통코드 닫기"));
    fireEvent.click(screen.getByText("나가기"));
    await waitFor(() =>
      expect(screen.queryByLabelText("이름 1")).not.toBeInTheDocument(),
    );
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-16");
  });
  it("uses the API mobile restriction before mounting the business screen", async () => {
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      value: 375,
    });
    mount("/UI-27");
    expect(await screen.findByText("PC에서 이용해 주세요")).toBeInTheDocument();
    expect(
      fetcher.mock.calls.some(([url]) => String(url).startsWith("/api/admin/")),
    ).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "대시보드로" }));
    await screen.findByRole("heading", { name: "UI-16" });
  });
  it("retains menu read recovery in the registered shell", async () => {
    fetcher.mockRejectedValueOnce(new Error("offline"));
    mount("/UI-27");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "문의 번호 없음 (서버 응답 없음)",
    );
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByLabelText("이름 1")).toHaveValue("백엔드");
  });
});
