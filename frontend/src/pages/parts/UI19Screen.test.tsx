import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI19Screen from "./UI19Screen";
import { useScreenPeriod } from "../shared/useScreenPeriod";
import type { SettlementMonth } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let month: SettlementMonth;
let failed: boolean;
let empty: boolean;
let fetcher: ReturnType<typeof vi.fn>;
function Period() {
  const [value, set] = useScreenPeriod("UI-19");
  return (
    <input
      aria-label="공통 기간"
      value={value}
      onChange={(e) => set(e.target.value)}
    />
  );
}
function mount() {
  return render(
    <MemoryRouter>
      <Period />
      <UI19Screen />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  failed = false;
  empty = false;
  month = {
    month: "2026-10",
    status: "DRAFT",
    closed: false,
    provisional: true,
    standard: {
      part_std_hist_id: 4,
      ldr_nm: "파트장",
      prft_dstrb_rt: 40,
      bsn_fee_amt: 0,
    },
    corp_card_amt: 10,
    card_remark: "기존 비고",
    part_prft_amt: 22,
    people: [
      {
        dev_id: 8,
        dev_nm: "정산 인력",
        emp_se_cd: "FL",
        mm_qty: 1,
        mth_untprc_amt: 80,
        slry_amt: 100,
        bsn_fee_amt: 0,
        rtrpay_amt: 0,
        tot_exp_amt: 100,
        diff_amt: -20,
        prft_amt: -8,
        rmrk_desc: "영업비",
      },
    ],
    warnings: ["D-31/D-32 운영 승인 대기"],
    history: [],
  };
  fetcher = vi.fn(async (path: string, _init?: RequestInit) => {
    if (failed)
      return new Response(
        JSON.stringify({
          success: false,
          code: "VALIDATION_ERROR",
          message: "자료 조회 실패",
          field: "month",
        }),
        {
          status: 400,
          headers: { "Content-Type": "application/json" },
        },
      );
    const selectedMonth = new URL(path, "http://test").searchParams.get(
      "month",
    )!;
    const current = { ...month, month: selectedMonth };
    const part = { bsn_part_id: 3, bsn_part_nm: "시험 영업 파트" };
    return path.includes("/parts/3?")
      ? response({
          part,
          view: path.includes("TREND") ? "TREND" : "MONTH",
          months: [current],
        })
      : response(empty ? [] : [{ part, settlement: current }]);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.unstubAllGlobals());

describe("SCR-UI-19 part settlement", () => {
  it("renders API-backed people, negative profit and shared table styles", async () => {
    mount();
    const row = await screen.findByTestId("part-person-8");
    expect(row).toHaveTextContent("정산 인력");
    expect(row).toHaveTextContent("-8");
    expect(row.closest("table")?.className).toContain("table");
    expect(screen.getByTestId("part-approval-message")).toHaveTextContent(
      "운영 승인 대기",
    );
    expect(screen.getByTestId("part-confirm-button")).toBeDisabled();
    expect(screen.getByTestId("part-download-button")).toBeDisabled();
  });
  it("keeps card and remark inputs and never sends guessed write fields", async () => {
    mount();
    const input = await screen.findByTestId("part-card-input");
    fireEvent.change(input, { target: { value: "123" } });
    fireEvent.change(screen.getByTestId("part-remark-input"), {
      target: { value: "유지할 비고" },
    });
    fireEvent.click(screen.getByTestId("part-card-save-button"));
    expect(input).toHaveValue("123");
    expect(screen.getByTestId("part-remark-input")).toHaveValue("유지할 비고");
    expect(
      fetcher.mock.calls.every(([, init]) => !init || init.method == null),
    ).toBe(true);
  });
  it("shows separate card integer validation", async () => {
    mount();
    fireEvent.change(await screen.findByTestId("part-card-input"), {
      target: { value: "-1.5" },
    });
    expect(
      screen.getByText("카드액은 0 이상 원 단위 정수여야 합니다."),
    ).toBeInTheDocument();
    expect(screen.getByTestId("part-card-input")).toHaveValue("-1.5");
  });
  it("locks confirmed inputs and exposes cancellation as unavailable", async () => {
    month.status = "CONFIRM";
    month.provisional = false;
    mount();
    expect(await screen.findByTestId("part-card-input")).toHaveAttribute(
      "readonly",
    );
    expect(screen.getByTestId("part-remark-input")).toHaveAttribute("readonly");
    expect(screen.getByTestId("part-cancel-button")).toBeDisabled();
    expect(screen.queryByTestId("part-confirm-button")).not.toBeInTheDocument();
  });
  it("locks closed draft input", async () => {
    month.closed = true;
    mount();
    expect(await screen.findByTestId("part-card-input")).toHaveAttribute(
      "readonly",
    );
    expect(screen.getByTestId("part-summary-panel")).toHaveTextContent("마감");
  });
  it("shows five rows per person in trend and frozen or provisional badges", async () => {
    mount();
    await screen.findByTestId("part-person-8");
    fireEvent.click(screen.getByTestId("part-trend-tab"));
    const row = await screen.findByTestId("part-trend-8-mm-qty");
    const table = row.closest("table")!;
    expect(within(table).getAllByTestId(/^part-trend-8-/)).toHaveLength(5);
    expect(table).toHaveTextContent("잠정");
    expect(table).toHaveTextContent("카드 1회 차감");
    expect(fetcher.mock.calls.some(([url]) => url.includes("view=TREND"))).toBe(
      true,
    );
  });
  it("uses the same period as the shell binding", async () => {
    mount();
    await screen.findByTestId("part-person-8");
    fireEvent.change(screen.getByLabelText("공통 기간"), {
      target: { value: "2026-11" },
    });
    await waitFor(() =>
      expect(screen.getByTestId("part-summary-panel")).toHaveTextContent(
        "2026-11",
      ),
    );
    expect(
      fetcher.mock.calls.some(([url]) => url.includes("month=2026-11")),
    ).toBe(true);
  });
  it("renders an empty part list", async () => {
    empty = true;
    mount();
    expect(
      await screen.findByText("조회할 영업 파트가 없습니다."),
    ).toBeInTheDocument();
  });
  it("renders empty people without fabricated rows", async () => {
    month.people = [];
    mount();
    expect(
      await screen.findByText("해당 월 귀속 인력이 없습니다."),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("part-person-8")).not.toBeInTheDocument();
  });
  it("renders singular ApiError.field and retries", async () => {
    failed = true;
    mount();
    expect(await screen.findByTestId("part-error-field")).toHaveTextContent(
      "month",
    );
    failed = false;
    fireEvent.click(screen.getByTestId("part-retry-button"));
    expect(await screen.findByTestId("part-person-8")).toBeInTheDocument();
  });
  it("reuses the existing mobile-hidden writeActions class without hiding read tables", async () => {
    mount();
    const panel = await screen.findByTestId("part-write-panel");
    expect(panel.className).toContain("writeActions");
    expect(screen.getByTestId("part-detail-panel").className).not.toContain(
      "writeActions",
    );
  });
});
