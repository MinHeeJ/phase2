import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SalesPartCreateDialog } from "./SalesPartCreateDialog";
import type { SalesPartDetail } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const missing = () =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code: "NOT_FOUND",
      message: "선택월 기준값이 없습니다.",
      field: null,
      requestId: "parameter-request",
    }),
    { status: 404 },
  );
const part: SalesPartDetail = {
  bsn_part_id: "4",
  bsn_part_nm: "추가 파트",
  hq_yn: "N",
  use_yn: "Y",
  sort_seq: 1,
  referenceMonth: "2026-10",
  currentStandard: null,
  scheduledChanges: [],
  standards: [],
  peopleCount: 0,
  projectCount: 0,
  lastClosedMonth: "2026-09",
  firstAllowedMonth: "2026-10",
};
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/params/"))
      return response({
        num_val: url.includes("PART_PROFIT_RATE") ? 40 : 1500000,
      });
    if (url === "/api/sales-parts" && init?.method === "POST")
      return response(part);
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const submit = () =>
  fireEvent.submit(screen.getByTestId("sales-part-create-dialog"));
const posts = () =>
  fetcher.mock.calls.filter(([, init]) => init?.method === "POST");
function open(firstAllowedMonth: string | null = "2026-10") {
  const reload = vi.fn(async () => {}),
    onSaved = vi.fn(),
    onCancel = vi.fn();
  render(
    <SalesPartCreateDialog
      firstAllowedMonth={firstAllowedMonth}
      lastClosedMonth={firstAllowedMonth ? "2026-09" : null}
      reload={reload}
      onSaved={onSaved}
      onCancel={onCancel}
    />,
  );
  return { reload, onSaved, onCancel };
}
async function fill() {
  await waitFor(() =>
    expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue(40),
  );
  change("파트명", "추가 파트");
  change("파트장", "파트장");
}

describe("영업 파트 추가 입력과 저장 복구", () => {
  it.each([
    ["파트명", 31, "파트명은 30자까지 입력해 주세요."],
    ["파트장", 51, "파트장은 50자까지 입력해 주세요."],
  ])(
    "rejects excessive %s even when form validation is bypassed",
    async (label, length, message) => {
      open();
      await fill();
      change(label, "가".repeat(length));
      submit();
      expect(await screen.findByRole("alert")).toHaveTextContent(message);
      expect(screen.getByLabelText(label)).toHaveFocus();
      expect(posts()).toHaveLength(0);
    },
  );

  it.each([0, 100])(
    "accepts %s percent and the exact name/leader length limits",
    async (rate) => {
      const { onSaved, reload } = open();
      await fill();
      change("파트명", "가".repeat(30));
      change("파트장", "나".repeat(50));
      change("이익 배분율 (%)", String(rate));
      change("영업비 (원)", "1");
      submit();
      await waitFor(() => expect(onSaved).toHaveBeenCalledWith(part));
      expect(reload).toHaveBeenCalledOnce();
      expect(JSON.parse(posts()[0][1].body)).toEqual({
        bsn_part_nm: "가".repeat(30),
        ldr_nm: "나".repeat(50),
        aply_bgn_ym: "2026-10",
        prft_dstrb_rt: rate,
        bsn_fee_amt: 1,
      });
    },
  );

  it.each(["PART_PROFIT_RATE", "PART_SALES_FEE"])(
    "keeps the available default when %s is absent and permits manual input",
    async (key) => {
      fetcher.mockImplementation(async (url: string, init?: RequestInit) => {
        if (url.includes(`/api/params/${key}?`)) return missing();
        if (url.startsWith("/api/params/"))
          return response({
            num_val: url.includes("PART_PROFIT_RATE") ? 40 : 1500000,
          });
        if (init?.method === "POST") return response(part);
        throw new Error(`Unexpected test request ${url}`);
      });
      const { onSaved } = open();
      expect(await screen.findByText(/기준값을 다시 조회하거나/)).toBeVisible();
      const absent =
        key === "PART_PROFIT_RATE" ? "이익 배분율 (%)" : "영업비 (원)";
      const available =
        key === "PART_PROFIT_RATE" ? "영업비 (원)" : "이익 배분율 (%)";
      expect(screen.getByLabelText(absent)).toHaveValue(null);
      expect(screen.getByLabelText(available)).toHaveValue(
        key === "PART_PROFIT_RATE" ? 1500000 : 40,
      );
      expect(screen.getByLabelText(absent)).toBeEnabled();
      change("파트명", "직접 입력 파트");
      change("파트장", "파트장");
      change(absent, "1");
      submit();
      await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
    },
  );

  it("does not request invented monthly defaults without closing history and accepts an explicit past month", async () => {
    const { onSaved } = open(null);
    expect(fetcher).not.toHaveBeenCalled();
    expect(screen.getByLabelText("적용 시작월")).toHaveValue("");
    expect(screen.getByLabelText("적용 시작월")).not.toHaveAttribute("min");
    change("파트명", "과거 파트");
    change("파트장", "파트장");
    submit();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "올바른 연월을 입력하세요.",
    );
    expect(posts()).toHaveLength(0);
    change("적용 시작월", "2025-01");
    await fill();
    submit();
    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
    expect(fetcher).toHaveBeenCalledWith(
      "/api/params/PART_PROFIT_RATE?month=2025-01",
      expect.anything(),
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/params/PART_SALES_FEE?month=2025-01",
      expect.anything(),
    );
    expect(JSON.parse(posts()[0][1].body).aply_bgn_ym).toBe("2025-01");
  });

  it("blocks submission until both monthly defaults have settled", async () => {
    let complete!: (value: Response) => void;
    const delayed = new Promise<Response>((resolve) => {
      complete = resolve;
    });
    fetcher.mockImplementation((url: string) =>
      url.includes("PART_SALES_FEE")
        ? delayed
        : Promise.resolve(response({ num_val: 40 })),
    );
    open();
    change("파트명", "추가 파트");
    change("파트장", "파트장");
    submit();
    expect(screen.getByRole("button", { name: "추가" })).toBeDisabled();
    expect(posts()).toHaveLength(0);
    await act(async () => {
      complete(response({ num_val: 1500000 }));
    });
    expect(screen.getByLabelText("영업비 (원)")).toHaveValue(1500000);
    expect(screen.getByRole("button", { name: "추가" })).toBeEnabled();
  });

  it("recovers a committed create by requerying, never by posting the part again", async () => {
    const { reload, onSaved } = open();
    reload.mockRejectedValueOnce(new Error("목록 조회 실패"));
    await fill();
    submit();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "저장 후 조회에 실패했습니다",
    );
    expect(onSaved).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "추가" })).toBeDisabled();
    expect(screen.getByLabelText("파트명")).toBeDisabled();
    submit();
    expect(posts()).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(part));
    expect(onSaved).toHaveBeenCalledOnce();
    expect(reload).toHaveBeenCalledTimes(2);
    expect(posts()).toHaveLength(1);
  });

  it("keeps committed data protected across repeated requery failures", async () => {
    const { reload, onSaved } = open();
    reload
      .mockRejectedValueOnce(new Error("조회 실패"))
      .mockRejectedValueOnce(new Error("재조회 실패"));
    await fill();
    submit();
    await screen.findByText(/저장 후 조회에 실패했습니다/);
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "다시 시도" })).toBeEnabled(),
    );
    submit();
    expect(posts()).toHaveLength(1);
    expect(onSaved).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
    expect(posts()).toHaveLength(1);
  });
});
