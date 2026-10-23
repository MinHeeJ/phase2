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
    if (url.startsWith("/api/month-close?")) return response([]);
    if (url.startsWith("/api/codes/")) return response([]);
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
          name: id === "UI-27" ? /공통코드/ : id,
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
  it.each(["/UI-06", "/UI-99", "/UI-27/M3", "/login"])(
    "does not register unsupported route %s",
    async (path) => {
      mount(path);
      expect(
        await screen.findByText("화면을 찾을 수 없습니다."),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("heading", { name: /공통코드 관리/ }),
      ).not.toBeInTheDocument();
    },
  );
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
  it("opens the partner business screen directly and after remount with both tabs on the same URL", async () => {
    const view = mount("/UI-15");
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    expect(screen.getByRole("tab", { name: "거래처" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("button", { name: "등록" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "엑셀 업로드" }));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "엑셀 업로드 · 거래처",
    );
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "취소" }),
    );
    fireEvent.click(screen.getByRole("tab", { name: "영업 파트" }));
    await screen.findByText("조건에 맞는 결과가 없습니다");
    expect(screen.getByRole("button", { name: "파트 추가" })).toBeEnabled();
    expect(screen.getByTestId("sales-part-download")).toBeInTheDocument();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-15");
    fireEvent.click(screen.getByRole("tab", { name: "거래처" }));
    expect(screen.getByRole("button", { name: "등록" })).toBeInTheDocument();
    view.unmount();
    mount("/UI-15");
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    expect(screen.getByRole("tab", { name: "거래처" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(
      fetcher.mock.calls.some(([url]) =>
        String(url).startsWith("/api/partners?"),
      ),
    ).toBe(true);
    expect(
      fetcher.mock.calls.some(([url]) =>
        String(url).startsWith("/api/sales-parts?"),
      ),
    ).toBe(true);
  });
  it("allows UI-15 mobile reads without upload or export controls and keeps the period NONE", async () => {
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      value: 767,
    });
    mount("/UI-15");
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    expect(screen.queryByText("PC에서 이용해 주세요")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "엑셀 업로드" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("partners-export-control"),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "영업 파트" }));
    await screen.findByText("조건에 맞는 결과가 없습니다");
    expect(screen.queryByTestId("sales-part-download")).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.some(([url]) =>
        String(url).startsWith("/api/month-close?"),
      ),
    ).toBe(false);
  });
  it("propagates partner draft state through the shared shell close guard", async () => {
    mount("/UI-15");
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    fireEvent.click(screen.getByRole("button", { name: "등록" }));
    fireEvent.change(await screen.findByLabelText("상호"), {
      target: { value: "작성 중" },
    });
    await waitFor(() =>
      expect(screen.getByLabelText("열린 화면")).toHaveTextContent("● UI-15"),
    );
    fireEvent.click(screen.getByRole("button", { name: "UI-15 닫기" }));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "저장하지 않은 내용이 있습니다.",
    );
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(screen.getByLabelText("상호")).toHaveValue("작성 중");
  });
  it("refreshes the partner list after the registered upload applies without a ledger id", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string) => {
      if (url === "/api/uploads/B.7/validate")
        return response({
          fileName: "partners.xlsx",
          totalRows: 1,
          okRows: 1,
          errorRows: 0,
          warningRows: 0,
          items: [],
          token: "validated-partners",
        });
      if (url === "/api/uploads/validated/validated-partners/apply")
        return response({ upldId: "91", appliedRows: 1 });
      return original(url);
    });
    mount("/UI-15");
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    const reads = () =>
      fetcher.mock.calls.filter(([url]) =>
        String(url).startsWith("/api/partners?"),
      ).length;
    const before = reads();
    fireEvent.click(screen.getByRole("button", { name: "엑셀 업로드" }));
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["test"], "partners.xlsx")] },
    });
    fireEvent.click(screen.getByRole("button", { name: "올리기" }));
    await screen.findByTestId("upload-result-dialog");
    expect(screen.getByRole("button", { name: "다시 검사" })).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "오류 목록 내려받기" }),
    ).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "반영" }));
    await waitFor(() => expect(reads()).toBeGreaterThan(before));
    expect(
      fetcher.mock.calls.some(([url]) => String(url).includes("undefined")),
    ).toBe(false);
  });
  it("connects part actions to the selected detail, dirty guard and list refresh", async () => {
    const original = fetcher.getMockImplementation()!;
    let part = {
      bsn_part_id: "42",
      bsn_part_nm: "제품 영업",
      hq_yn: "N",
      use_yn: "Y",
      sort_seq: 1,
      referenceMonth: "2026-10",
      currentStandard: null,
      scheduledChanges: [],
      peopleCount: 0,
      projectCount: 0,
      standards: [],
      lastClosedMonth: "2026-09",
      firstAllowedMonth: "2026-10",
    };
    fetcher.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith("/api/sales-parts?"))
        return response({ items: [part], total: 1, page: 1, size: 50 });
      if (url === "/api/sales-parts/42/name" && init?.method === "PUT") {
        part = { ...part, ...JSON.parse(String(init.body)) };
        return response(part);
      }
      if (url === "/api/sales-parts/42") return response(part);
      return original(url);
    });
    mount("/UI-15");
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    fireEvent.click(screen.getByRole("tab", { name: "영업 파트" }));
    fireEvent.click(await screen.findByRole("button", { name: "제품 영업" }));
    fireEvent.click(await screen.findByRole("button", { name: "더보기" }));
    fireEvent.click(screen.getByRole("button", { name: "파트명 수정" }));
    fireEvent.change(screen.getByLabelText("파트명"), {
      target: { value: "변경 영업" },
    });
    await waitFor(() =>
      expect(screen.getByLabelText("열린 화면")).toHaveTextContent("● UI-15"),
    );
    fireEvent.click(screen.getByTestId("sales-part-detail-close"));
    expect(
      screen.getByRole("button", { name: "계속 작성" }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "변경 영업" }),
      ).toBeInTheDocument(),
    );
    expect(screen.getByRole("dialog")).toHaveTextContent("변경 영업");
    expect(
      fetcher.mock.calls.filter(([url]) =>
        String(url).startsWith("/api/sales-parts?"),
      ).length,
    ).toBeGreaterThan(1);
  });
  it("recovers a partner read failure through the registered screen", async () => {
    const original = fetcher.getMockImplementation()!;
    let failed = false;
    fetcher.mockImplementation(async (url: string) => {
      if (url.startsWith("/api/partners?") && !failed) {
        failed = true;
        throw new Error("offline");
      }
      return original(url);
    });
    mount("/UI-15");
    fireEvent.click(await screen.findByText("다시 시도"));
    await screen.findByText("조건에 맞는 결과가 없습니다.");
  });
  it("uses the drawer tabs as the single owner of partner activity navigation", async () => {
    const original = fetcher.getMockImplementation()!;
    const partner = {
      ptnr_id: "42",
      ptnr_no: "C-042",
      ptnr_se_cd: "CUST",
      ptnr_nm: "거래처 활동",
      biz_reg_no: "123-45-67890",
      ceo_nm: null,
      indty_cd: null,
      chrg_nm: null,
      chrg_tel_no: null,
      txinv_email_addr: "tax@example.test",
      rmrk_desc: null,
      use_yn: "Y",
      kindName: "고객사",
      industryName: null,
      paymentAccount: null,
      projectCount: 0,
      assignmentPeopleCount: 0,
    };
    fetcher.mockImplementation(async (url: string) => {
      if (url.startsWith("/api/partners?"))
        return response({ items: [partner], total: 1, page: 1, size: 50 });
      if (url === "/api/partners/42") return response(partner);
      if (
        url === "/api/partners/42/assignments" ||
        url === "/api/partners/42/history"
      )
        return response([]);
      return original(url);
    });
    mount("/UI-15");
    fireEvent.click(await screen.findByRole("button", { name: "거래처 활동" }));
    const drawer = await screen.findByTestId("partner-detail-drawer");
    fireEvent.click(
      await within(drawer).findByRole("tab", { name: "투입 내역" }),
    );
    await within(drawer).findByText("지금 투입 중인 내역이 없습니다.");
    expect(
      within(drawer).getAllByRole("tab", { name: "투입 내역" }),
    ).toHaveLength(1);
    expect(
      within(drawer).getAllByTestId("partner-assignments-panel"),
    ).toHaveLength(1);
    fireEvent.click(within(drawer).getByRole("tab", { name: "변경 이력" }));
    await within(drawer).findByText("아직 바꾼 기록이 없습니다.");
    expect(
      within(drawer).getAllByRole("tab", { name: "변경 이력" }),
    ).toHaveLength(1);
    expect(within(drawer).getAllByTestId("partner-history-panel")).toHaveLength(
      1,
    );
    expect(
      within(drawer).getByRole("tab", { name: "변경 이력" }),
    ).toHaveAttribute("aria-selected", "true");
  });
});
