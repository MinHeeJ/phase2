import { useEffect, useState } from "react";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppShell, type BusinessScreenBindings } from "./AppShell";
import type { MenuNode } from "./api";
import { MenuNavigation } from "./MenuNavigation";
import { useSharedPreferences } from "./preferences";

const children: MenuNode[] = [
  "UI-16",
  "UI-27",
  "UI-01",
  "UI-02",
  "UI-03",
  "UI-04",
  "UI-05",
  "UI-07",
  "UI-08",
  "UI-09",
].map((id, index) => ({
  menuId: index + 11,
  name:
    id === "UI-16" ? "대시보드" : id === "UI-27" ? "공통코드" : `화면 ${id}`,
  screenId: id,
  icon: null,
  mobile: id !== "UI-27",
  children: [],
}));
const menus: MenuNode[] = [
  {
    menuId: 1,
    name: "업무",
    screenId: null,
    icon: "folder",
    mobile: true,
    children,
  },
];
function Editor({ onDirtyChange, name }: BusinessScreenBindings) {
  const [value, setValue] = useState("");
  useEffect(() => onDirtyChange(value !== ""), [value, onDirtyChange]);
  return (
    <input
      aria-label={name === "공통코드" ? "업무 초안" : `${name} 초안`}
      value={value}
      onChange={(event) => setValue(event.target.value)}
    />
  );
}
function Location() {
  return <output aria-label="현재 주소">{useLocation().pathname}</output>;
}
function mount(path = "/UI-16") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Location />
      <AppShell renderBusiness={(_id, bindings) => <Editor {...bindings} />} />
    </MemoryRouter>,
  );
}
async function searchOpen(id: string) {
  fireEvent.keyDown(window, { key: "k", ctrlKey: true });
  const dialog = screen.getByRole("dialog");
  fireEvent.change(within(dialog).getByLabelText("화면 이름 또는 ID"), {
    target: { value: id },
  });
  fireEvent.click(within(dialog).getByRole("button", { name: new RegExp(id) }));
  await waitFor(() =>
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(`/${id}`),
  );
}
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    value: 1280,
  });
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
    vi
      .fn()
      .mockImplementation(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({ success: true, meta: {}, data: menus }),
          ),
        ),
      ),
  );
});
afterEach(() => vi.unstubAllGlobals());
describe("shell navigation and preferences", () => {
  it("redirects root to pinned dashboard, with empty notifications and two user settings", async () => {
    mount("/");
    await screen.findByRole("heading", { name: "대시보드" });
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-16");
    expect(screen.queryByLabelText("대시보드 닫기")).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("알림"));
    expect(screen.getByText("새 알림이 없습니다")).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("사용자 설정"));
    expect(screen.getByText("목록 건수")).toBeInTheDocument();
    expect(screen.getByText("좁게 보기")).toBeInTheDocument();
    fireEvent.change(screen.getByRole("combobox"), {
      target: { value: "100" },
    });
    expect(localStorage.getItem("si.pageSize")).toBe("100");
    fireEvent.click(screen.getByText("좁게 보기"));
    expect(localStorage.getItem("si.dense")).toBe("true");
  });
  it("Ctrl+K searches existing API names and IDs and Esc closes without navigation", async () => {
    mount();
    await screen.findByRole("heading", { name: "대시보드" });
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    fireEvent.change(screen.getByLabelText("화면 이름 또는 ID"), {
      target: { value: "공통" },
    });
    expect(
      within(screen.getByRole("dialog")).getByText("공통코드 UI-27"),
    ).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-16");
  });
  it("already-open tab switching retains draft and close cancellation preserves focused editor", async () => {
    mount("/UI-27");
    const input = await screen.findByLabelText("업무 초안");
    fireEvent.change(input, { target: { value: "보존할 입력" } });
    const tabs = screen.getByLabelText("열린 화면");
    await waitFor(() => expect(tabs).toHaveTextContent("● 공통코드"));
    fireEvent.click(within(tabs).getByRole("button", { name: "대시보드" }));
    fireEvent.click(within(tabs).getByRole("button", { name: "● 공통코드" }));
    expect(screen.getByLabelText("업무 초안")).toHaveValue("보존할 입력");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("공통코드 닫기"));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "저장하지 않은 내용이 있습니다.",
    );
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByLabelText("업무 초안")).toHaveValue("보존할 입력");
    fireEvent.click(screen.getByLabelText("공통코드 닫기"));
    fireEvent.click(screen.getByText("나가기"));
    await waitFor(() =>
      expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-16"),
    );
    expect(screen.queryByLabelText("업무 초안")).not.toBeInTheDocument();
  });
  it("overflow counts dashboard, asks about LRU, and cancellation changes no tabs", async () => {
    mount();
    await screen.findByRole("heading", { name: "대시보드" });
    for (const id of [
      "UI-01",
      "UI-02",
      "UI-03",
      "UI-04",
      "UI-05",
      "UI-07",
      "UI-08",
    ])
      await searchOpen(id);
    const tabs = screen.getByLabelText("열린 화면");
    expect(within(tabs).getAllByRole("button", { name: /닫기$/ })).toHaveLength(
      7,
    );
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("화면 이름 또는 ID"), {
      target: { value: "UI-09" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: /UI-09/ }));
    expect(
      screen.getByRole("dialog", { name: "열린 화면 닫기" }),
    ).toHaveTextContent("화면 UI-01");
    fireEvent.click(screen.getByText("취소"));
    expect(within(tabs).getAllByRole("button", { name: /닫기$/ })).toHaveLength(
      7,
    );
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-08");
    await act(async () => {});
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: /UI-09/ }),
    );
    fireEvent.click(screen.getByText("닫고 열기"));
    await waitFor(() =>
      expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-09"),
    );
    expect(screen.queryByLabelText("화면 UI-01 닫기")).not.toBeInTheDocument();
    expect(within(tabs).getAllByRole("button", { name: /닫기$/ })).toHaveLength(
      7,
    );
  });
  it("dirty LRU requires both overflow and unsaved confirmation before discarding", async () => {
    mount("/UI-27");
    fireEvent.change(await screen.findByLabelText("업무 초안"), {
      target: { value: "유지" },
    });
    for (const id of ["UI-01", "UI-02", "UI-03", "UI-04", "UI-05", "UI-07"])
      await searchOpen(id);
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    let dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("화면 이름 또는 ID"), {
      target: { value: "UI-08" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: /UI-08/ }));
    expect(
      screen.getByRole("dialog", { name: "열린 화면 닫기" }),
    ).toHaveTextContent("공통코드");
    fireEvent.click(screen.getByText("닫고 열기"));
    expect(
      screen.getByRole("dialog", { name: "저장하지 않은 내용" }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByLabelText("업무 초안")).toHaveValue("유지");
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-07");
  });
  it("remembers five recent screens and never writes tabs or drafts to storage", () => {
    act(() => {
      for (const id of ["UI-01", "UI-02", "UI-03", "UI-04", "UI-05", "UI-07"])
        useSharedPreferences.getState().visit(id);
    });
    expect(useSharedPreferences.getState().recent).toEqual([
      "UI-07",
      "UI-05",
      "UI-04",
      "UI-03",
      "UI-02",
    ]);
    expect(Object.keys(localStorage)).toEqual(["si.recent"]);
  });
  it("mobile navigation filters PC-only leaves and inherits group lucide icon", () => {
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      value: 375,
    });
    const { container } = render(
      <MenuNavigation menus={menus} active="UI-16" onNavigate={() => {}} />,
    );
    expect(screen.queryByText("공통코드")).not.toBeInTheDocument();
    expect(
      screen.getByText("大시보드".replace("大", "대")),
    ).toBeInTheDocument();
    expect(
      container.querySelectorAll("svg.lucide-folder").length,
    ).toBeGreaterThan(1);
  });
  it("opens the seeded dashboard group directly instead of expanding its sole child", () => {
    const navigate = vi.fn();
    render(
      <MenuNavigation
        menus={[
          {
            ...menus[0],
            name: "대시보드",
            screenId: null,
            children: [children[0]],
          },
        ]}
        active="UI-27"
        onNavigate={navigate}
      />,
    );
    const dashboard = screen.getByRole("button", { name: "대시보드" });
    expect(dashboard).not.toHaveAttribute("aria-expanded");
    fireEvent.click(dashboard);
    expect(navigate).toHaveBeenCalledWith("UI-16");
    expect(screen.getAllByRole("button", { name: "대시보드" })).toHaveLength(1);
  });
  it("favorites reorder changes navigation order and local preference together", () => {
    act(() => {
      useSharedPreferences.getState().toggleFavorite("UI-01");
      useSharedPreferences.getState().toggleFavorite("UI-02");
    });
    render(
      <MenuNavigation menus={menus} active="UI-16" onNavigate={() => {}} />,
    );
    act(() => useSharedPreferences.getState().moveFavorite("UI-02", "UI-01"));
    expect(
      within(screen.getByLabelText("즐겨찾기 목록"))
        .getAllByRole("button")
        .map((button) => button.textContent),
    ).toEqual(["화면 UI-02", "화면 UI-01"]);
    expect(JSON.parse(localStorage.getItem("si.favorites")!)).toEqual([
      "UI-02",
      "UI-01",
    ]);
  });
});
