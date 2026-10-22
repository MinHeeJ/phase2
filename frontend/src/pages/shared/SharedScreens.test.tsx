import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SharedScreen } from "./SharedScreen";
import { PlaceholderScreen } from "./PlaceholderScreen";
import { PeriodBar, seoulMonth } from "./PeriodBar";
import { useSharedPreferences } from "./preferences";
import { periodType, screenIds, sharedRoutes } from "./registry";
import type { MenuNode } from "./api";

const leaf = (id: string, mobile = true): MenuNode => ({
  menuId: Number(id.slice(3)) + 10,
  name: `화면 ${id}`,
  screenId: id,
  icon: null,
  mobile,
  children: [],
});
const menus: MenuNode[] = [
  {
    menuId: 1,
    name: "메뉴 묶음",
    screenId: null,
    icon: "folder",
    mobile: true,
    children: screenIds.map((id) =>
      leaf(
        id,
        ![
          "UI-10",
          "UI-14",
          "UI-20",
          "UI-23",
          "UI-24",
          "UI-25",
          "UI-27",
        ].includes(id),
      ),
    ),
  },
];
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    value: 1280,
  });
  useSharedPreferences.setState({ favorites: [], recent: [] });
});
afterEach(() => vi.unstubAllGlobals());
describe("shared screen inventory and states", () => {
  it.each(sharedRoutes)(
    "%s renders the common placeholder with no business inputs",
    async (route) => {
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockImplementation((url: string) =>
            Promise.resolve(response(url === "/api/menus" ? menus : [])),
          ),
      );
      render(<SharedScreen screenId={route.slice(1)} />);
      expect(
        await screen.findByText("이 화면은 다음 단계에서 만듭니다."),
      ).toBeInTheDocument();
      expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
        route.slice(1),
      );
      expect(screen.queryByRole("table")).not.toBeInTheDocument();
      expect(screen.queryByLabelText(/비밀번호/)).not.toBeInTheDocument();
      expect(screen.queryByText("저장")).not.toBeInTheDocument();
    },
  );
  it("has actual 31 screen identities, excludes UI06 and keeps unlisted UI22 compatibility", () => {
    expect(screenIds).toHaveLength(31);
    expect(screenIds).not.toContain("UI-06");
    expect(screenIds).not.toContain("UI-22");
    expect(sharedRoutes).toHaveLength(31);
    expect(sharedRoutes).toContain("/UI-22");
    expect(sharedRoutes).not.toContain("/UI-27");
    expect(screenIds).toContain("UI-32");
    expect(screenIds).toContain("UI-33");
  });
  it("does not fabricate unknown or admin-owned screens", () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(menus)));
    const { rerender } = render(<SharedScreen screenId="UI-06" />);
    expect(screen.getByText("화면을 찾을 수 없습니다.")).toBeInTheDocument();
    rerender(<SharedScreen screenId="UI-27" />);
    expect(screen.getByText("화면을 찾을 수 없습니다.")).toBeInTheDocument();
  });
  it("shows initial skeleton and server error with retry, then API title and breadcrumb", async () => {
    let finish: (response: Response) => void = () => {};
    const fetcher = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve;
          }),
      )
      .mockImplementation(() => Promise.resolve(response(menus)));
    vi.stubGlobal("fetch", fetcher);
    render(<SharedScreen screenId="UI-03" />);
    expect(screen.getByLabelText("메뉴 불러오는 중")).toBeInTheDocument();
    await act(async () =>
      finish(
        new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "SERVER_ERROR",
            message: "메뉴 조회 오류",
            requestId: "server-id",
          }),
          { status: 500 },
        ),
      ),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("server-id");
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByRole("heading", { level: 1 })).toHaveTextContent(
      "화면 UI-03",
    );
    expect(
      screen.getByRole("navigation", { name: "현재 위치" }),
    ).toHaveTextContent("메뉴 묶음");
  });
  it.each(["UI-10", "UI-14", "UI-20", "UI-23", "UI-24", "UI-25", "UI-27"])(
    "%s replaces mobile=false content with PC notice and explicit home only",
    (id) => {
      Object.defineProperty(window, "innerWidth", {
        configurable: true,
        value: 375,
      });
      render(<PlaceholderScreen screen={leaf(id, false)} />);
      expect(screen.getByText("PC에서 이용해 주세요")).toBeInTheDocument();
      expect(screen.getByRole("link")).toHaveAttribute("href", "/UI-16");
      expect(screen.queryByRole("heading")).not.toBeInTheDocument();
      expect(screen.queryByLabelText("조회월")).not.toBeInTheDocument();
    },
  );
  it("NONE has no period and star toggles with an inline favorite limit", async () => {
    useSharedPreferences.setState({
      favorites: screenIds.slice(0, 10) as unknown as string[],
    });
    render(<PlaceholderScreen screen={leaf("UI-15")} />);
    expect(screen.queryByLabelText("조회 기간")).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("즐겨찾기"));
    expect(screen.getByRole("status")).toHaveTextContent("최대 10개");
    expect(useSharedPreferences.getState().favorites).toHaveLength(10);
    await act(async () => {
      useSharedPreferences.setState({ favorites: [] });
    });
    fireEvent.click(screen.getByLabelText("즐겨찾기"));
    expect(screen.getByLabelText("즐겨찾기")).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(JSON.parse(localStorage.getItem("si.favorites")!)).toEqual([
      "UI-15",
    ]);
  });
});
describe("period contract", () => {
  it("uses Seoul runtime date and correct archetypes", () => {
    expect(seoulMonth(new Date("2026-09-30T16:00:00Z"))).toBe("2026-10");
    expect(periodType("UI-19")).toBe("MONTH");
    expect(periodType("UI-07")).toBe("YEAR_CUM");
    expect(periodType("UI-01")).toBe("YEAR");
    expect(periodType("UI-22")).toBe("NONE");
  });
  it("retains per-screen year in memory across visits, without writing localStorage", () => {
    localStorage.clear();
    const { unmount } = render(<PeriodBar screenId="UI-17" />);
    const year = Number(
      screen.getByText(/^[0-9]{4}년$/).textContent!.slice(0, 4),
    );
    fireEvent.click(screen.getByLabelText("다음 연도"));
    expect(screen.getByText(`${year + 1}년`)).toBeInTheDocument();
    unmount();
    render(<PeriodBar screenId="UI-17" />);
    expect(screen.getByText(`${year + 1}년`)).toBeInTheDocument();
    expect(localStorage.length).toBe(0);
  });
  it("cumulative end month updates together with caption", () => {
    render(<PeriodBar screenId="UI-07" />);
    fireEvent.change(screen.getByLabelText("누계월"), {
      target: { value: "10" },
    });
    expect(screen.getByText("1~10월 누계")).toBeInTheDocument();
  });
  it("closed/open/missing states come only from successful sparse reads", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementation((url: string) =>
          Promise.resolve(
            response(
              url.includes("2026-09")
                ? [{ month: "2026-09", status: "CLOSED" }]
                : [],
            ),
          ),
        ),
    );
    render(<PeriodBar screenId="UI-19" />);
    fireEvent.change(screen.getByLabelText("조회월"), {
      target: { value: "2026-09" },
    });
    expect(await screen.findByText("마감")).toBeInTheDocument();
    expect(screen.getByText(/2026-09는 마감되었습니다/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("조회월"), {
      target: { value: "2026-11" },
    });
    expect(await screen.findByText("작업중")).toBeInTheDocument();
    expect(screen.queryByText(/마감되었습니다/)).not.toBeInTheDocument();
  });
  it("failed month read retains chosen period and does not infer OPEN until retry succeeds", async () => {
    const fetcher = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("offline"))
      .mockImplementation(() => Promise.resolve(response([])));
    vi.stubGlobal("fetch", fetcher);
    render(<PeriodBar screenId="UI-14" />);
    const input = screen.getByLabelText("조회월") as HTMLInputElement;
    const month = input.value;
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "서버 응답 없음",
    );
    expect(screen.queryByText("작업중")).not.toBeInTheDocument();
    fireEvent.click(screen.getByText("다시 시도"));
    await waitFor(() =>
      expect(screen.queryByRole("alert")).not.toBeInTheDocument(),
    );
    expect(input).toHaveValue(month);
    expect(await screen.findByText("작업중")).toBeInTheDocument();
  });
});
