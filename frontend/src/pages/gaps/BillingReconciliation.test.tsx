import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI13Screen from "../billingsops/UI13Screen";
import type { BillingGap, GapPage } from "../billingsops/client";
import { downloadUploadFile } from "../uploadsops/client";
import controls from "../../shared/Controls.module.css";

vi.mock("../uploadsops/client", () => ({ downloadUploadFile: vi.fn() }));
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let page: GapPage;
let failed: boolean;
let hold: (() => Promise<Response>) | undefined;
const row = (overrides: Partial<BillingGap> = {}): BillingGap => ({
  project_id: 901,
  project_name: "정산 프로젝트",
  month: "2026-09",
  basis_supply_amt: 2000000,
  confirmed_supply_amt: 0,
  difference_amt: 2000000,
  tolerance: 10000,
  judgment: "청구 없음",
  explanation: "신규 투입자 청구 누락 가능성이 있습니다.",
  attendance_missing: false,
  suspected_people: [{ name: "계약 인력", management_number: "K-00901" }],
  billing_ids: [],
  ...overrides,
});
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-29T00:00:00Z"));
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  page = {
    month: "2026-09",
    items: [row()],
    total_difference_amt: 2000000,
    total: 1,
    page: 0,
    size: 50,
  };
  failed = false;
  hold = undefined;
  vi.mocked(downloadUploadFile).mockReset().mockResolvedValue(undefined);
  fetcher = vi.fn(async (url: string) => {
    if (url.startsWith("/api/billings/gaps?")) {
      if (hold) return hold();
      if (failed) throw new Error("offline");
      return response(page);
    }
    if (url.startsWith("/api/billings/summary?"))
      return response({
        year: 2026,
        annual_confirmed_supply_amt: 0,
        current_month_confirmed_supply_amt: 0,
        confirmed_receivable_amt: 0,
        overdue_amt: 0,
        planned_supply_amt: 0,
        draft_supply_amt: 0,
      });
    if (url.startsWith("/api/billings?"))
      return response({ items: [], total: 0, page: 0, size: 50 });
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});
function show(state?: unknown) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: "/UI-13", state }]}>
      <UI13Screen />
    </MemoryRouter>,
  );
}
async function open() {
  fireEvent.click(screen.getByRole("tab", { name: "청구 누락 점검" }));
  await screen.findByTestId("billing-gap-901");
}
const requests = () =>
  fetcher.mock.calls.map(
    ([url]) => new URLSearchParams((url as string).split("?")[1]),
  );
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe("billing reconciliation in the existing UI-13 route", () => {
  it("renders the API-backed columns, explanation and contract-person identity", async () => {
    show();
    await open();
    for (const name of [
      "프로젝트",
      "귀속월",
      "투입 기준 금액",
      "청구 공급가",
      "차이",
      "판정",
      "설명",
    ])
      expect(screen.getByRole("columnheader", { name })).toBeInTheDocument();
    expect(screen.getByText("계약 인력 · K-00901")).toBeInTheDocument();
    expect(
      screen.getByText("신규 투입자 청구 누락 가능성이 있습니다."),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/점검 월 2026-09 · 총 차이 2,000,000원/),
    ).toBeInTheDocument();
  });
  it("exports the displayed server-default month, not a new default or unsaved filters", async () => {
    show();
    await open();
    change("월", "2026-11");
    change("판정", "맞음");
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await waitFor(() => expect(downloadUploadFile).toHaveBeenCalledOnce());
    const [path] = vi.mocked(downloadUploadFile).mock.calls[0];
    expect(path.startsWith("/api/billings/gaps/export?")).toBe(true);
    const params = new URLSearchParams(path.split("?")[1]);
    expect(params.get("month")).toBe("2026-09");
    expect(params.has("judgment")).toBe(false);
    expect(params.has("year")).toBe(false);
    expect(params.has("page")).toBe(false);
    expect(params.has("size")).toBe(false);
  });
  it("forwards the selected closing month through navigation state without changing the YEAR mode", async () => {
    show({ closingMonth: "2025-12" });
    await open();
    expect(screen.getByText("2025년")).toBeInTheDocument();
    expect(screen.getByLabelText("월")).toHaveValue("2025-12");
    expect(requests().some((q) => q.get("month") === "2025-12")).toBe(true);
    expect(
      screen.queryByTestId("billing-gap-action-901"),
    ).not.toBeInTheDocument();
  });
  it("rejects malformed navigation month rather than transmitting it", async () => {
    show({ closingMonth: "2025-13" });
    await open();
    expect(screen.getByText("2026년")).toBeInTheDocument();
    expect(requests().some((q) => q.get("month") === "2025-13")).toBe(false);
  });
  it("hides correction for a prior-year row even when the shell YEAR is current", async () => {
    page = { ...page, month: "2025-12", items: [row({ month: "2025-12" })] };
    show();
    await open();
    expect(screen.getByText("2026년")).toBeInTheDocument();
    expect(
      screen.queryByTestId("billing-gap-action-901"),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "엑셀 다운로드" })).toBeEnabled();
  });
  it("keeps mobile reconciliation readable but hides row writes", async () => {
    window.innerWidth = 767;
    show();
    await open();
    expect(
      screen.queryByTestId("billing-gap-action-901"),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-add")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "엑셀 다운로드" })).toBeEnabled();
  });
  it("passes the selected project's month to the established editor", async () => {
    const editor = vi.fn((props: { projectId?: number; month?: string }) => (
      <div role="dialog" aria-label="연결된 청구 등록">
        {props.month}
      </div>
    ));
    render(
      <MemoryRouter>
        <UI13Screen renderEditor={editor} />
      </MemoryRouter>,
    );
    await open();
    fireEvent.click(screen.getByTestId("billing-gap-action-901"));
    await screen.findByRole("dialog", { name: "연결된 청구 등록" });
    expect(editor.mock.calls[0][0]).toMatchObject({
      projectId: 901,
      month: "2026-09",
    });
  });
  it("uses applied filters for list and export and never exports only the current page", async () => {
    show();
    await open();
    change("월", "2026-08");
    change("판정", "적게 청구");
    change("검색", "정산");
    page = {
      ...page,
      month: "2026-08",
      items: [row({ month: "2026-08", judgment: "적게 청구" })],
    };
    fireEvent.click(screen.getByTestId("billing-query"));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "엑셀 다운로드" }),
      ).toBeEnabled(),
    );
    expect(
      requests().some(
        (q) =>
          q.get("month") === "2026-08" &&
          q.get("judgment") === "적게 청구" &&
          q.get("q") === "정산",
      ),
    ).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await waitFor(() => expect(downloadUploadFile).toHaveBeenCalledOnce());
    const params = new URLSearchParams(
      vi.mocked(downloadUploadFile).mock.calls[0][0].split("?")[1],
    );
    expect(Object.fromEntries(params)).toMatchObject({
      month: "2026-08",
      judgment: "적게 청구",
      q: "정산",
    });
    expect(params.has("page")).toBe(false);
  });
  it.each([
    "맞음",
    "맞음(무상)",
    "적게 청구",
    "많이 청구",
    "청구 없음",
    "근태 미입력",
  ])("preserves the shared status tone for %s", async (judgment) => {
    page.items = [row({ judgment })];
    show();
    await open();
    expect(screen.getByText(judgment, { selector: "span" })).toHaveClass(
      judgment.startsWith("맞음") ? controls.success : controls.warning,
    );
  });
  it("renders the shared empty state without a sample row", async () => {
    page = { ...page, items: [], total: 0, total_difference_amt: 0 };
    show();
    fireEvent.click(screen.getByRole("tab", { name: "청구 누락 점검" }));
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "조건 초기화" }),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("billing-gap-901")).not.toBeInTheDocument();
  });
  it("blocks export while loading and hides stale rows when a new filter is pending", async () => {
    show();
    await open();
    hold = () => new Promise<Response>(() => {});
    change("월", "2026-08");
    fireEvent.click(screen.getByTestId("billing-query"));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "엑셀 다운로드" }),
      ).toBeDisabled(),
    );
    expect(screen.queryByTestId("billing-gap-901")).not.toBeInTheDocument();
    expect(downloadUploadFile).not.toHaveBeenCalled();
  });
  it("shows failed requests with retry and disables export until a successful read", async () => {
    failed = true;
    show();
    fireEvent.click(screen.getByRole("tab", { name: "청구 누락 점검" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "서버에 연결할 수 없습니다.",
    );
    expect(
      screen.getByRole("button", { name: "엑셀 다운로드" }),
    ).toBeDisabled();
    expect(screen.queryByTestId("billing-gap-901")).not.toBeInTheDocument();
    failed = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByTestId("billing-gap-901");
    expect(screen.getByRole("button", { name: "엑셀 다운로드" })).toBeEnabled();
  });
});
