import { useState } from "react";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SharedWorkspace } from "../src/pages/shared/SharedWorkspace";
import { PlaceholderScreen } from "../src/pages/shared/PlaceholderScreen";
import { PeriodBar } from "../src/pages/shared/PeriodBar";
import { useCodes } from "../src/pages/shared/useCodes";
import {
  getCodeOptions,
  getEffectiveParam,
  getHealth,
} from "../src/pages/shared/api";
import { useSharedPreferences } from "../src/pages/shared/preferences";
import { screenIds, periodType } from "../src/pages/shared/registry";
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
function mockPhone(phone = false) {
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    value: vi.fn().mockImplementation(() => ({
      matches: phone,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  });
}
function workspace(
  path = "/UI-16",
  renderScreen?: Parameters<typeof SharedWorkspace>[0]["renderScreen"],
) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SharedWorkspace renderScreen={renderScreen} />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  localStorage.clear();
  mockPhone();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockImplementation((path: string) =>
        Promise.resolve(json(path.startsWith("/api/menus") ? menus : [])),
      ),
  );
});
afterEach(() => vi.unstubAllGlobals());

describe("공통 화면 계약", () => {
  it.each(screenIds.filter((id) => id !== "UI-27"))(
    "직접 경로 %s는 업무 입력 없는 자리 화면",
    async (id) => {
      workspace(`/${id}`);
      expect(
        await screen.findByRole("heading", {
          name: id === "UI-22" ? id : `화면 ${id}`,
        }),
      ).toBeInTheDocument();
      const panel = within(screen.getByRole("region", { name: `${id} 화면` }));
      expect(
        panel.getByText("이 화면은 다음 단계에서 만듭니다."),
      ).toBeInTheDocument();
      expect(panel.queryByRole("table")).not.toBeInTheDocument();
      expect(
        panel.queryByRole("button", { name: "저장" }),
      ).not.toBeInTheDocument();
      expect(panel.queryByLabelText("조회 기간") !== null).toBe(
        periodType(id) !== "NONE",
      );
    },
  );
  it("UI-27은 DB 제목을 사용하고 등록된 업무 화면을 유지", async () => {
    workspace("/UI-27", () => <div>관리 기능 region</div>);
    expect(
      await screen.findByRole("heading", { name: "공통코드" }),
    ).toBeInTheDocument();
    expect(screen.getByText("관리 기능 region")).toBeInTheDocument();
    expect(
      within(screen.getByRole("region", { name: "UI-27 화면" })).queryByText(
        "이 화면은 다음 단계에서 만듭니다.",
      ),
    ).not.toBeInTheDocument();
  });
  it("루트는 대시보드로 이동하고 대시보드는 닫을 수 없음", async () => {
    workspace("/");
    expect(
      await screen.findByRole("heading", { name: "화면 UI-16" }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("UI-16 닫기")).not.toBeInTheDocument();
  });
  it.each(["UI-10", "UI-14", "UI-20", "UI-23", "UI-24", "UI-25", "UI-27"])(
    "휴대폰 %s는 PC 안내만 표시",
    async (id) => {
      mockPhone(true);
      workspace(`/${id}`);
      expect(
        await screen.findByText("PC에서 이용해 주세요"),
      ).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "대시보드로" })).toHaveAttribute(
        "href",
        "/UI-16",
      );
      expect(screen.queryByLabelText("조회 기간")).not.toBeInTheDocument();
      expect(
        screen.queryByRole("link", {
          name: id === "UI-27" ? "공통코드" : `화면 ${id}`,
        }),
      ).not.toBeInTheDocument();
    },
  );
  it("메뉴 최초 로딩은 skeleton", () => {
    vi.stubGlobal("fetch", vi.fn().mockReturnValue(new Promise(() => {})));
    workspace();
    expect(screen.getByLabelText("불러오는 중")).toHaveAttribute(
      "aria-busy",
      "true",
    );
  });
  it("빈 메뉴는 원인과 재조회 버튼", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json([])));
    workspace();
    expect(
      await screen.findByText("활성 메뉴에서 이 화면을 찾을 수 없습니다."),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "메뉴 다시 조회" }),
    ).toBeInTheDocument();
  });
  it("메뉴 연결 실패와 읽기 재시도", async () => {
    const fetch = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("network"))
      .mockResolvedValue(json(menus));
    vi.stubGlobal("fetch", fetch);
    workspace();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "문의 번호: 서버 연결 전 실패",
    );
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(
      await screen.findByRole("heading", { name: "화면 UI-16" }),
    ).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledTimes(2);
  });
  it("알림과 사용자 설정은 업무 데이터를 만들지 않음", async () => {
    workspace();
    await screen.findByRole("heading");
    fireEvent.click(screen.getByRole("button", { name: "알림" }));
    expect(screen.getByText("새 알림이 없습니다")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "기본 사용자" }));
    fireEvent.change(screen.getByLabelText("목록 기본 건수"), {
      target: { value: "20" },
    });
    fireEvent.click(screen.getByLabelText("좁게 보기"));
    expect(localStorage.getItem("si.pageSize")).toBe("20");
    expect(localStorage.getItem("si.dense")).toBe("true");
  });
  it("Ctrl+K는 실제 leaf의 이름/ID 부분일치 검색이며 Esc로 닫음", async () => {
    workspace();
    await screen.findByRole("heading");
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByRole("textbox"), {
      target: { value: "ui-27" },
    });
    expect(
      within(dialog).getByRole("button", { name: "공통코드 · UI-27" }),
    ).toBeInTheDocument();
    expect(
      within(dialog).queryByRole("button", { name: "화면 UI-03 · UI-03" }),
    ).not.toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("즐겨찾기 최대 10개, 순서 저장, 최근5개와 메모리 탭/기간 경계", async () => {
    useSharedPreferences.setState({
      favorites: leaves.slice(0, 10).map((leaf) => leaf.screenId!),
    });
    workspace("/UI-33");
    await screen.findByRole("heading");
    fireEvent.click(screen.getByRole("button", { name: "즐겨찾기 추가" }));
    expect(
      screen.getByText("즐겨찾기는 최대 10개까지 추가할 수 있습니다."),
    ).toBeInTheDocument();
    const store = useSharedPreferences.getState();
    store.moveFavorite(leaves[1].screenId!, leaves[0].screenId!);
    expect(JSON.parse(localStorage.getItem("si.favorites")!)[0]).toBe(
      leaves[1].screenId,
    );
    leaves.slice(0, 6).forEach((leaf) => store.visit(leaf.screenId!));
    expect(useSharedPreferences.getState().recent).toHaveLength(5);
    expect(
      Object.keys(localStorage).every((key) =>
        [
          "si.favorites",
          "si.recent",
          "si.collapsed",
          "si.pageSize",
          "si.dense",
        ].includes(key),
      ),
    ).toBe(true);
  });
  it("9번째 화면은 대시보드 외 LRU 교체를 확인하고 취소시 기존 탭 유지", async () => {
    workspace("/UI-16");
    await screen.findByRole("heading");
    const open = async (id: string) => {
      fireEvent.keyDown(window, { key: "k", ctrlKey: true });
      const dialog = screen.getByRole("dialog");
      fireEvent.change(within(dialog).getByRole("textbox"), {
        target: { value: id },
      });
      fireEvent.click(
        within(dialog).getByRole("button", { name: `화면 ${id} · ${id}` }),
      );
    };
    for (const id of [
      "UI-01",
      "UI-02",
      "UI-03",
      "UI-04",
      "UI-05",
      "UI-07",
      "UI-08",
    ]) {
      await open(id);
      await screen.findByRole("heading", { name: `화면 ${id}` });
    }
    await open("UI-09");
    expect(screen.getByRole("dialog")).toHaveTextContent("화면 UI-01");
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.getByLabelText("UI-01 닫기")).toBeInTheDocument();
    expect(screen.queryByLabelText("UI-09 닫기")).not.toBeInTheDocument();
    await open("UI-09");
    fireEvent.click(screen.getByRole("button", { name: "닫기 확인" }));
    await screen.findByRole("heading", { name: "화면 UI-09" });
    expect(screen.queryByLabelText("UI-01 닫기")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("UI-16 닫기")).not.toBeInTheDocument();
  });
  it("일반 탭 전환은 dirty draft 보존, 닫기는 계속 작성/나가기 확인", async () => {
    function Draft({
      onDirtyChange,
    }: {
      onDirtyChange: (value: boolean) => void;
    }) {
      const [value, setValue] = useState("");
      return (
        <input
          aria-label="업무 입력"
          value={value}
          onChange={(event) => {
            setValue(event.target.value);
            onDirtyChange(true);
          }}
        />
      );
    }
    workspace("/UI-27", (_id, context) => (
      <Draft onDirtyChange={context.onDirtyChange} />
    ));
    await screen.findByRole("heading");
    fireEvent.change(screen.getByLabelText("업무 입력"), {
      target: { value: "유지할 초안" },
    });
    fireEvent.click(screen.getByRole("button", { name: "화면 UI-16" }));
    await screen.findByRole("heading", { name: "화면 UI-16" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "● 공통코드" }));
    await screen.findByRole("heading", { name: "공통코드" });
    expect(screen.getByLabelText("업무 입력")).toHaveValue("유지할 초안");
    fireEvent.click(screen.getByLabelText("UI-27 닫기"));
    expect(screen.getByRole("dialog")).toHaveTextContent("저장하지 않은 내용");
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(screen.getByLabelText("업무 입력")).toHaveValue("유지할 초안");
    fireEvent.click(screen.getByLabelText("UI-27 닫기"));
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    await screen.findByRole("heading", { name: "화면 UI-16" });
    expect(screen.queryByLabelText("업무 입력")).not.toBeInTheDocument();
  });
});

describe("기간 및 선택 API", () => {
  it("실제 CLOSED와 미등록 OPEN은 조회 성공 뒤에만 판정", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementation((path: string) =>
          Promise.resolve(
            json(
              path.includes("2026-09")
                ? [{ month: "2026-09", status: "CLOSED" }]
                : [],
            ),
          ),
        ),
    );
    render(<PeriodBar screenId="UI-19" />);
    fireEvent.change(screen.getByLabelText("조회 연월"), {
      target: { value: "2026-09" },
    });
    expect(await screen.findByText("마감")).toBeInTheDocument();
    expect(screen.getByText(/고칠 내용은 다음 달/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("조회 연월"), {
      target: { value: "2027-01" },
    });
    expect(await screen.findByText("작업중")).toBeInTheDocument();
    expect(screen.queryByText(/고칠 내용은 다음 달/)).not.toBeInTheDocument();
  });
  it("월 조회 실패는 OPEN으로 위장하지 않고 기간 유지 및 재시도", async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError("network"));
    vi.stubGlobal("fetch", fetch);
    render(<PeriodBar screenId="UI-20" />);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    const period = (screen.getByLabelText("조회 연월") as HTMLInputElement)
      .value;
    expect(screen.queryByText("작업중")).not.toBeInTheDocument();
    fetch.mockResolvedValue(json([]));
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("작업중")).toBeInTheDocument();
    expect(screen.getByLabelText("조회 연월")).toHaveValue(period);
  });
  it("YEAR 기간은 화면별로 기억하며 YEAR_CUM의 누계월 표시", () => {
    const view = render(<PlaceholderScreen screenId="UI-01" />);
    const before = screen.getByLabelText("조회 기간").textContent;
    fireEvent.click(screen.getByRole("button", { name: "다음 연도" }));
    const after = screen.getByLabelText("조회 기간").textContent;
    expect(after).not.toBe(before);
    view.unmount();
    const second = render(<PlaceholderScreen screenId="UI-03" />);
    second.unmount();
    render(<PlaceholderScreen screenId="UI-01" />);
    expect(screen.getByLabelText("조회 기간")).toHaveTextContent(after!);
  });
  it("YEAR_CUM은 실제 선택월의 누계이며 NONE은 기간 없음", () => {
    const view = render(<PeriodBar screenId="UI-07" />);
    const input = screen.getByLabelText("누계월") as HTMLInputElement;
    fireEvent.change(input, {
      target: { value: `${input.value.slice(0, 4)}-03` },
    });
    expect(screen.getByText("1~3월 누계")).toBeInTheDocument();
    view.unmount();
    render(<PeriodBar screenId="UI-16" />);
    expect(screen.queryByLabelText("조회 기간")).not.toBeInTheDocument();
  });
  it("useCodes 선택 그룹 변경시 기존 옵션 제거 및 실패 재시도", async () => {
    function Options({ group }: { group: string }) {
      const state = useCodes(group);
      return (
        <>
          <span>{state.codes.map((code) => code.name).join(",")}</span>
          {state.loading && <p>선택 로딩</p>}
          {state.error && <button onClick={state.retry}>선택 재시도</button>}
        </>
      );
    }
    const fetch = vi
      .fn()
      .mockResolvedValue(
        json([
          { code: "BE", name: "백엔드", attr1: null, attr2: null, attr3: null },
        ]),
      );
    vi.stubGlobal("fetch", fetch);
    const view = render(<Options group="JOB" />);
    expect(await screen.findByText("백엔드")).toBeInTheDocument();
    fetch.mockRejectedValue(new TypeError("network"));
    view.rerender(<Options group="POSITION" />);
    expect(
      await screen.findByRole("button", { name: "선택 재시도" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("백엔드")).not.toBeInTheDocument();
    fetch.mockResolvedValue(json([]));
    fireEvent.click(screen.getByRole("button", { name: "선택 재시도" }));
    await waitFor(() =>
      expect(
        screen.queryByRole("button", { name: "선택 재시도" }),
      ).not.toBeInTheDocument(),
    );
  });
  it("클라이언트는 선택한 식별자/월과 상대 API 경로를 사용", async () => {
    const fetch = vi
      .fn()
      .mockImplementation(() => Promise.resolve(json(undefined)));
    vi.stubGlobal("fetch", fetch);
    await getCodeOptions("A/B");
    await getEffectiveParam("VAT_RATE", "2026-11");
    await getHealth();
    expect(fetch.mock.calls.map((call) => call[0])).toEqual([
      "/api/codes/A%2FB",
      "/api/params/VAT_RATE?month=2026-11",
      "/api/health",
    ]);
  });
});
