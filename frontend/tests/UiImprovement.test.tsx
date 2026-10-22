import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { ResponsiveLayout } from "../src/app/ResponsiveLayout";
import { AppRouter } from "../src/app/router";
import { DataTable } from "../src/shared/Controls";
import { ParamsTab } from "../src/pages/admin/ParamsTab";
import { ExcelMapsTab } from "../src/pages/admin/ExcelMapsTab";
import { useSharedPreferences } from "../src/pages/shared/preferences";

const json = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
const page = (items: unknown[]) =>
  json({ items, total: items.length, page: 1, size: 100 });
const param = {
  oprt_std_id: "VAT_RATE",
  oprt_std_nm: "부가세",
  aply_bgn_ym: "2026-10",
  num_val: 10,
  txt_val: null,
  unit_nm: "%",
  use_plc_desc: "계산",
};
const mapping = {
  map_id: "9",
  excel_lbl_nm: "표기",
  trns_cd_grp_id: "JOB",
  trns_cd: "BE",
  cd_grp_nm: "직무",
  cd_nm: "개발",
  map_role_cd: "MAIN",
  rmrk_desc: "기존 메모",
  use_yn: "Y",
};
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
    dense: false,
    pageSize: 50,
  });
});
afterEach(() => vi.unstubAllGlobals());

describe("UI improvement regressions (rendered components)", () => {
  it("desktop menu toggle updates the persisted collapsed preference", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => json([])),
    );
    render(
      <MemoryRouter initialEntries={["/UI-16"]}>
        <AppRouter />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByLabelText("메뉴 닫기"));
    expect(useSharedPreferences.getState().collapsed).toBe(true);
    expect(screen.getByLabelText("업무 메뉴")).toHaveAttribute(
      "data-compact",
      "true",
    );
    expect(localStorage.getItem("si.collapsed")).toBe("true");
  });

  it("mobile leaf navigation closes the overlay, group expansion does not", () => {
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      value: 375,
    });
    const { container } = render(
      <ResponsiveLayout
        header="제목"
        menu={
          <nav>
            <button aria-expanded="false">묶음</button>
            <button>화면 이동</button>
          </nav>
        }
      >
        내용
      </ResponsiveLayout>,
    );
    fireEvent.click(screen.getByLabelText("메뉴 열기"));
    fireEvent.click(screen.getByText("묶음"));
    expect(screen.getByLabelText("메뉴 닫기")).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    fireEvent.click(screen.getByText("화면 이동"));
    expect(container.firstElementChild?.className).not.toContain("expanded");
    fireEvent.click(screen.getByLabelText("메뉴 열기"));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByLabelText("메뉴 열기")).toHaveAttribute(
      "aria-expanded",
      "false",
    );
  });

  it("empty menu explains the cause and retries the actual read", async () => {
    const fetcher = vi.fn(async () => json([]));
    vi.stubGlobal("fetch", fetcher);
    render(
      <MemoryRouter initialEntries={["/UI-16"]}>
        <AppRouter />
      </MemoryRouter>,
    );
    expect(
      await screen.findByText(
        "표시할 메뉴가 없습니다. 메뉴 설정을 확인해 주세요.",
      ),
    ).toBeVisible();
    fireEvent.click(screen.getByText("다시 시도"));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  });

  it("does not present loading or failed data as an empty query result", () => {
    const props = {
      rows: [],
      total: 0,
      columns: [{ key: "name", label: "이름", render: () => "" }],
      rowKey: () => "",
    };
    const { rerender } = render(<DataTable {...props} showEmpty={false} />);
    expect(
      screen.queryByText("조건에 맞는 결과가 없습니다"),
    ).not.toBeInTheDocument();
    rerender(<DataTable {...props} showEmpty />);
    expect(screen.getByRole("cell")).toHaveTextContent(
      "조건에 맞는 결과가 없습니다",
    );
  });

  it("common-code title consumes the menu name and exposes a working favorite", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) =>
        url === "/api/menus"
          ? json([
              {
                menuId: 1,
                name: "시스템",
                screenId: null,
                icon: "gear",
                mobile: true,
                children: [
                  {
                    menuId: 2,
                    name: "공통코드 관리",
                    screenId: "UI-27",
                    icon: null,
                    mobile: false,
                    children: [],
                  },
                ],
              },
            ])
          : page([]),
      ),
    );
    render(
      <MemoryRouter initialEntries={["/UI-27"]}>
        <AppRouter />
      </MemoryRouter>,
    );
    expect(
      await screen.findByRole("heading", { level: 1, name: "공통코드 관리" }),
    ).toBeVisible();
    expect(
      screen.getByRole("navigation", { name: "현재 위치" }),
    ).toHaveTextContent("시스템");
    fireEvent.click(screen.getByLabelText("즐겨찾기"));
    expect(screen.getByLabelText("즐겨찾기")).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(
      within(screen.getByLabelText("즐겨찾기 목록")).getByText("공통코드 관리"),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "삭제" }),
    ).not.toBeInTheDocument();
  });

  it("saving a parameter refreshes the already expanded version history", async () => {
    let saved = false;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init?: RequestInit) => {
        if (init?.method === "PUT") {
          saved = true;
          return json(null);
        }
        if (url.startsWith("/api/month-close")) return json([]);
        if (url.includes("/versions?"))
          return page(
            saved
              ? [{ ...param, aply_bgn_ym: "2026-11", num_val: 11 }, param]
              : [param],
          );
        return page([param]);
      }),
    );
    render(<ParamsTab />);
    fireEvent.click(await screen.findByText("이력 보기"));
    await screen.findByRole("heading", { name: "부가세 적용 이력" });
    fireEvent.click(screen.getByText("값 변경"));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
    );
    fireEvent.change(screen.getByLabelText("새 값"), {
      target: { value: "11" },
    });
    fireEvent.change(screen.getByLabelText("적용 시작월"), {
      target: { value: "2026-11" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(
      await within(screen.getByTestId("param-versions")).findByText("11.0%"),
    ).toBeVisible();
  });

  it("mapping save accepts the refreshed server row and clears dirty status", async () => {
    let saved = false;
    const dirty = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === "PATCH") {
          saved = true;
          return json(null);
        }
        return page([
          { ...mapping, rmrk_desc: saved ? "서버 정규화" : mapping.rmrk_desc },
        ]);
      }),
    );
    render(<ExcelMapsTab groups={[]} onDirtyChange={dirty} />);
    fireEvent.change(await screen.findByLabelText("메모 9"), {
      target: { value: "입력 메모" },
    });
    await waitFor(() => expect(dirty).toHaveBeenLastCalledWith(true));
    await act(async () =>
      fireEvent.click(screen.getByRole("button", { name: "저장" })),
    );
    await waitFor(() =>
      expect(screen.getByLabelText("메모 9")).toHaveValue("서버 정규화"),
    );
    expect(dirty).toHaveBeenLastCalledWith(false);
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
  });
});
