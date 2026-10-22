import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { Link, MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DataTable } from "../src/components/UiKit";
import { AppFrame } from "../src/app/AppFrame";
import { SharedWorkspace } from "../src/pages/shared/SharedWorkspace";
import { ExcelMapsTab } from "../src/pages/admin/ExcelMapsTab";
import { useSharedPreferences } from "../src/pages/shared/preferences";

function json(data: unknown) {
  return new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
  });
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
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
});
afterEach(() => vi.unstubAllGlobals());

describe("개선된 데이터 상태", () => {
  it("loading과 실패를 성공한 0건과 구분한다", () => {
    const props = {
      columns: [
        {
          key: "name",
          title: "이름",
          render: (row: { name: string }) => row.name,
        },
      ],
      rows: [],
      total: 0,
      rowKey: (row: { name: string }) => row.name,
    };
    const view = render(<DataTable {...props} loading />);
    expect(
      screen.getByRole("status", { name: "목록 불러오는 중" }),
    ).toHaveAttribute("aria-busy", "true");
    expect(screen.queryByText("총 0건")).not.toBeInTheDocument();
    expect(screen.queryByText(/조회 결과가 없습니다/)).not.toBeInTheDocument();
    view.rerender(<DataTable {...props} unavailable />);
    expect(screen.getByText("목록을 확인할 수 없습니다")).toBeInTheDocument();
    expect(screen.queryByText("총 0건")).not.toBeInTheDocument();
    view.rerender(
      <DataTable {...props} emptyMessage="조건에 맞는 결과가 없습니다" />,
    );
    expect(screen.getByText("총 0건")).toBeInTheDocument();
    expect(screen.getByText("조건에 맞는 결과가 없습니다")).toBeInTheDocument();
  });
  it("대응 API 대기·실패·읽기 재시도 뒤 빈 결과를 차례로 렌더링한다", async () => {
    let reject!: (reason: Error) => void;
    const fetcher = vi
      .fn()
      .mockReturnValueOnce(
        new Promise<Response>((_, fail) => {
          reject = fail;
        }),
      )
      .mockResolvedValue(json({ items: [], total: 0, page: 1, size: 50 }));
    vi.stubGlobal("fetch", fetcher);
    render(<ExcelMapsTab groups={[]} onDirty={vi.fn()} />);
    expect(
      screen.queryByText("조건에 맞는 결과가 없습니다"),
    ).not.toBeInTheDocument();
    await act(async () => reject(new TypeError("network")));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "문의 번호: 서버 연결 전 실패",
    );
    expect(
      screen.queryByText("조건에 맞는 결과가 없습니다"),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls.every(([, options]) => !options.method)).toBe(
      true,
    );
  });
});

describe("내비게이션과 비인증 사용자 UX", () => {
  it("펼친 모바일 메뉴에서 실제 링크를 고르면 메뉴를 닫는다", () => {
    render(
      <MemoryRouter>
        <AppFrame
          header="프로젝트·원가 관리"
          navigation={<Link to="/UI-16">대시보드로</Link>}
        >
          <p>본문</p>
        </AppFrame>
      </MemoryRouter>,
    );
    const toggle = screen.getByRole("button", { name: "메뉴 열기" });
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    fireEvent.click(screen.getByRole("link", { name: "대시보드로" }));
    expect(toggle).toHaveAttribute("aria-expanded", "false");
  });
  it("태블릿은 개인 PC 접힘 설정과 무관하게 rail에서 시작한다", () => {
    vi.mocked(window.matchMedia).mockImplementation(
      (query) =>
        ({
          matches: query.includes("min-width: 768px"),
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
        }) as unknown as MediaQueryList,
    );
    render(
      <AppFrame
        collapsed={false}
        header="앱"
        navigation={<a href="/UI-16">대시보드</a>}
      >
        본문
      </AppFrame>,
    );
    expect(screen.getByTestId("app-frame")).toHaveAttribute(
      "data-navigation-collapsed",
      "true",
    );
    fireEvent.click(screen.getByRole("button", { name: "메뉴 열기·접기" }));
    expect(screen.getByTestId("app-frame")).toHaveAttribute(
      "data-navigation-collapsed",
      "false",
    );
  });
  it("무인증 메뉴·검색·즐겨찾기가 동작하며 검색 0건도 안내한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        json([
          {
            menuId: 1,
            name: "대시보드",
            screenId: null,
            icon: "grid",
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
            ],
          },
        ]),
      ),
    );
    render(
      <MemoryRouter initialEntries={["/UI-16"]}>
        <SharedWorkspace />
      </MemoryRouter>,
    );
    expect(
      await screen.findByRole("heading", { name: "대시보드" }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText(/비밀번호/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "즐겨찾기 추가" }));
    expect(
      screen.getByRole("button", { name: "즐겨찾기 해제" }),
    ).toHaveAttribute("aria-pressed", "true");
    expect(localStorage.getItem("si.favorites")).toContain("UI-16");
    fireEvent.click(screen.getByRole("button", { name: "화면 검색 열기" }));
    const dialog = within(screen.getByRole("dialog", { name: "화면 검색" }));
    fireEvent.change(dialog.getByRole("textbox"), {
      target: { value: "존재하지않는화면" },
    });
    expect(dialog.getByText("조건에 맞는 결과가 없습니다")).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "알림" }));
    expect(screen.getByRole("status")).toHaveTextContent("새 알림이 없습니다");
  });
});
