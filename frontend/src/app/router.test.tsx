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
import * as apiClient from "../api/apiClient";
import { seoulMonth } from "../pages/shared/PeriodBar";
import type { BillingRow } from "../pages/billings/client";
import { screenIds } from "../pages/shared/registry";
import { useSharedPreferences } from "../pages/shared/preferences";
import type { MenuNode } from "../pages/shared/api";
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
const billingRow: BillingRow = {
  bill_id: 81,
  bill_stg_id: null,
  virtual_plan: false,
  bill_no: "B-TEST-81",
  prj_id: 92,
  project_name: "등록 연결 검증",
  customer_id: null,
  customer_name: null,
  contract_type: "MO",
  atrb_ym: seoulMonth(),
  bill_dt: `${seoulMonth()}-01`,
  sply_amt: 100,
  vat_amt: 10,
  tot_amt: 110,
  bill_stts_cd: "CONFIRM",
  txinv_no: null,
  txinv_dt: null,
  rcpt_plan_dt: null,
  received_amt: 0,
  adjusted_total_amt: 110,
  receivable_amt: 110,
  reminder_stage: null,
};
const billingDetail = () => ({
  billing: billingRow,
  details: [],
  receipts: [],
  amendments: [],
  original_bill_id: null,
  change_history: [],
  warnings: [],
});
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
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    value: 1280,
  });
  localStorage.clear();
  sessionStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  fetcher = vi.fn(async (url: string, _init?: RequestInit) => {
    if (url === "/api/menus") return response(menus);
    if (url.startsWith("/api/billings/summary?"))
      return response({
        year: Number(seoulMonth().slice(0, 4)),
        annual_confirmed_supply_amt: 100,
        current_month_confirmed_supply_amt: 100,
        confirmed_receivable_amt: 110,
        overdue_amt: 0,
        planned_supply_amt: 0,
        draft_supply_amt: 0,
      });
    if (url.startsWith("/api/billings?"))
      return response({ items: [billingRow], total: 1, page: 0, size: 50 });
    if (url === "/api/billings/81" || url === "/api/billings/81/receipts")
      return response(billingDetail());
    if (url.startsWith("/api/billings/gaps?"))
      return response({
        month: seoulMonth(),
        items: [],
        total: 0,
        page: 0,
        size: 50,
        total_difference_amt: 0,
      });
    if (url.startsWith("/api/billings/overdue?"))
      return response({
        items: [],
        total: 0,
        page: 0,
        size: 50,
        customer_totals: [],
        stage_counts: [],
      });
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
afterEach(() => vi.unstubAllGlobals());

describe("application route registration", () => {
  it("registers billing tabs and the receipt owner on the existing route", async () => {
    mount("/UI-13");
    await screen.findByTestId("billing-row-81");
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    expect(await screen.findByTestId("billing-panel-gaps")).toBeVisible();
    fireEvent.click(screen.getByTestId("billing-tab-overdue"));
    expect(await screen.findByTestId("billing-panel-overdue")).toBeVisible();
    fireEvent.click(screen.getByTestId("billing-tab-list"));
    fireEvent.click(await screen.findByTestId("billing-open-81"));
    expect(await screen.findByTestId("billing-receipts-panel")).toBeVisible();
    fireEvent.click(await screen.findByTestId("billing-receipt-add"));
    expect(screen.getAllByTestId("billing-receipt-add")).toHaveLength(1);
    expect(screen.getByTestId("billing-receipt-dialog")).toBeVisible();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-13");
    fireEvent.click(screen.getByTestId("billing-receipt-cancel"));
    expect(
      screen.queryByTestId("billing-receipt-dialog"),
    ).not.toBeInTheDocument();
  });
  it("composes manual attendance and B.3 upload without registering modal routes", async () => {
    mount("/UI-13");
    fireEvent.click(await screen.findByTestId("billing-attendance"));
    expect(
      await screen.findByTestId("dispatch-attendance-dialog"),
    ).toBeVisible();
    fireEvent.click(await screen.findByTestId("A-13-UPLOAD"));
    expect(await screen.findByTestId("attendance-upload-dialog")).toBeVisible();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-13");
    fireEvent.click(screen.getByTestId("A-13-M2-CLOSE"));
    expect(
      screen.queryByTestId("attendance-upload-dialog"),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("attendance-close"));
    expect(
      screen.queryByTestId("dispatch-attendance-dialog"),
    ).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.some(
        ([, init]) => init?.method && init.method !== "GET",
      ),
    ).toBe(false);
  });
  it("refreshes billing detail and summaries after the receipt owner's save", async () => {
    mount("/UI-13");
    fireEvent.click(await screen.findByTestId("billing-open-81"));
    fireEvent.click(await screen.findByTestId("billing-receipt-add"));
    fireEvent.change(screen.getByTestId("billing-receipt-date"), {
      target: { value: `${seoulMonth()}-01` },
    });
    fireEvent.change(screen.getByTestId("billing-receipt-amount"), {
      target: { value: "30" },
    });
    await waitFor(() =>
      expect(screen.getByTestId("billing-receipt-save")).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId("billing-receipt-save"));
    expect(
      await screen.findByTestId("billing-receipt-success"),
    ).toHaveTextContent("저장했습니다.");
    expect(fetcher.mock.calls).toEqual(
      expect.arrayContaining([
        [
          "/api/billings/81/receipts",
          expect.objectContaining({
            method: "POST",
            body: JSON.stringify({
              rcpt_dt: `${seoulMonth()}-01`,
              rcpt_amt: 30,
              rmrk_desc: null,
            }),
          }),
        ],
      ]),
    );
    await waitFor(() =>
      expect(
        fetcher.mock.calls.filter(([url]) =>
          String(url).startsWith("/api/billings/summary?"),
        ).length,
      ).toBeGreaterThan(1),
    );
    expect(
      fetcher.mock.calls.filter(([url]) => url === "/api/billings/81").length,
    ).toBeGreaterThan(1);
  });
  it.each(["past", "mobile"])(
    "keeps composed billing writes hidden for %s context",
    async (mode) => {
      if (mode === "past")
        sessionStorage.setItem(
          "UI-13.year",
          String(Number(seoulMonth().slice(0, 4)) - 1),
        );
      else
        Object.defineProperty(window, "innerWidth", {
          configurable: true,
          value: 767,
        });
      mount("/UI-13");
      fireEvent.click(await screen.findByTestId("billing-open-81"));
      await screen.findByTestId("billing-receipts-panel");
      expect(
        screen.queryByTestId("billing-attendance"),
      ).not.toBeInTheDocument();
      expect(screen.queryByTestId("billing-create")).not.toBeInTheDocument();
      expect(
        screen.queryByTestId("billing-receipt-add"),
      ).not.toBeInTheDocument();
      expect(screen.getByTestId("billing-export")).toBeVisible();
    },
  );
  it("exposes billing clients without replacing the shared transport", async () => {
    const result = await apiClient.getBilling(81);
    expect(result.billing.bill_id).toBe(81);
    await apiClient.createBillingReceipt(81, {
      rcpt_dt: `${seoulMonth()}-01`,
      rcpt_amt: 30,
      rmrk_desc: null,
    });
    expect(fetcher.mock.calls).toEqual(
      expect.arrayContaining([
        [
          "/api/billings/81/receipts",
          expect.objectContaining({ method: "POST" }),
        ],
      ]),
    );
    expect(apiClient.confirmBilling).toBeTypeOf("function");
    expect(apiClient.cancelBilling).toBeTypeOf("function");
    expect(apiClient.createBillingAmendment).toBeTypeOf("function");
    expect(apiClient.downloadBilling).toBeTypeOf("function");
  });
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
