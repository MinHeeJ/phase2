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
import { useSharedPreferences } from "../pages/shared/preferences";
import { emptyProject, type ProjectDetail } from "../pages/projects/client";
import { response } from "../pages/developers/DeveloperTestSupport";
import * as client from "../api/apiClient";

const menus = [
  {
    menuId: 1,
    name: "프로젝트·투입",
    screenId: null,
    icon: null,
    mobile: true,
    children: [
      {
        menuId: 2,
        name: "대시보드",
        screenId: "UI-16",
        icon: null,
        mobile: true,
        children: [],
      },
      {
        menuId: 3,
        name: "프로젝트",
        screenId: "UI-01",
        icon: null,
        mobile: true,
        children: [],
      },
      {
        menuId: 4,
        name: "프로젝트 등록",
        screenId: "UI-33",
        icon: null,
        mobile: false,
        children: [],
      },
    ],
  },
];
let project: ProjectDetail;
let fetcher: ReturnType<typeof vi.fn>;
let failStages: boolean;
function Location() {
  return (
    <output aria-label="현재 주소">
      {useLocation().pathname + useLocation().search}
    </output>
  );
}
function mount(path = "/UI-01", state?: { projectCreated: number }) {
  return render(
    <MemoryRouter initialEntries={[state ? { pathname: path, state } : path]}>
      <Location />
      <AppRouter />
    </MemoryRouter>,
  );
}
const tabs = () => within(screen.getByLabelText("열린 화면"));
async function drawer() {
  fireEvent.click(await screen.findByTestId("project-open-41"));
  await screen.findByTestId("project-edit");
}
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  vi.setSystemTime(new Date("2026-10-23T00:00:00Z"));
  localStorage.clear();
  sessionStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  project = {
    ...emptyProject,
    prj_id: 41,
    prj_no: "P-2641",
    prj_nm: "계약 업무",
    cntrct_ty_cd: "TK",
    prj_stts_cd: "RUN",
    cntrct_bgn_dt: "2026-03-01",
    cntrct_end_dt: "2026-12-31",
    cur_end_dt: "2026-12-31",
    cur_cntrct_amt: 1000000,
    init_cntrct_amt: 1000000,
    assignmentCount: 0,
    use_yn: "Y",
    custName: "고객",
    partName: "본사",
    pmName: null,
    bsn_part_id: 1,
    cntrct_mm_qty: 0,
    stlm_mthd_cd: "STAGE",
  };
  failStages = false;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/menus") return response(menus);
    if (url.startsWith("/api/codes/")) return response([]);
    if (url.startsWith("/api/month-close?")) return response([]);
    if (url.startsWith("/api/params/")) return response({ num_val: 80 });
    if (url.startsWith("/api/partners/options")) return response([]);
    if (url === "/api/sales-parts/options")
      return response([{ id: "1", name: "본사", hq: true }]);
    if (url.startsWith("/api/developers/options")) return response([]);
    if (url === "/api/projects/options") return response([]);
    if (url.startsWith("/api/projects?"))
      return response({
        items: project.use_yn === "Y" ? [project] : [],
        total: project.use_yn === "Y" ? 1 : 0,
        page: 0,
        size: 50,
        unassignedCount: 0,
      });
    if (url === "/api/projects/41" || url === "/api/projects/P-2641")
      return response(project);
    if (url === "/api/projects/41/disable") {
      project = { ...project, use_yn: "N" };
      return response(project);
    }
    if (url === "/api/projects/41/enable") {
      project = { ...project, use_yn: "Y" };
      return response(project);
    }
    if (url === "/api/projects/41/bill-stages") {
      if (failStages)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "UNAVAILABLE",
            message: "단계 조회 실패",
            requestId: "stage-request",
          }),
          { status: 500 },
        );
      if (init?.method === "PUT")
        project = { ...project, prj_nm: "갱신된 계약 업무" };
      return response({
        items: [],
        cur_cntrct_amt: 1000000,
        totalSupplyAmount: 0,
      });
    }
    if (url.startsWith("/api/projects/41/allocations"))
      return response({
        aloc_stg_cd: "EST",
        activeStage: null,
        items: [],
        cur_cntrct_amt: 1000000,
        realoc_need_yn: "N",
      });
    if (url === "/api/projects/41/staff-plans")
      return response({ items: [], actual: [] });
    if (url === "/api/projects/41/contract-changes")
      return response({
        cntrct_bgn_dt: project.cntrct_bgn_dt,
        init_cntrct_amt: project.init_cntrct_amt,
        items: [],
      });
    if (
      /^\/api\/projects\/41\/(expenses|part-changes|assignments|history)$/.test(
        url,
      )
    )
      return response([]);
    if (url.startsWith("/api/admin/code-groups/EXPENSE_ITEM/codes?"))
      return response({ items: [], total: 0, page: 1, size: 100 });
    throw new Error(`Unexpected project route request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("project route composition", () => {
  it("registers the real project list and its shared API export", async () => {
    mount();
    expect(await screen.findByTestId("project-row-41")).toHaveTextContent(
      "계약 업무",
    );
    expect(tabs().getByRole("button", { name: "프로젝트" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect((await client.getProject("P-2641")).prj_id).toBe(41);
    expect(
      fetcher.mock.calls.some(([url]) => String(url).includes("/auth/")),
    ).toBe(false);
  });
  it("direct registration uses the project tab and excludes registration from menu and search", async () => {
    mount("/UI-33");
    await screen.findByTestId("project-registration");
    expect(tabs().getByRole("button", { name: "프로젝트" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(tabs().queryByText("프로젝트 등록")).not.toBeInTheDocument();
    expect(useSharedPreferences.getState().recent).toContain("UI-01");
    expect(useSharedPreferences.getState().recent).not.toContain("UI-33");
    expect(
      within(screen.getByLabelText("화면 탐색")).queryByText("프로젝트 등록"),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("화면 검색"));
    fireEvent.change(screen.getByLabelText("화면 이름 또는 ID"), {
      target: { value: "UI-33" },
    });
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "검색 결과가 없습니다.",
    );
  });
  it("keeps registration drafts and list filters in one project tab", async () => {
    mount();
    await screen.findByTestId("project-row-41");
    fireEvent.change(screen.getByTestId("project-query-input"), {
      target: { value: "조회 초안" },
    });
    fireEvent.click(screen.getByTestId("project-create"));
    await screen.findByTestId("project-registration");
    fireEvent.change(screen.getByLabelText("프로젝트명"), {
      target: { value: "등록 초안" },
    });
    await waitFor(() =>
      expect(
        tabs().getByRole("button", { name: "● 프로젝트" }),
      ).toBeInTheDocument(),
    );
    fireEvent.click(tabs().getByRole("button", { name: "대시보드" }));
    fireEvent.click(tabs().getByRole("button", { name: "● 프로젝트" }));
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-33");
    expect(screen.getByLabelText("프로젝트명")).toHaveValue("등록 초안");
    fireEvent.click(screen.getByLabelText("프로젝트 닫기"));
    expect(screen.getByRole("dialog")).toHaveTextContent("저장하지 않은 내용");
    fireEvent.click(screen.getByText("계속 작성"));
    fireEvent.click(screen.getByTestId("project-back"));
    fireEvent.click(screen.getByText("나가기"));
    expect(screen.getByTestId("project-query-input")).toHaveValue("조회 초안");
  });
  it("resolves disabled canonical deep links without guessing a numeric ID", async () => {
    project.use_yn = "N";
    mount("/UI-01?open=P-2641");
    await screen.findByText("사용 안 함으로 바꾼 프로젝트입니다.");
    expect(
      fetcher.mock.calls.some(([url]) => url === "/api/projects/P-2641"),
    ).toBe(true);
    expect(screen.queryByTestId("project-edit")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("project-more"));
    fireEvent.click(screen.getByTestId("project-enable"));
    await waitFor(() =>
      expect(screen.getByTestId("project-row-41")).toBeInTheDocument(),
    );
  });
  it("mounts only the selected business panel with the selected project ID", async () => {
    mount();
    await drawer();
    expect(
      fetcher.mock.calls.some(([url]) => String(url).endsWith("/bill-stages")),
    ).toBe(false);
    for (const [key, testId] of [
      ["bill-stages", "bill-stages-panel"],
      ["allocations", "project-allocation-panel"],
      ["expenses", "project-expense-panel"],
      ["contract-changes", "contract-change-panel"],
      ["staff-plans", "staff-plans-panel"],
    ]) {
      fireEvent.click(screen.getByTestId(`project-tab-${key}`));
      expect(await screen.findByTestId(testId)).toBeVisible();
      expect(
        screen.queryByTestId("project-panel-basic"),
      ).not.toBeInTheDocument();
    }
    expect(screen.queryByTestId("bill-stages-panel")).not.toBeInTheDocument();
  });
  it.each(["MO", "DP", "IN"])(
    "omits turnkey-only tabs for %s but keeps expenses and staff plans",
    async (type) => {
      project.cntrct_ty_cd = type;
      mount();
      await drawer();
      expect(
        screen.queryByTestId("project-tab-bill-stages"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByTestId("project-tab-allocations"),
      ).not.toBeInTheDocument();
      expect(screen.getByTestId("project-tab-expenses")).toBeVisible();
      expect(screen.getByTestId("project-tab-staff-plans")).toBeVisible();
    },
  );
  it("connects the part action to its existing local dialog", async () => {
    mount();
    await drawer();
    fireEvent.click(screen.getByTestId("project-part-change"));
    expect(
      await screen.findByTestId("project-part-change-dialog"),
    ).toBeVisible();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-01");
  });
  it("guards dirty panel tab changes and drawer close without losing the draft", async () => {
    mount();
    await drawer();
    fireEvent.click(screen.getByTestId("project-tab-bill-stages"));
    fireEvent.click(await screen.findByTestId("bill-stage-add"));
    fireEvent.click(screen.getByTestId("project-tab-expenses"));
    expect(
      screen.getByRole("dialog", { name: "저장하지 않은 내용이 있습니다" }),
    ).toBeVisible();
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByTestId("project-panel-bill-stages")).toBeVisible();
    fireEvent.click(screen.getByTestId("project-drawer-close"));
    expect(
      screen.getByRole("dialog", { name: "저장하지 않은 내용이 있습니다" }),
    ).toBeVisible();
    fireEvent.click(screen.getByText("나가기"));
    expect(screen.queryByTestId("project-drawer")).not.toBeInTheDocument();
  });
  it("refreshes detail and list after a panel save", async () => {
    mount();
    await drawer();
    fireEvent.click(screen.getByTestId("project-tab-bill-stages"));
    await screen.findByTestId("bill-stages-panel");
    await waitFor(() =>
      expect(screen.getByTestId("bill-stage-save")).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId("bill-stage-save"));
    await waitFor(() =>
      expect(screen.getByTestId("project-row-41")).toHaveTextContent(
        "갱신된 계약 업무",
      ),
    );
    expect(
      within(screen.getByTestId("project-drawer")).getByRole("heading", {
        name: "갱신된 계약 업무",
      }),
    ).toBeVisible();
  });
  it("retains a registered panel's error and retry behavior", async () => {
    failStages = true;
    mount();
    await drawer();
    fireEvent.click(screen.getByTestId("project-tab-bill-stages"));
    expect(await screen.findByText("단계 조회 실패")).toBeVisible();
    failStages = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await waitFor(() =>
      expect(screen.queryByText("단계 조회 실패")).not.toBeInTheDocument(),
    );
  });
  it.each([false, true])(
    "disable refreshes the list and open detail (newly created: %s)",
    async (created) => {
      mount("/UI-01", created ? { projectCreated: 41 } : undefined);
      if (created) {
        await waitFor(() =>
          expect(screen.getByTestId("project-row-41").className).toContain(
            "highlight",
          ),
        );
      }
      await drawer();
      fireEvent.click(screen.getByTestId("project-more"));
      fireEvent.click(screen.getByTestId("project-disable-open"));
      fireEvent.click(screen.getByTestId("project-disable-confirm"));
      await waitFor(() =>
        expect(
          within(screen.getByTestId("project-drawer")).getAllByText(
            "사용 안 함으로 바꾼 프로젝트입니다.",
          ).length,
        ).toBeGreaterThan(0),
      );
      await waitFor(() =>
        expect(screen.queryByTestId("project-row-41")).not.toBeInTheDocument(),
      );
    },
  );
  it("mobile direct registration shows guidance and returns to the read-only project list", async () => {
    vi.stubGlobal("innerWidth", 767);
    mount("/UI-33");
    expect(
      await screen.findByTestId("project-registration-mobile"),
    ).toBeVisible();
    expect(screen.queryByTestId("project-form")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("project-return-list"));
    await screen.findByTestId("project-row-41");
    expect(screen.queryByTestId("project-create")).not.toBeInTheDocument();
    expect(screen.queryByTestId("project-upload")).not.toBeInTheDocument();
  });
});
