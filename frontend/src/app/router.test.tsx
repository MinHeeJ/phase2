import {
  render,
  screen,
  fireEvent,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, useLocation } from "react-router-dom";
import { ApplicationRouter } from "./router";
import {
  getMenus,
  getMonthClose,
  type SharedMenuNode,
} from "../pages/shared/sharedApi";
import { listCodeGroups } from "../pages/admin/adminApi";
import { usePreferences } from "../shared/preferences";

vi.mock("../pages/shared/sharedApi", async (original) => ({
  ...(await original<typeof import("../pages/shared/sharedApi")>()),
  getMenus: vi.fn(),
  getMonthClose: vi.fn(),
}));
vi.mock("../pages/admin/adminApi", async (original) => ({
  ...(await original<typeof import("../pages/admin/adminApi")>()),
  listCodeGroups: vi.fn(),
}));
const placeholderIds = [
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
];
const leaf = (screenId: string): SharedMenuNode => ({
  menuId: Number(screenId.slice(3)),
  name: `화면 ${screenId}`,
  screenId,
  mobile: screenId !== "UI-27",
  icon: null,
  children: [],
});
const menu: SharedMenuNode[] = [
  {
    menuId: 100,
    name: "메뉴 묶음",
    screenId: null,
    mobile: true,
    icon: null,
    children: [...placeholderIds, "UI-27"].map(leaf),
  },
];
function Location() {
  return <output aria-label="현재 경로">{useLocation().pathname}</output>;
}
function mount(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ApplicationRouter />
      <Location />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
  usePreferences
    .getState()
    .update({ collapsed: false, dense: false, pageSize: 50 });
  vi.mocked(getMenus).mockResolvedValue(menu);
  vi.mocked(getMonthClose).mockImplementation(async (from) => [
    { month: from, status: "OPEN" },
  ]);
  vi.mocked(listCodeGroups).mockResolvedValue({
    items: [],
    total: 0,
    page: 0,
    size: 100,
  });
  vi.stubGlobal(
    "matchMedia",
    vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

describe("애플리케이션 경로 등록", () => {
  it.each(placeholderIds)(
    "%s는 DB 메뉴 제목과 기존 자리 화면을 사용한다",
    async (id) => {
      mount(`/${id}`);
      expect(
        await screen.findByRole("heading", { level: 1, name: `화면 ${id}` }),
      ).toBeInTheDocument();
      expect(
        within(screen.getByRole("tabpanel", { name: `화면 ${id}` })).getByText(
          "이 화면은 다음 단계에서 만듭니다.",
        ),
      ).toBeVisible();
      expect(
        screen.queryByRole("tab", { name: "코드" }),
      ).not.toBeInTheDocument();
    },
  );
  it("루트 주소를 대시보드로 바꾼다", async () => {
    mount("/");
    await waitFor(() =>
      expect(screen.getByLabelText("현재 경로")).toHaveTextContent("/UI-16"),
    );
    expect(
      await screen.findByRole("heading", { level: 1, name: "화면 UI-16" }),
    ).toBeInTheDocument();
  });
  it("UI-27은 두 번째 shell 없이 실제 코드 관리 화면을 연결한다", async () => {
    mount("/UI-27");
    expect(
      await screen.findByRole("tab", { name: "코드" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("tab", { name: "엑셀 표기 대응" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "기준값" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "변경 이력" })).toBeInTheDocument();
    expect(
      screen.getAllByRole("navigation", { name: "전체 메뉴" }),
    ).toHaveLength(1);
    expect(
      within(screen.getByRole("tabpanel", { name: "화면 UI-27" })).queryByText(
        "이 화면은 다음 단계에서 만듭니다.",
      ),
    ).not.toBeInTheDocument();
    await waitFor(() => expect(listCodeGroups).toHaveBeenCalledWith(0, 100));
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
  });
  it("일반 탭 전환은 관리 화면의 내부 탭 상태를 보존한다", async () => {
    mount("/UI-27");
    await screen.findByRole("tab", { name: "코드" });
    fireEvent.click(screen.getByRole("button", { name: "그룹 추가" }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "화면 UI-16" }));
    fireEvent.click(screen.getByRole("tab", { name: "화면 UI-27" }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
  it("UI-22는 로그인 입력과 메뉴 추가 없이 직접 연다", async () => {
    mount("/UI-22");
    expect(
      await screen.findByRole("heading", { level: 1, name: "UI-22" }),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole("tabpanel", { name: "UI-22" })).getByText(
        "이 화면은 다음 단계에서 만듭니다.",
      ),
    ).toBeVisible();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "UI-22" }),
    ).not.toBeInTheDocument();
  });
  it.each(["/UI-06", "/UI-27/M3"])(
    "등록하지 않은 주소 %s는 업무 화면을 만들지 않는다",
    async (path) => {
      mount(path);
      expect(
        await screen.findByText(/요청한 화면을 찾을 수 없습니다/),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("tab", { name: "코드" }),
      ).not.toBeInTheDocument();
    },
  );
  it("모바일에서는 관리 컴포넌트와 해당 API를 실행하지 않는다", async () => {
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
    expect(listCodeGroups).not.toHaveBeenCalled();
    expect(screen.queryByRole("tab", { name: "코드" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "대시보드로" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "화면 UI-16" }),
    ).toBeInTheDocument();
  });
  it("메뉴 실패를 기존 오류 상태와 재시도에 연결한다", async () => {
    vi.mocked(getMenus).mockRejectedValueOnce(
      new Error("서버에 연결할 수 없습니다."),
    );
    mount("/UI-27");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "서버에 연결할 수 없습니다.",
    );
    expect(listCodeGroups).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(
      await screen.findByRole("tab", { name: "코드" }),
    ).toBeInTheDocument();
  });
});
