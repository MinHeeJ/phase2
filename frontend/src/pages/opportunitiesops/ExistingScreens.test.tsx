import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import {
  PipelineProfitStructure,
  PipelineNetProfit,
  PipelineCashFlow,
  PipelineCommonCodes,
  PipelineWorkforceBoard,
  PipelineSharedScreen,
  PipelineCodeGroupDialog,
  PipelineExcelMapDialog,
} from "./screens";

const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, data, meta: {} }), {
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  sessionStorage.clear();
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/menus")
      return ok(
        ["UI-16", "UI-22", "UI-23", "UI-24", "UI-25"].map((id, i) => ({
          menuId: i + 1,
          name: `기존 ${id}`,
          screenId: id,
          icon: null,
          mobile: true,
          children: [],
        })),
      );
    if (url.startsWith("/api/codes/")) return ok([]);
    if (url.startsWith("/api/month-close")) return ok([]);
    return new Response(
      JSON.stringify({
        success: false,
        code: "SOURCE_ERROR",
        message: "원천 조회 실패",
        field: null,
        requestId: "preserve-test",
      }),
      { status: 500, headers: { "Content-Type": "application/json" } },
    );
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
it.each([
  ["SCR-UI-07", PipelineProfitStructure, "전체 수익 구조"],
  ["SCR-UI-09", PipelineNetProfit, "순이익표"],
  ["SCR-UI-21", PipelineCashFlow, "자금 흐름"],
  ["SCR-UI-27", PipelineCommonCodes, "공통코드 관리"],
  ["SCR-UI-28", PipelineWorkforceBoard, "인력 운영 보드"],
] as const)(
  "%s retains its original owner and server-error path",
  async (_id, Component, title) => {
    render(
      <MemoryRouter>
        <Component name={title} />
      </MemoryRouter>,
    );
    expect(screen.getByRole("heading", { name: title })).toBeVisible();
    await screen.findAllByText("원천 조회 실패");
    expect(
      fetcher.mock.calls.every(
        ([, init]) =>
          !init ||
          !(init as RequestInit).method ||
          (init as RequestInit).method === "GET",
      ),
    ).toBe(true);
  },
);
it.each(["UI-16", "UI-22", "UI-23", "UI-24", "UI-25"])(
  "SCR-%s retains its menu-backed shared page without a duplicate dashboard",
  async (id) => {
    render(
      <MemoryRouter>
        <PipelineSharedScreen screenId={id} />
      </MemoryRouter>,
    );
    await screen.findByRole("heading", { name: `기존 ${id}` });
    expect(fetcher.mock.calls.every(([url]) => url === "/api/menus")).toBe(
      true,
    );
  },
);
it("SCR-UI-27-M3 keeps original code-group fields and does not write on cancel", () => {
  const cancel = vi.fn();
  render(
    <PipelineCodeGroupDialog
      onCancel={cancel}
      onSaved={vi.fn()}
      reload={vi.fn(async () => {})}
    />,
  );
  expect(screen.getByRole("textbox", { name: /그룹 코드/ })).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "취소" }));
  expect(cancel).toHaveBeenCalledOnce();
  expect(fetcher).not.toHaveBeenCalled();
});
it("SCR-UI-27-M4 keeps the original mapping form instead of introducing pipeline uploads", async () => {
  const cancel = vi.fn();
  render(
    <PipelineExcelMapDialog
      groups={[]}
      onCancel={cancel}
      onSaved={vi.fn()}
      reload={vi.fn(async () => {})}
    />,
  );
  expect(screen.getByRole("heading", { name: "대응 추가" })).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "취소" }));
  await waitFor(() => expect(cancel).toHaveBeenCalledOnce());
  expect(
    fetcher.mock.calls.some(([url]) => String(url).includes("opportunities")),
  ).toBe(false);
});
