import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, useNavigate } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppRouter } from "./router";
import { useSharedPreferences } from "../pages/shared/preferences";
import { response } from "../pages/developers/DeveloperTestSupport";
import type { MenuNode } from "../pages/shared/api";
import type { Closing } from "../pages/apiops/closingClient";

const menus: MenuNode[] = [
  {
    menuId: 1,
    name: "마감",
    screenId: null,
    icon: null,
    mobile: true,
    children: [
      {
        menuId: 2,
        name: "월 마감",
        screenId: "UI-14",
        icon: null,
        mobile: true,
        children: [],
      },
    ],
  },
];
let closing: Closing;
let failed: boolean;
let fetcher: ReturnType<typeof vi.fn>;
const posts = () =>
  fetcher.mock.calls.filter(([, init]) => init?.method === "POST");
function AdjustmentHandoff() {
  const navigate = useNavigate();
  return (
    <button
      data-testid="test-adjustment-handoff"
      onClick={() =>
        navigate("/UI-14", {
          state: { closingMonth: "2026-09", closingTab: "adjustments" },
        })
      }
    >
      전월 조정으로 이동
    </button>
  );
}
function mount(state?: { closingMonth?: string; closingTab?: string }) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: "/UI-14", state }]}>
      <AppRouter />
      <AdjustmentHandoff />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-30T00:00:00Z"));
  localStorage.clear();
  sessionStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  closing = { month: "2026-10", status: "OPEN", checks: [], canReopen: false };
  failed = false;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/menus") return response(menus);
    if (url.startsWith("/api/codes/") || url.startsWith("/api/month-close?"))
      return response([]);
    if (url.startsWith("/api/closing?")) {
      if (failed)
        return new Response(
          JSON.stringify({
            success: false,
            code: "INTERNAL_ERROR",
            message: "마감 조회 실패",
            requestId: "closing-read",
          }),
          { status: 500 },
        );
      const month = new URL(url, "http://test").searchParams.get("month");
      return response({ ...closing, month });
    }
    if (
      url.startsWith("/api/closing/history?") ||
      url.startsWith("/api/closing/adjustments?")
    )
      return response({ items: [], total: 0, page: 0, size: 20 });
    if (url.startsWith("/api/closing/check?") && init?.method === "POST")
      return response(closing);
    throw new Error(`Unexpected closing route request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe("monthly closing route composition (SCR-UI-14)", () => {
  it("opens the composed workspace anonymously through the existing menu", async () => {
    mount();
    expect(await screen.findByTestId("closing-screen")).toBeVisible();
    expect(await screen.findByText("마감 이력이 없습니다.")).toBeVisible();
    expect(
      screen.getByText("아직 점검하지 않았습니다. 다시 점검을 실행해 주세요."),
    ).toBeVisible();
    expect(screen.getByTestId("closing-confirm-button")).toBeDisabled();
    expect(
      screen.queryByText("이 화면은 다음 단계에서 만듭니다."),
    ).not.toBeInTheDocument();
    expect(fetcher.mock.calls.some(([url]) => url.includes("/auth/"))).toBe(
      false,
    );
    expect(posts()).toHaveLength(0);
  });
  it("connects explicit recheck to the owner's command and refresh", async () => {
    mount();
    fireEvent.click(await screen.findByTestId("closing-recheck-button"));
    expect(await screen.findByText("점검 결과를 저장했습니다.")).toBeVisible();
    expect(fetcher).toHaveBeenCalledWith(
      "/api/closing/check?month=2026-10",
      expect.objectContaining({ method: "POST" }),
    );
    expect(posts()).toHaveLength(1);
    expect(
      fetcher.mock.calls.filter(([url]) => url.startsWith("/api/closing?"))
        .length,
    ).toBeGreaterThan(1);
  });
  it("opens adjustments inside the same route using navigation state", async () => {
    mount({ closingMonth: "2026-09", closingTab: "adjustments" });
    expect(
      await screen.findByTestId("closing-adjustments-panel"),
    ).toBeVisible();
    expect(screen.getByLabelText("조회월")).toHaveValue("2026-09");
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) =>
            url.startsWith("/api/closing/adjustments?") &&
            url.includes("month=2026-09"),
        ),
      ).toBe(true),
    );
    expect(posts()).toHaveLength(0);
  });
  it("applies an adjustment handoff to the already mounted closing tab", async () => {
    mount();
    await screen.findByTestId("closing-history-panel");
    fireEvent.click(screen.getByTestId("test-adjustment-handoff"));
    expect(
      await screen.findByTestId("closing-adjustments-panel"),
    ).toBeVisible();
    expect(screen.getByLabelText("조회월")).toHaveValue("2026-09");
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) =>
            url.startsWith("/api/closing/adjustments?") &&
            url.includes("month=2026-09"),
        ),
      ).toBe(true),
    );
    expect(posts()).toHaveLength(0);
  });
  it("keeps read errors retryable without issuing mutations", async () => {
    failed = true;
    mount();
    await screen.findByText("마감 조회 실패");
    failed = false;
    fireEvent.click(
      within(screen.getByTestId("closing-screen")).getByRole("button", {
        name: "다시 시도",
      }),
    );
    await screen.findByText("마감 이력이 없습니다.");
    expect(screen.queryByText("마감 조회 실패")).not.toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });
  it("keeps CLOSED reads and history available without OPEN mutations", async () => {
    closing = {
      ...closing,
      status: "CLOSED",
      processedAt: "2026-10-30T12:00:00",
      canReopen: false,
    };
    mount();
    await screen.findByText(/시스템 처리가 마감했습니다/);
    expect(await screen.findByTestId("closing-history-panel")).toBeVisible();
    expect(
      screen.queryByTestId("closing-recheck-button"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("closing-reopen-button"),
    ).not.toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });
  it.each([767, 375])(
    "allows mobile reads at %spx but hides all command controls",
    async (width) => {
      vi.stubGlobal("innerWidth", width);
      mount();
      expect(await screen.findByText("마감 이력이 없습니다.")).toBeVisible();
      expect(
        screen.queryByText("PC에서 이용해 주세요"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByTestId("closing-recheck-button"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByTestId("closing-confirm-button"),
      ).not.toBeInTheDocument();
      fireEvent.click(screen.getByTestId("closing-adjustments-tab"));
      expect(
        await screen.findByTestId("closing-adjustments-panel"),
      ).toBeVisible();
      expect(
        screen.queryByTestId("adjustment-create-button"),
      ).not.toBeInTheDocument();
      expect(posts()).toHaveLength(0);
    },
  );
});
