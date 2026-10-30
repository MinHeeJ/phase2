import { createElement } from "react";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, expect, test, vi } from "vitest";
import { AppRouter } from "../../src/app/router";
import { useSharedPreferences } from "../../src/pages/shared/preferences";
import { screenIds } from "../../src/pages/shared/registry";

// Transport fixtures isolate browser geometry and route coverage; not a DB acceptance test.
const pcOnly = new Set([
  "UI-10",
  "UI-14",
  "UI-20",
  "UI-23",
  "UI-24",
  "UI-25",
  "UI-27",
]);
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
    "fetch",
    vi.fn(async (url: string) => {
      const path = url.split("?")[0];
      const data =
        path === "/api/menus"
          ? [
              {
                menuId: 1,
                name: "업무",
                screenId: null,
                icon: "folder",
                mobile: true,
                children: screenIds.map((id, i) => ({
                  menuId: i + 2,
                  name: id === "UI-27" ? "공통코드 관리" : id,
                  screenId: id,
                  icon: null,
                  mobile: !pcOnly.has(id),
                  children: [],
                })),
              },
            ]
          : path === "/api/month-close" ||
              path.startsWith("/api/codes/") ||
              path === "/api/sales-parts/options" ||
              path === "/api/partners/options"
            ? []
            : { items: [], total: 0, page: 1, size: 100 };
      return new Response(JSON.stringify({ success: true, meta: {}, data }));
    }),
  );
});

function mount(path: string, width = 1280) {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    value: width,
  });
  return render(
    createElement(
      MemoryRouter,
      { initialEntries: [path] },
      createElement(AppRouter),
    ),
  );
}

test("unauthenticated common-code screen exposes its four working tabs", async () => {
  mount("/UI-27");
  expect(
    await screen.findByRole("heading", { level: 1, name: "공통코드 관리" }),
  ).toBeVisible();
  for (const label of ["엑셀 표기 대응", "기준값", "변경 이력", "코드"]) {
    const tab = screen.getByRole("tab", { name: label });
    fireEvent.click(tab);
    expect(tab).toHaveAttribute("aria-selected", "true");
  }
  expect(screen.queryByLabelText("비밀번호")).not.toBeInTheDocument();
});

test("mobile PC-only route exposes guidance instead of business inputs", async () => {
  mount("/UI-27", 375);
  expect(await screen.findByText("PC에서 이용해 주세요")).toBeVisible();
  expect(screen.queryByRole("tab")).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "대시보드로" }));
  expect(
    await screen.findByRole("heading", { level: 1, name: "UI-16" }),
  ).toBeVisible();
});

test("menu navigation and screen search accept user input without login", async () => {
  mount("/UI-03");
  expect(
    await screen.findByRole("heading", { level: 1, name: "UI-03" }),
  ).toBeVisible();
  const menu = screen.getByLabelText("업무 메뉴");
  fireEvent.click(within(menu).getByRole("button", { name: "UI-01" }));
  expect(
    await screen.findByRole("heading", { level: 1, name: "UI-01" }),
  ).toBeVisible();
  fireEvent.keyDown(window, { key: "k", ctrlKey: true });
  expect(await screen.findByRole("dialog")).toBeVisible();
});
