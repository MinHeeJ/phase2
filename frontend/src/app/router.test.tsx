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
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/menus") return response(menus);
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
