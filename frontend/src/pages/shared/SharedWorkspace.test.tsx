import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import {
  ScreenTitleAction,
  SharedWorkspace,
  useSharedScreenState,
} from "./SharedWorkspace";
import {
  SharedPlaceholderScreen,
  seoulCurrentMonth,
  sharedPeriodType,
} from "./SharedPlaceholderScreen";
import { getMenus, getMonthClose, type SharedMenuNode } from "./sharedApi";
import { ApiFailure } from "../../shared/http";
import { usePreferences } from "../../shared/preferences";

vi.mock("./sharedApi", async (original) => ({
  ...(await original<typeof import("./sharedApi")>()),
  getMenus: vi.fn(),
  getMonthClose: vi.fn(),
}));
const leaf = (
  screenId: string,
  name: string,
  mobile = true,
): SharedMenuNode => ({
  menuId: Number(screenId.slice(3)),
  name,
  screenId,
  mobile,
  icon: null,
  children: [],
});
const menu: SharedMenuNode[] = [
  {
    menuId: 1,
    name: "대시보드 묶음",
    screenId: null,
    icon: "grid",
    mobile: true,
    children: [leaf("UI-16", "대시보드")],
  },
  {
    menuId: 2,
    name: "프로젝트 묶음",
    screenId: null,
    icon: "folder",
    mobile: true,
    children: [
      leaf("UI-01", "프로젝트"),
      leaf("UI-03", "개인 원가"),
      leaf("UI-19", "영업 파트 이익금"),
    ],
  },
  {
    menuId: 10,
    name: "시스템",
    screenId: null,
    icon: "gear",
    mobile: true,
    children: [leaf("UI-27", "공통코드 관리", false)],
  },
];
const now = new Date("2026-10-01T00:00:00+09:00");
function mount(
  path = "/UI-16",
  renderScreen?: (id: string) => React.ReactNode,
) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SharedWorkspace renderScreen={renderScreen} />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  localStorage.clear();
  usePreferences
    .getState()
    .update({ collapsed: false, dense: false, pageSize: 50 });
  vi.mocked(getMenus).mockReset().mockResolvedValue(menu);
  vi.mocked(getMonthClose)
    .mockReset()
    .mockImplementation(async (from) => [{ month: from, status: "OPEN" }]);
  vi.stubGlobal(
    "matchMedia",
    vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }),
  );
});

describe("자리 화면 기간 계약", () => {
  it("Seoul 현재 연월과 화면별 기간 종류를 적용한다", () => {
    expect(seoulCurrentMonth(new Date("2026-09-30T15:00:00Z"))).toBe("2026-10");
    expect(sharedPeriodType("UI-01")).toBe("YEAR");
    expect(sharedPeriodType("UI-19")).toBe("MONTH");
    expect(sharedPeriodType("UI-07")).toBe("YEAR_CUM");
    expect(sharedPeriodType("UI-29")).toBe("NONE");
  });
  it("YEAR 이전 다음 이동은 업무 입력을 만들지 않는다", () => {
    render(<SharedPlaceholderScreen screenId="UI-01" now={now} />);
    fireEvent.click(screen.getByRole("button", { name: "다음 연도" }));
    expect(screen.getByText("2027년")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "이전 연도" }));
    expect(screen.getByText("2026년")).toBeInTheDocument();
    expect(
      screen.getByText("이 화면은 다음 단계에서 만듭니다."),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("누계월 선택과 안내가 일치한다", () => {
    render(<SharedPlaceholderScreen screenId="UI-07" now={now} />);
    fireEvent.change(screen.getByRole("combobox", { name: "누계월" }), {
      target: { value: "3" },
    });
    expect(screen.getByText("1~3월 누계")).toBeInTheDocument();
  });
  it("NONE 화면에는 기간 입력이나 로그인 폼을 만들지 않는다", () => {
    render(<SharedPlaceholderScreen screenId="UI-22" now={now} />);
    expect(screen.queryByLabelText("조회 기간")).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });
  it("CLOSED 월은 회색 마감 배지와 잠금 안내를 표시한다", async () => {
    vi.mocked(getMonthClose).mockResolvedValue([
      { month: "2026-10", status: "CLOSED" },
    ]);
    render(<SharedPlaceholderScreen screenId="UI-19" now={now} />);
    expect(await screen.findByText("마감")).toBeInTheDocument();
    expect(screen.getByText(/2026-10는 마감되었습니다/)).toBeInTheDocument();
    expect(getMonthClose).toHaveBeenCalledWith("2026-10", "2026-10");
  });
  it("OPEN 월은 작업중으로 표시한다", async () => {
    render(<SharedPlaceholderScreen screenId="UI-19" now={now} />);
    expect(await screen.findByText("작업중")).toBeInTheDocument();
    expect(screen.queryByText(/마감되었습니다/)).not.toBeInTheDocument();
  });
  it("월 조회 오류에 문의 번호와 재시도를 표시하고 선택월을 유지한다", async () => {
    vi.mocked(getMonthClose).mockRejectedValueOnce(
      new ApiFailure(
        {
          success: false,
          meta: {},
          code: "INTERNAL_ERROR",
          message: "처리 중 오류가 발생했습니다.",
          requestId: "month-1",
        },
        500,
      ),
    );
    render(<SharedPlaceholderScreen screenId="UI-19" now={now} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "문의 번호: month-1",
    );
    expect(screen.getByLabelText("조회월")).toHaveValue("2026-10");
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("작업중")).toBeInTheDocument();
  });
  it("이전 월의 늦은 응답이 새 선택월 상태를 덮지 않는다", async () => {
    let finish!: (value: { month: string; status: "CLOSED" }[]) => void;
    vi.mocked(getMonthClose).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    render(<SharedPlaceholderScreen screenId="UI-19" now={now} />);
    fireEvent.click(screen.getByRole("button", { name: "다음 월" }));
    expect(await screen.findByText("작업중")).toBeInTheDocument();
    finish([{ month: "2026-10", status: "CLOSED" }]);
    await waitFor(() =>
      expect(screen.getByLabelText("조회월")).toHaveValue("2026-11"),
    );
    expect(screen.queryByText("마감")).not.toBeInTheDocument();
  });
});

describe("공유 shell과 메뉴 화면", () => {
  it("화면 동작은 본문과 분리해 공통 제목줄 우측에 연결한다", async () => {
    mount("/UI-27", (id) =>
      id === "UI-27" ? (
        <ScreenTitleAction>
          <button>그룹 추가</button>
        </ScreenTitleAction>
      ) : undefined,
    );
    const heading = await screen.findByRole("heading", {
      level: 1,
      name: "공통코드 관리",
    });
    await waitFor(() =>
      expect(heading.closest("header")).toContainElement(
        screen.getByRole("button", { name: "그룹 추가" }),
      ),
    );
  });
  it("화면 검색 결과가 없으면 빈 상태를 표시한다", async () => {
    mount();
    await screen.findByRole("heading", { level: 1, name: "대시보드" });
    fireEvent.click(screen.getByRole("button", { name: "화면 검색" }));
    fireEvent.change(screen.getByRole("textbox", { name: "화면명" }), {
      target: { value: "없는화면검색" },
    });
    expect(screen.getByText("조건에 맞는 결과가 없습니다")).toBeVisible();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("태블릿은 기본 접힘에서 메뉴 토글로 펼칠 수 있다", async () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn((query: string) => ({
        matches: query.includes("min-width: 768px"),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    );
    mount();
    await screen.findByRole("heading", { level: 1, name: "대시보드" });
    const navigation = screen.getByRole("navigation", { name: "전체 메뉴" });
    const compactClass = navigation.className;
    fireEvent.click(screen.getByRole("button", { name: "메뉴 접기/펼치기" }));
    expect(navigation.className).not.toBe(compactClass);
    fireEvent.click(screen.getByRole("button", { name: "메뉴 접기/펼치기" }));
    expect(navigation.className).toBe(compactClass);
  });
  it.each([
    "UI-01",
    "UI-02",
    "UI-03",
    "UI-04",
    "UI-05",
    "UI-07",
    "UI-08",
    "UI-09",
    "UI-10",
    "UI-11",
    "UI-12",
    "UI-13",
    "UI-14",
    "UI-15",
    "UI-16",
    "UI-17",
    "UI-18",
    "UI-19",
    "UI-20",
    "UI-21",
    "UI-23",
    "UI-24",
    "UI-25",
    "UI-26",
    "UI-28",
    "UI-29",
    "UI-30",
    "UI-31",
    "UI-32",
    "UI-33",
  ])("DB leaf %s를 공통 자리 화면으로 연다", async (id) => {
    vi.mocked(getMenus).mockResolvedValue([
      { ...menu[1], children: [leaf(id, `화면 ${id}`)] },
    ]);
    mount(`/${id}`);
    expect(
      await screen.findByRole("heading", { level: 1, name: `화면 ${id}` }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("이 화면은 다음 단계에서 만듭니다."),
    ).toBeInTheDocument();
  });
  it("메뉴 로딩 상태와 API에서 가져온 제목과 경로를 표시한다", async () => {
    mount("/UI-01");
    expect(
      screen.getByRole("status", { name: "메뉴 불러오는 중" }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("heading", { level: 1, name: "프로젝트" }),
    ).toBeInTheDocument();
    expect(screen.getByText("프로젝트 묶음 / 프로젝트")).toBeInTheDocument();
  });
  it("빈 메뉴는 재시도 가능한 빈 상태다", async () => {
    vi.mocked(getMenus).mockResolvedValue([]);
    mount();
    expect(
      await screen.findByText("표시할 메뉴가 없습니다."),
    ).toBeInTheDocument();
  });
  it("메뉴 조회 실패는 오류 띠 후 재시도하고 가짜 문의 번호를 만들지 않는다", async () => {
    vi.mocked(getMenus).mockRejectedValueOnce(
      new Error("서버에 연결할 수 없습니다."),
    );
    mount();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "서버에 연결할 수 없습니다.",
    );
    expect(screen.queryByText(/문의 번호/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "대시보드" }),
    ).toBeInTheDocument();
  });
  it("UI-22는 DB 메뉴에 추가하지 않고 직접 읽기 전용 자리로 표시한다", async () => {
    mount("/UI-22");
    expect(
      await screen.findByRole("heading", { level: 1, name: "UI-22" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "UI-22" }),
    ).not.toBeInTheDocument();
  });
  it("모바일 비노출 화면은 PC 안내와 대시보드로만 표시한다", async () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn().mockReturnValue({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }),
    );
    mount("/UI-27");
    expect(await screen.findByText(/PC에서 이용해 주세요/)).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "공통코드 관리" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { level: 1, name: "공통코드 관리" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "대시보드로" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "대시보드" }),
    ).toBeInTheDocument();
  });
  it("즐겨찾기와 사용자 설정만 si. 저장소로 기록한다", async () => {
    mount("/UI-01");
    await screen.findByRole("heading", { level: 1, name: "프로젝트" });
    fireEvent.click(screen.getByRole("button", { name: "즐겨찾기" }));
    expect(JSON.parse(localStorage.getItem("si.favorites")!)).toEqual([
      "UI-01",
    ]);
    fireEvent.click(screen.getByRole("button", { name: "기본 사용자" }));
    fireEvent.change(screen.getByRole("combobox", { name: "목록 기본 건수" }), {
      target: { value: "100" },
    });
    fireEvent.click(screen.getByRole("checkbox", { name: "좁게 보기" }));
    expect(JSON.parse(localStorage.getItem("si.preferences")!)).toMatchObject({
      pageSize: 100,
      dense: true,
    });
  });
  it("알림은 데이터 없이 지정 문구만 표시하고 Ctrl+K와 Esc로 검색한다", async () => {
    mount();
    await screen.findByRole("heading", { level: 1, name: "대시보드" });
    fireEvent.click(screen.getByRole("button", { name: "알림" }));
    expect(screen.getByText("새 알림이 없습니다")).toBeInTheDocument();
    fireEvent.keyDown(document, { key: "k", ctrlKey: true });
    expect(
      screen.getByRole("dialog", { name: "화면 검색" }),
    ).toBeInTheDocument();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("탭 전환 후 각 화면의 기간을 보존하고 대시보드는 닫지 않는다", async () => {
    mount("/UI-01");
    await screen.findByRole("heading", { level: 1, name: "프로젝트" });
    const year = screen.getByText(/^[0-9]+년$/).textContent;
    fireEvent.click(screen.getByRole("button", { name: "다음 연도" }));
    const changed = screen.getByText(/^[0-9]+년$/).textContent;
    expect(changed).not.toBe(year);
    fireEvent.click(screen.getByRole("tab", { name: "대시보드" }));
    fireEvent.click(screen.getByRole("tab", { name: "프로젝트" }));
    expect(screen.getByText(changed!)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "대시보드 탭 닫기" }),
    ).not.toBeInTheDocument();
  });
  it("dirty 편집은 보존 탭 전환 시 유지하고 닫을 때 계속 작성/나가기를 묻는다", async () => {
    function Editor() {
      const { setDirty } = useSharedScreenState();
      return <input aria-label="편집 값" onChange={() => setDirty(true)} />;
    }
    mount("/UI-27", (id) => (id === "UI-27" ? <Editor /> : undefined));
    const input = await screen.findByLabelText("편집 값");
    fireEvent.change(input, { target: { value: "유지할 값" } });
    fireEvent.click(screen.getByRole("tab", { name: "대시보드" }));
    fireEvent.click(screen.getByRole("tab", { name: "● 공통코드 관리" }));
    expect(screen.getByLabelText("편집 값")).toHaveValue("유지할 값");
    fireEvent.click(
      screen.getByRole("button", { name: "공통코드 관리 탭 닫기" }),
    );
    expect(
      screen.getByText("저장하지 않은 내용이 있습니다. 나가면 사라집니다."),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(screen.getByLabelText("편집 값")).toHaveValue("유지할 값");
    fireEvent.click(
      screen.getByRole("button", { name: "공통코드 관리 탭 닫기" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    await waitFor(() =>
      expect(screen.queryByLabelText("편집 값")).not.toBeInTheDocument(),
    );
  });
  it("9번째 화면은 오래 안 본 탭 닫기 확인 후 연다", async () => {
    const ids = [
      "UI-01",
      "UI-02",
      "UI-03",
      "UI-04",
      "UI-05",
      "UI-07",
      "UI-08",
      "UI-09",
    ];
    vi.mocked(getMenus).mockResolvedValue([
      menu[0],
      { ...menu[1], children: ids.map((id) => leaf(id, `화면 ${id}`)) },
    ]);
    mount();
    await screen.findByRole("heading", { level: 1, name: "대시보드" });
    fireEvent.click(screen.getByRole("button", { name: "프로젝트 묶음" }));
    for (const id of ids)
      fireEvent.click(screen.getByRole("link", { name: `화면 ${id}` }));
    expect(
      await screen.findByRole("dialog", { name: "열린 화면이 8개입니다." }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "확인" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "화면 UI-09" }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole("tab")).toHaveLength(8);
    expect(
      screen.queryByRole("tab", { name: "화면 UI-01" }),
    ).not.toBeInTheDocument();
  });
});
