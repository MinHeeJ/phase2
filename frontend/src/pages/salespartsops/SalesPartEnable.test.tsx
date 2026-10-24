import { StrictMode } from "react";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SalesPartManagement from "./SalesPartManagement";
import { SalesPartStateDialog } from "./SalesPartStateDialog";
import { listSalesPartOptions } from "./client";
import type { SalesPartDetail } from "../salesparts/client";

const part: SalesPartDetail = {
  bsn_part_id: "2",
  bsn_part_nm: "영업 파트",
  hq_yn: "N",
  use_yn: "N",
  sort_seq: 1,
  referenceMonth: "2026-10",
  currentStandard: null,
  scheduledChanges: [],
  peopleCount: 0,
  projectCount: 0,
  standards: [],
  lastClosedMonth: null,
  firstAllowedMonth: null,
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failed = (status = 404) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code: status === 404 ? "NOT_FOUND" : "SERVER_ERROR",
      message: "영업 파트를 조회할 수 없습니다.",
      field: null,
      requestId: "enable-request",
    }),
    { status, headers: { "Content-Type": "application/json" } },
  );
let fetcher: ReturnType<typeof vi.fn>, row: SalesPartDetail;
beforeEach(() => {
  row = { ...part };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/sales-parts/options")
      return response(
        row.use_yn === "Y"
          ? [{ id: row.bsn_part_id, name: row.bsn_part_nm, hq: false }]
          : [],
      );
    if (url.startsWith("/api/sales-parts?"))
      return response({ items: [row], total: 1, page: 1, size: 50 });
    if (url === "/api/sales-parts/2/enable" && init?.method === "POST") {
      row = { ...row, use_yn: "Y" };
      return response(row);
    }
    if (url === "/api/sales-parts/2") return response(row);
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function open(reload = vi.fn(async () => {})) {
  const onSaved = vi.fn(),
    onClose = vi.fn();
  render(
    <StrictMode>
      <SalesPartStateDialog
        part={part}
        action="enable"
        reload={reload}
        onSaved={onSaved}
        onClose={onClose}
      />
    </StrictMode>,
  );
  return { reload, onSaved, onClose };
}
async function detail() {
  fireEvent.click(await screen.findByRole("button", { name: "영업 파트" }));
  const drawer = await screen.findByRole("dialog", { name: "영업 파트 상세" });
  await within(drawer).findByText("2026-10 기준");
  return drawer;
}

describe("SCR-UI-15 sales part enable", () => {
  it("stopped part offers enable then refreshes list, detail and selectable options", async () => {
    expect(await listSalesPartOptions()).toEqual([]);
    render(<SalesPartManagement />);
    const drawer = await detail();
    expect(
      within(drawer).getByText("사용을 중지한 파트입니다."),
    ).toBeInTheDocument();
    fireEvent.click(
      within(drawer).getByRole("button", { name: "파트 더보기" }),
    );
    expect(
      within(drawer).queryByRole("menuitem", { name: "사용 중지" }),
    ).toBeNull();
    fireEvent.click(
      within(drawer).getByRole("menuitem", { name: "다시 사용" }),
    );
    await screen.findByText("영업 파트을 다시 사용합니다.");
    expect(screen.queryByText("사용을 중지한 파트입니다.")).toBeNull();
    expect(within(drawer).getByText("사용")).toBeInTheDocument();
    expect(
      within(screen.getByTestId("sales-part-row-2")).getByText("사용"),
    ).toBeInTheDocument();
    expect(await listSalesPartOptions()).toEqual([
      { id: "2", name: "영업 파트", hq: false },
    ]);
    const calls = fetcher.mock.calls.filter(([url]) => url.endsWith("/enable"));
    expect(calls).toHaveLength(1);
    expect(calls[0][1]).toMatchObject({ method: "POST" });
    expect(calls[0][1].body).toBeUndefined();
    expect(
      fetcher.mock.calls.filter(([url]) => url === "/api/sales-parts/2"),
    ).toHaveLength(2);
    fireEvent.click(
      within(drawer).getByRole("button", { name: "파트 더보기" }),
    );
    expect(
      within(drawer).queryByRole("menuitem", { name: "다시 사용" }),
    ).toBeNull();
    expect(
      within(drawer).getByRole("menuitem", { name: "사용 중지" }),
    ).toBeInTheDocument();
  });
  it("headquarters has no lifecycle menu", async () => {
    row = { ...part, hq_yn: "Y", use_yn: "Y" };
    render(<SalesPartManagement />);
    const drawer = await detail();
    expect(
      within(drawer).queryByRole("button", { name: "파트 더보기" }),
    ).toBeNull();
    expect(fetcher.mock.calls.some(([, init]) => init?.method === "POST")).toBe(
      false,
    );
  });
  it("pending enable runs only once under StrictMode and cannot be cancelled", async () => {
    let finish: (value: Response) => void = () => {};
    fetcher.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    const { onSaved, onClose, reload } = open();
    expect(fetcher).toHaveBeenCalledOnce();
    expect(screen.getByTestId("sales-part-state-cancel")).toBeDisabled();
    fireEvent.click(screen.getByTestId("sales-part-state-cancel"));
    expect(onClose).not.toHaveBeenCalled();
    expect(onSaved).not.toHaveBeenCalled();
    await act(async () => finish(response({ ...part, use_yn: "Y" })));
    await waitFor(() =>
      expect(onSaved).toHaveBeenCalledWith("영업 파트을 다시 사용합니다."),
    );
    expect(reload).toHaveBeenCalledOnce();
    expect(reload.mock.invocationCallOrder[0]).toBeLessThan(
      onSaved.mock.invocationCallOrder[0],
    );
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it("missing part keeps error and request id without a success announcement", async () => {
    fetcher.mockResolvedValueOnce(failed());
    const { onSaved, reload } = open();
    await screen.findByText("영업 파트를 조회할 수 없습니다.");
    expect(screen.getByText(/enable-request/)).toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
    expect(reload).not.toHaveBeenCalled();
    expect(screen.getByTestId("sales-part-enable-retry")).toBeEnabled();
  });
  it("a definite rejection is retried only by an explicit enable action", async () => {
    fetcher.mockResolvedValueOnce(failed());
    const { onSaved } = open();
    await screen.findByText("영업 파트를 조회할 수 없습니다.");
    expect(fetcher).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByTestId("sales-part-enable-retry"));
    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it("lost response recovers reads without replaying or claiming a confirmed mutation", async () => {
    fetcher.mockRejectedValueOnce(new Error("network"));
    const { onSaved, reload } = open();
    await screen.findByText(/저장 결과를 다시 조회/);
    expect(screen.getByTestId("sales-part-enable-retry")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(reload).toHaveBeenCalledOnce());
    expect(fetcher).toHaveBeenCalledOnce();
    expect(onSaved).not.toHaveBeenCalled();
  });
  it("confirmed enable with a failed refresh finishes after read recovery without another POST", async () => {
    const reload = vi.fn(async () => {});
    reload.mockRejectedValueOnce(new Error("refresh failed"));
    const { onSaved } = open(reload);
    await screen.findByText(
      "저장 후 조회에 실패했습니다. 현재 상태를 확인하세요.",
    );
    expect(onSaved).not.toHaveBeenCalled();
    expect(screen.getByTestId("sales-part-enable-retry")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() =>
      expect(onSaved).toHaveBeenCalledWith("영업 파트을 다시 사용합니다."),
    );
    expect(reload).toHaveBeenCalledTimes(2);
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it("failed read recovery retains the error and does not announce success", async () => {
    const reload = vi.fn(async () => {
      throw new Error("refresh failed");
    });
    const { onSaved } = open(reload);
    await screen.findByText(
      "저장 후 조회에 실패했습니다. 현재 상태를 확인하세요.",
    );
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(2));
    expect(onSaved).not.toHaveBeenCalled();
    expect(fetcher).toHaveBeenCalledOnce();
  });
});
