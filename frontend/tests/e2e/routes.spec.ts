import { createElement } from "react";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { App } from "../../src/app/App";
import { usePreferences } from "../../src/stores/preferences";
import type { MenuNode } from "../../src/services/api";

const menus: MenuNode[] = [
  {
    menuId: 1,
    name: "시스템",
    screenId: null,
    icon: "gear",
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
      {
        menuId: 3,
        name: "공통코드",
        screenId: "UI-27",
        icon: null,
        mobile: false,
        children: [],
      },
    ],
  },
];

beforeEach(() => {
  Object.defineProperty(window, "innerWidth", { value: 1280, writable: true });
  usePreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    size: 50,
    dense: false,
  });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string) => {
      expect(path).toMatch(/^\/api\//);
      const data =
        path === "/api/menus"
          ? menus
          : { items: [], total: 0, page: 1, size: 100 };
      return new Response(JSON.stringify({ success: true, meta: {}, data }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

function openRoute(path: string) {
  return render(
    createElement(MemoryRouter, { initialEntries: [path] }, createElement(App)),
  );
}

test.each(["/", "/UI-16"])(
  "%s 직접 진입과 재마운트는 대시보드를 표시한다",
  async (path) => {
    const view = openRoute(path);
    expect(
      await screen.findByRole("heading", { name: "대시보드" }),
    ).toBeVisible();
    expect(screen.getByText("이 화면은 다음 단계에서 만듭니다.")).toBeVisible();
    view.unmount();
    openRoute("/UI-16");
    expect(
      await screen.findByRole("heading", { name: "대시보드" }),
    ).toBeVisible();
  },
);

test("비인증 사용자는 화면 검색에서 공통코드로 이동하고 작업 탭을 볼 수 있다", async () => {
  const user = userEvent.setup();
  openRoute("/UI-16");
  await screen.findByRole("heading", { name: "대시보드" });
  await user.keyboard("{Control>}k{/Control}");
  const search = screen.getByRole("dialog", { name: "화면 검색" });
  expect(search).toBeVisible();
  await user.type(
    screen.getByRole("textbox", { name: "화면 이름" }),
    "공통코드",
  );
  await user.click(within(search).getByRole("button", { name: "공통코드" }));
  expect(
    await screen.findByRole("heading", { name: "공통코드" }),
  ).toBeVisible();
  expect(screen.getByRole("tab", { name: "코드" })).toBeVisible();
  expect(screen.getByRole("tab", { name: "기준값" })).toBeVisible();
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});

test("모바일 공통코드 직접 진입은 편집 대신 PC 안내와 대시보드 이동을 제공한다", async () => {
  Object.defineProperty(window, "innerWidth", { value: 360, writable: true });
  const user = userEvent.setup();
  openRoute("/UI-27");
  expect(await screen.findByText("PC에서 이용해 주세요")).toBeVisible();
  expect(
    screen.queryByRole("button", { name: "저장" }),
  ).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "대시보드로" }));
  expect(
    await screen.findByRole("heading", { name: "대시보드" }),
  ).toBeVisible();
});
