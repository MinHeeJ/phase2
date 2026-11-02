import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { PaymentOrderPanel } from "./PaymentOrderPanel";
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let closed: boolean;
let paid: boolean;
let empty: boolean;
let failed: boolean;
let writeFailure: boolean;
beforeEach(() => {
  closed = false;
  paid = false;
  empty = false;
  failed = false;
  writeFailure = false;
  fetcher = vi.fn(async (_url: string, init?: RequestInit) => {
    if (failed) throw new Error("offline");
    if (init?.method === "PUT" && writeFailure)
      return new Response(
        JSON.stringify({
          success: false,
          code: "CLOSED_MONTH",
          message: "마감된 월입니다.",
          field: "month",
        }),
        { status: 409 },
      );
    const items =
      init?.method === "PUT"
        ? JSON.parse(init.body as string).items
        : empty
          ? []
          : [{ pay_odr_no: 1, planned_date: "2026-10-10", paid }];
    return response({ items, month_status: closed ? "CLOSED" : "OPEN" });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
it("loads API dates and saves only contract fields then refreshes parent", async () => {
  const changed = vi.fn();
  render(<PaymentOrderPanel month="2026-10" onSaved={changed} />);
  fireEvent.click(
    await screen.findByRole("button", { name: "지급 차수 설정" }),
  );
  fireEvent.change(screen.getByLabelText("1차 지급 예정일"), {
    target: { value: "2026-10-12" },
  });
  fireEvent.click(screen.getByRole("button", { name: "저장" }));
  await waitFor(() => expect(changed).toHaveBeenCalledOnce());
  expect(fetcher).toHaveBeenCalledWith(
    "/api/finance/payment-orders?month=2026-10",
    expect.objectContaining({
      method: "PUT",
      body: JSON.stringify({
        items: [{ pay_odr_no: 1, planned_date: "2026-10-12" }],
      }),
    }),
  );
});
it("closed month hides editing", async () => {
  closed = true;
  render(<PaymentOrderPanel month="2026-10" />);
  await screen.findByText("마감된 월은 조회만 가능합니다.");
  expect(
    screen.queryByRole("button", { name: "지급 차수 설정" }),
  ).not.toBeInTheDocument();
});
it("past period is read only", async () => {
  render(<PaymentOrderPanel month="2026-10" readOnly />);
  await screen.findByText("과거 기간은 조회만 가능합니다.");
  expect(
    screen.queryByRole("button", { name: "지급 차수 설정" }),
  ).not.toBeInTheDocument();
});
it("paid date is disabled", async () => {
  paid = true;
  render(<PaymentOrderPanel month="2026-10" />);
  fireEvent.click(
    await screen.findByRole("button", { name: "지급 차수 설정" }),
  );
  expect(screen.getByLabelText("1차 지급 예정일")).toBeDisabled();
});
it("empty list has an actionable state", async () => {
  empty = true;
  render(<PaymentOrderPanel month="2026-10" />);
  await screen.findByText("설정된 지급 차수가 없습니다.");
  expect(screen.getByRole("button", { name: "지급 차수 설정" })).toBeEnabled();
});
it("failed read can be retried", async () => {
  failed = true;
  render(<PaymentOrderPanel month="2026-10" />);
  await screen.findByRole("alert");
  failed = false;
  fireEvent.click(screen.getByRole("button", { name: "다시 조회" }));
  await screen.findByText("2026-10-10");
});
it("failed save retains entered date", async () => {
  writeFailure = true;
  render(<PaymentOrderPanel month="2026-10" />);
  fireEvent.click(
    await screen.findByRole("button", { name: "지급 차수 설정" }),
  );
  fireEvent.change(screen.getByLabelText("1차 지급 예정일"), {
    target: { value: "2026-10-12" },
  });
  fireEvent.click(screen.getByRole("button", { name: "저장" }));
  await screen.findByText("마감된 월입니다.");
  expect(screen.getByLabelText("1차 지급 예정일")).toHaveValue("2026-10-12");
});
it("invalid date stays local", async () => {
  render(<PaymentOrderPanel month="2026-10" />);
  fireEvent.click(
    await screen.findByRole("button", { name: "지급 차수 설정" }),
  );
  fireEvent.change(screen.getByLabelText("1차 지급 예정일"), {
    target: { value: "2026-11-10" },
  });
  fireEvent.click(screen.getByRole("button", { name: "저장" }));
  await screen.findByText("지급 예정일은 선택한 월 안에서 입력하세요.");
  expect(fetcher.mock.calls.some((call) => call[1]?.method === "PUT")).toBe(
    false,
  );
});
