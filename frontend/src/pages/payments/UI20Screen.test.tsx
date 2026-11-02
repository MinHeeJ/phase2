import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI20Screen from "./UI20Screen";
import type { Payment, PaymentList } from "./client";

let data: PaymentList;
let fetcher: ReturnType<typeof vi.fn>;
let fail = false;
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const payment: Payment = {
  pay_id: 7,
  pay_ym: "2026-10",
  pay_odr_no: 1,
  pay_se_cd: "ETC",
  rcvr_se_cd: "ETC",
  dev_id: null,
  ptnr_id: null,
  recipient: "수령인",
  sply_amt: 1000000,
  vat_amt: 0,
  ddct_amt: 0,
  pay_amt: 1000000,
  pay_plan_dt: "2026-10-10",
  pay_dt: null,
  pay_stts_cd: "PLAN",
  pay_src_cd: "MANUAL",
  src_rec_id: null,
  cry_pay_id: null,
  bank: "004",
  account: "001234",
  dpstr_nm: "예금주",
  remark: null,
  updated_at: "2026-10-01T12:00:00",
  executed_amt: 0,
};
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-30T00:00:00Z"));
  fail = false;
  data = {
    items: [{ ...payment }],
    total: 1,
    summary: [
      {
        pay_odr_no: 1,
        count: 1,
        planned_amount: 1000000,
        paid_amount: 0,
        remaining_amount: 1000000,
      },
    ],
    closed: false,
    warnings: [],
    categories: [{ code: "ETC", name: "기타" }],
    banks: [{ code: "004", name: "국민은행" }],
  };
  fetcher = vi.fn(async (_url: string, init?: RequestInit) => {
    if (fail)
      return new Response(
        JSON.stringify({
          success: false,
          code: "STALE_PAYMENT",
          message: "다시 조회하세요.",
          requestId: "test-request",
        }),
        { status: 409 },
      );
    if (init?.method === "POST" || init?.method === "PUT")
      return response(payment);
    return response(data);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
const open = () =>
  render(
    <MemoryRouter>
      <UI20Screen />
    </MemoryRouter>,
  );
describe("지급 관리 SCR-UI-20", () => {
  it("renders API rows, source totals, status colours and leading-zero accounts", async () => {
    open();
    expect(await screen.findByText("수령인")).toBeInTheDocument();
    expect(screen.getByRole("table")).toHaveTextContent("예정");
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining("/api/finance/payments?month=2026-10"),
      expect.anything(),
    );
    expect(screen.getByText("총 1건")).toBeInTheDocument();
  });
  it("empty response is not a dummy list", async () => {
    data.items = [];
    data.total = 0;
    open();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
  });
  it("closed month disables writes", async () => {
    data.closed = true;
    open();
    await screen.findByText("수령인");
    expect(
      screen.getByRole("button", { name: "수기 지급 추가" }),
    ).toBeDisabled();
    expect(screen.getByRole("button", { name: "원천 재생성" })).toBeDisabled();
  });
  it("paid rows cannot be edited or completed", async () => {
    data.items[0].pay_stts_cd = "PAID";
    data.items[0].executed_amt = 1000000;
    open();
    await screen.findByText("수령인");
    expect(
      screen.queryByRole("button", { name: "수정" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "지급 완료" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "완료 취소" })).toBeEnabled();
  });
  it("complete dialog sends selected ID, partial amount and version", async () => {
    open();
    await screen.findByText("수령인");
    fireEvent.click(screen.getByRole("button", { name: "지급 완료" }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("지급액"), {
      target: { value: "400000" },
    });
    fireEvent.change(within(dialog).getByLabelText("지급일"), {
      target: { value: "2026-10-30" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "확인" }));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        "/api/finance/payments/7/complete",
        expect.objectContaining({
          method: "POST",
          body: JSON.stringify({
            pay_dt: "2026-10-30",
            paid_amount: 400000,
            updated_at: payment.updated_at,
          }),
        }),
      ),
    );
  });
  it("request failure keeps dialog values and displays error", async () => {
    data.items[0].pay_stts_cd = "PAID";
    data.items[0].executed_amt = 1000000;
    open();
    await screen.findByText("수령인");
    fireEvent.click(screen.getByRole("button", { name: "완료 취소" }));
    fireEvent.change(screen.getByLabelText("취소 사유"), {
      target: { value: "오입력" },
    });
    fail = true;
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "확인" }),
    );
    await screen.findAllByText("다시 조회하세요.");
    expect(screen.getByLabelText("취소 사유")).toHaveValue("오입력");
  });
  it("edit preserves account text and calls selected payment identity", async () => {
    open();
    await screen.findByText("수령인");
    fireEvent.click(screen.getByRole("button", { name: "수정" }));
    expect(screen.getByLabelText("계좌")).toHaveValue("001234");
    fireEvent.change(screen.getByLabelText("수령인"), {
      target: { value: "변경 수령인" },
    });
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "확인" }),
    );
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        "/api/finance/payments/7",
        expect.objectContaining({ method: "PUT" }),
      ),
    );
  });
  it("carry window sends target month", async () => {
    open();
    await screen.findByText("수령인");
    fireEvent.click(screen.getByRole("button", { name: "이월" }));
    fireEvent.change(screen.getByLabelText("다음 미마감 월"), {
      target: { value: "2026-11" },
    });
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "확인" }),
    );
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        "/api/finance/payments/7/carry",
        expect.objectContaining({
          method: "POST",
          body: JSON.stringify({ month: "2026-11" }),
        }),
      ),
    );
  });
  it("carried parent cannot be selected or completed", async () => {
    data.items[0].pay_stts_cd = "UNPAID";
    open();
    await screen.findByText("수령인");
    expect(screen.getByRole("checkbox")).toBeDisabled();
    expect(
      screen.queryByRole("button", { name: "지급 완료" }),
    ).not.toBeInTheDocument();
  });
  it("export review warns that it is not bank submission", async () => {
    open();
    await screen.findByText("수령인");
    expect(
      screen.getByRole("button", { name: "은행 이체 파일" }),
    ).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: "은행 이체 파일" }));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "은행 제출용이 아닙니다",
    );
    expect(screen.getByRole("dialog")).toHaveTextContent("총 1건");
  });
  it("failed list exposes retry and blocks writes", async () => {
    fail = true;
    open();
    await screen.findByText("다시 조회하세요.");
    expect(
      screen.getByRole("button", { name: "수기 지급 추가" }),
    ).toBeDisabled();
    expect(screen.queryByText("수령인")).not.toBeInTheDocument();
  });
  it("negative manual payment stays in form", async () => {
    open();
    await screen.findByText("수령인");
    fireEvent.click(screen.getByRole("button", { name: "수기 지급 추가" }));
    fireEvent.change(screen.getByLabelText("공제"), { target: { value: "1" } });
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "확인" }),
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "지급액은 0원 이상이어야 합니다.",
    );
  });
  it("mobile shows PC guidance instead of payment controls", () => {
    Object.defineProperty(window, "innerWidth", {
      value: 375,
      configurable: true,
    });
    open();
    expect(screen.getByText(/PC에서/)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
