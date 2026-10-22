import { act, render, screen, fireEvent, within } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApplicationRouter, ApplicationRoutes } from "../src/app/router";
import { screenIds } from "../src/pages/shared/registry";
import { useSharedPreferences } from "../src/pages/shared/preferences";
import type { MenuNode } from "../src/services/types";

const leaves: MenuNode[] = screenIds
  .filter((id) => id !== "UI-22")
  .map((id, index) => ({
    menuId: index + 11,
    name: id === "UI-27" ? "공통코드" : `화면 ${id}`,
    screenId: id,
    icon: null,
    mobile: ![
      "UI-10",
      "UI-14",
      "UI-20",
      "UI-23",
      "UI-24",
      "UI-25",
      "UI-27",
    ].includes(id),
    children: [],
  }));
const menus: MenuNode[] = [
  {
    menuId: 1,
    name: "메뉴 묶음",
    screenId: null,
    icon: "db",
    mobile: true,
    children: leaves,
  },
];
function json(data: unknown) {
  return new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
  });
}
function Location() {
  return <output aria-label="현재 주소">{useLocation().pathname}</output>;
}
function open(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ApplicationRoutes />
      <Location />
    </MemoryRouter>,
  );
}
function searchFor(id: string) {
  fireEvent.keyDown(window, { key: "k", ctrlKey: true });
  const dialog = within(screen.getByRole("dialog"));
  fireEvent.change(dialog.getByRole("textbox"), { target: { value: id } });
  fireEvent.click(
    dialog.getByRole("button", {
      name: `${id === "UI-27" ? "공통코드" : `화면 ${id}`} · ${id}`,
    }),
  );
}
beforeEach(() => {
  localStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    value: vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string) => {
      if (path === "/api/menus") return json(menus);
      if (path.startsWith("/api/admin/code-groups?"))
        return json({
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
      if (path.startsWith("/api/admin/code-groups/JOB/codes?"))
        return json({
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
      return json([]);
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("공통 라우터 등록", () => {
  it.each(screenIds)("%s 직접 진입은 기존 shell과 화면을 연결", async (id) => {
    open(`/${id}`);
    const panel = within(
      await screen.findByRole("region", { name: `${id} 화면` }),
    );
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(`/${id}`);
    expect(
      screen.getByRole("navigation", { name: "업무 메뉴" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("navigation", { name: "현재 위치" }),
    ).toHaveTextContent(id === "UI-27" ? "공통코드" : id);
    if (id === "UI-27") {
      expect(await panel.findByDisplayValue("백엔드")).toBeInTheDocument();
      expect(panel.getAllByRole("tab")).toHaveLength(4);
      expect(
        panel.queryByText("이 화면은 다음 단계에서 만듭니다."),
      ).not.toBeInTheDocument();
    } else
      expect(
        panel.getByText("이 화면은 다음 단계에서 만듭니다."),
      ).toBeInTheDocument();
    if (id === "UI-22")
      expect(
        screen.queryByRole("link", { name: "UI-22" }),
      ).not.toBeInTheDocument();
  });
  it.each(["/", "/UI-06", "/UI-27/M3", "/UI-27-M5"])(
    "%s는 별도 화면 없이 대시보드로 이동",
    async (path) => {
      open(path);
      await screen.findByRole("heading", { name: "화면 UI-16" });
      expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-16");
    },
  );
  it("실제 BrowserRouter는 현재 URL에서 기동", async () => {
    window.history.replaceState(null, "", "/UI-03");
    render(<ApplicationRouter />);
    expect(
      await screen.findByRole("heading", { name: "화면 UI-03" }),
    ).toBeInTheDocument();
    expect(window.location.pathname).toBe("/UI-03");
  });
  it("연결된 UI27 draft는 일반 탭 왕복에 유지되고 닫기만 확인", async () => {
    open("/UI-27");
    await screen.findByDisplayValue("백엔드");
    fireEvent.change(screen.getByDisplayValue("백엔드"), {
      target: { value: "유지할 초안" },
    });
    expect(
      await screen.findByRole("button", { name: "● 공통코드" }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "화면 UI-16" }));
    await screen.findByRole("heading", { name: "화면 UI-16" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "● 공통코드" }));
    expect(screen.getByDisplayValue("유지할 초안")).toBeVisible();
    fireEvent.click(screen.getByLabelText("UI-27 닫기"));
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(screen.getByDisplayValue("유지할 초안")).toBeVisible();
    fireEvent.click(screen.getByLabelText("UI-27 닫기"));
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    await screen.findByRole("heading", { name: "화면 UI-16" });
    expect(screen.queryByDisplayValue("유지할 초안")).not.toBeInTheDocument();
  });
  it("dirty UI27 LRU 교체는 초과 확인 뒤 미저장 확인", async () => {
    open("/UI-27");
    await screen.findByDisplayValue("백엔드");
    fireEvent.change(screen.getByDisplayValue("백엔드"), {
      target: { value: "유지할 초안" },
    });
    for (const id of ["UI-01", "UI-02", "UI-03", "UI-04", "UI-05", "UI-07"]) {
      searchFor(id);
      await screen.findByRole("heading", { name: `화면 ${id}` });
    }
    searchFor("UI-08");
    expect(screen.getByRole("dialog")).toHaveTextContent("공통코드");
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    });
    expect(screen.getByRole("dialog")).toHaveTextContent("저장하지 않은 내용");
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    });
    expect(screen.getByDisplayValue("유지할 초안")).toBeInTheDocument();
    searchFor("UI-08");
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    });
    expect(screen.getByRole("dialog")).toHaveTextContent("저장하지 않은 내용");
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    });
    await screen.findByRole("heading", { name: "화면 UI-08" });
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-08");
    expect(screen.queryByLabelText("UI-27 닫기")).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue("유지할 초안")).not.toBeInTheDocument();
  });
  it("휴대폰 UI27 진입은 업무 조회 없이 기존 PC 안내", async () => {
    vi.mocked(window.matchMedia).mockReturnValue({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    } as unknown as MediaQueryList);
    open("/UI-27");
    expect(await screen.findByText("PC에서 이용해 주세요")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "대시보드로" })).toHaveAttribute(
      "href",
      "/UI-16",
    );
    expect(
      vi.mocked(fetch).mock.calls.every((call) => call[0] === "/api/menus"),
    ).toBe(true);
  });
  it("메뉴 오류는 등록된 화면을 숨기고 조회 재시도로 복구", async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError("network"));
    open("/UI-27");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "문의 번호: 서버 연결 전 실패",
    );
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByDisplayValue("백엔드")).toBeInTheDocument();
  });
});
