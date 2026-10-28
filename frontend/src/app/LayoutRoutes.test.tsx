import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppRouter } from "./router";
import {
  developerServer,
  failure,
  response,
} from "../pages/developers/DeveloperTestSupport";
import { useSharedPreferences } from "../pages/shared/preferences";
import type { MenuNode } from "../pages/shared/api";

const menus: MenuNode[] = [
  {
    menuId: 1,
    name: "업무",
    screenId: null,
    icon: "folder",
    mobile: true,
    children: [
      {
        menuId: 2,
        name: "개발자 풀",
        screenId: "UI-02",
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
        name: "투입 배정",
        screenId: "UI-12",
        icon: null,
        mobile: true,
        children: [],
      },
    ],
  },
];
let fetcher: ReturnType<typeof vi.fn>;
let failedPath: string | null;
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  localStorage.clear();
  sessionStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  failedPath = null;
  const server = developerServer();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    const path = url.split("?")[0];
    if (path === "/api/menus") return response(menus);
    if (path === failedPath) return failure(null, 500);
    if (path === "/api/month-close" || path === "/api/projects/options")
      return response([]);
    if (path === "/api/projects" || path === "/api/assignments")
      return response({
        items: [],
        total: 0,
        page: 0,
        size: 50,
        unassignedCount: 0,
      });
    if (path === "/api/developers/1/assignments")
      return response({
        items: ["ACTIVE", "ENDED", "REPLACED", "CANCELLED"].map(
          (status, index) => ({
            asgn_id: 901 + index,
            prj_id: 801 + index,
            prj_nm: `이력 프로젝트 ${index + 1}`,
            job_cd: "BE",
            job_name: "백엔드",
            bgn_dt: "2025-01-01",
            end_dt: "2025-12-31",
            asgn_rt: 0.5,
            asgn_stts_cd: status,
          }),
        ),
        total: 4,
        page: 0,
        size: 50,
      });
    return server.fetcher(url, init);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function mount(path = "/UI-02") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRouter />
    </MemoryRouter>,
  );
}
function navigate(name: string) {
  const group = within(screen.getByLabelText("화면 탐색")).getByRole("button", {
    name: "업무",
  }).parentElement!;
  fireEvent.click(within(group).getByRole("button", { name }));
}

describe("existing layout route integration", () => {
  it("preserves the developer list across project and assignment empty/reset flows and routed history", async () => {
    mount();
    const row = await screen.findByTestId("developer-row-1");
    const text = row.textContent;
    const table = row.closest("table");
    fireEvent.change(screen.getByTestId("developer-search"), {
      target: { value: "보존할 초안" },
    });
    fireEvent.click(screen.getByTestId("developer-select-1"));
    navigate("프로젝트");
    await screen.findByTestId("project-empty-reset");
    fireEvent.change(screen.getByTestId("project-query-input"), {
      target: { value: "없는 프로젝트" },
    });
    fireEvent.click(screen.getByTestId("project-query"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) =>
            String(url).startsWith("/api/projects?") &&
            new URLSearchParams(String(url).split("?")[1]).get("q") ===
              "없는 프로젝트",
        ),
      ).toBe(true),
    );
    fireEvent.click(await screen.findByTestId("project-empty-reset"));
    expect(screen.getByTestId("project-query-input")).toHaveValue("");
    expect(screen.queryByTestId("project-select-all")).not.toBeInTheDocument();
    navigate("투입 배정");
    const reset = await screen.findByTestId("assignment-empty-reset");
    expect(reset.parentElement?.tagName).toBe("DIV");
    expect(reset.closest("td")).toHaveAttribute("colspan", "13");
    expect(reset.parentElement?.firstElementChild).toHaveTextContent(
      "조건에 맞는 결과가 없습니다",
    );
    fireEvent.change(screen.getByTestId("assignment-filter-name"), {
      target: { value: "없는 이름" },
    });
    fireEvent.click(screen.getByTestId("assignment-search"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) =>
            String(url).startsWith("/api/assignments?") &&
            new URLSearchParams(String(url).split("?")[1]).get("q") ===
              "없는 이름",
        ),
      ).toBe(true),
    );
    fireEvent.click(await screen.findByTestId("assignment-empty-reset"));
    expect(screen.getByTestId("assignment-filter-name")).toHaveValue("");
    navigate("개발자 풀");
    expect(screen.getByTestId("developer-row-1")).toBe(row);
    expect(row.textContent).toBe(text);
    expect(row.closest("table")).toBe(table);
    expect(screen.getByTestId("developer-search")).toHaveValue("보존할 초안");
    expect(screen.getByTestId("developer-select-1")).toBeChecked();
    fireEvent.click(screen.getByTestId("developer-open-1"));
    await screen.findByTestId("developer-edit");
    fireEvent.click(screen.getByRole("tab", { name: "투입 이력" }));
    const panel = await screen.findByTestId("developer-assignments");
    for (const status of ["ACTIVE", "ENDED", "REPLACED", "CANCELLED"])
      expect(await within(panel).findByText(status)).toBeInTheDocument();
    expect(
      within(panel)
        .getAllByRole("columnheader")
        .map((cell) => cell.textContent),
    ).toEqual(["프로젝트", "직무", "시작 ~ 철수", "투입률", "상태"]);
    expect(
      fetcher.mock.calls.some(
        ([, init]) => init?.method && init.method !== "GET",
      ),
    ).toBe(false);
    expect(
      fetcher.mock.calls.some(([url]) => String(url).includes("/api/auth/")),
    ).toBe(false);
  });

  it.each([
    ["/UI-01", "/api/projects", "project-empty-reset"],
    ["/UI-12", "/api/assignments", "assignment-empty-reset"],
  ])(
    "keeps failed list requests distinct from successful empty results on %s",
    async (route, path, resetId) => {
      failedPath = path;
      mount(route);
      expect(await screen.findByText("입력 오류")).toBeVisible();
      expect(screen.queryByTestId(resetId)).not.toBeInTheDocument();
      expect(
        fetcher.mock.calls.some(([url]) => String(url).startsWith(`${path}?`)),
      ).toBe(true);
    },
  );
});
