import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { CorporateTaxPanel } from "./CorporateTaxPanel";
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let editable: boolean, failed: boolean, empty: boolean, saveFailed: boolean;
beforeEach(() => {
  editable = true;
  failed = false;
  empty = false;
  saveFailed = false;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (failed) throw new Error("offline");
    if (init?.method === "PUT" && saveFailed)
      return new Response(
        JSON.stringify({
          success: false,
          code: "CLOSED_MONTH",
          message: "마감 상태가 변경되었습니다.",
        }),
        { status: 409 },
      );
    if (url.includes("/plan"))
      return response({
        year: 2026,
        annual_amount: 120,
        reason: "기존",
        editable,
        months: [
          { month: "2026-10", amount: 10, closed: !editable, warning: null },
        ],
        history: [],
      });
    return response({
      year: 2026,
      payments: empty
        ? []
        : [
            {
              tax_pay_id: 5,
              tax_pay_se_cd: "FINAL",
              planned_date: "2027-03-31",
              planned_amount: 50,
              actual_date: null,
              actual_amount: null,
            },
          ],
      suggestions: [
        {
          tax_pay_se_cd: "FINAL",
          suggested_date: "2027-03-31",
          suggested_amount: 50,
        },
        {
          tax_pay_se_cd: "INTERIM",
          suggested_date: "2026-08-31",
          suggested_amount: null,
        },
      ],
      locked_types: [],
    });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
it("loads annual and monthly amounts and saves required reason", async () => {
  const changed = vi.fn();
  render(<CorporateTaxPanel year={2026} month="2026-10" onSaved={changed} />);
  const annual = await screen.findByLabelText("연간 예상 법인세");
  await waitFor(() => expect(annual).toHaveValue("120"));
  fireEvent.change(annual, { target: { value: "130" } });
  fireEvent.change(screen.getByLabelText("변경 사유"), {
    target: { value: "예상 변경" },
  });
  fireEvent.click(screen.getByRole("button", { name: "예상액 저장" }));
  await waitFor(() => expect(changed).toHaveBeenCalledOnce());
  expect(fetcher).toHaveBeenCalledWith(
    "/api/finance/tax/plan?year=2026",
    expect.objectContaining({
      method: "PUT",
      body: JSON.stringify({ annual_amount: 130, reason: "예상 변경" }),
    }),
  );
});
it("closed month is read only", async () => {
  editable = false;
  render(<CorporateTaxPanel year={2026} month="2026-10" />);
  await screen.findByText("마감된 월은 조회만 가능합니다.");
  expect(screen.getByLabelText("연간 예상 법인세")).toBeDisabled();
  expect(
    screen.queryByRole("button", { name: "예상액 저장" }),
  ).not.toBeInTheDocument();
});
it("past period is read only", async () => {
  render(<CorporateTaxPanel year={2026} month="2026-10" readOnly />);
  await screen.findByText("과거 기간은 조회만 가능합니다.");
  expect(
    screen.queryByRole("button", { name: "납부 일정 추가" }),
  ).not.toBeInTheDocument();
});
it("empty payments do not invent saved rows", async () => {
  empty = true;
  render(<CorporateTaxPanel year={2026} month="2026-10" />);
  await screen.findByText("등록된 납부 일정이 없습니다.");
  expect(screen.getByRole("button", { name: "납부 일정 추가" })).toBeEnabled();
});
it("failed reads can retry", async () => {
  failed = true;
  render(<CorporateTaxPanel year={2026} month="2026-10" />);
  await screen.findByRole("alert");
  failed = false;
  fireEvent.click(screen.getByRole("button", { name: "다시 조회" }));
  await screen.findByText("2027-03-31");
});
it("invalid money does not call writes", async () => {
  render(<CorporateTaxPanel year={2026} month="2026-10" />);
  const annual = await screen.findByLabelText("연간 예상 법인세");
  await waitFor(() => expect(annual).toHaveValue("120"));
  fireEvent.change(annual, { target: { value: "-1" } });
  fireEvent.click(screen.getByRole("button", { name: "예상액 저장" }));
  await screen.findByText("0 이상의 원 단위 정수와 변경 사유를 입력하세요.");
  expect(fetcher.mock.calls.some((c) => c[1]?.method === "PUT")).toBe(false);
});
it("failed save preserves draft", async () => {
  saveFailed = true;
  render(<CorporateTaxPanel year={2026} month="2026-10" />);
  const annual = await screen.findByLabelText("연간 예상 법인세");
  await waitFor(() => expect(annual).toHaveValue("120"));
  fireEvent.change(annual, { target: { value: "130" } });
  fireEvent.change(screen.getByLabelText("변경 사유"), {
    target: { value: "변경" },
  });
  fireEvent.click(screen.getByRole("button", { name: "예상액 저장" }));
  await screen.findByText("마감 상태가 변경되었습니다.");
  expect(annual).toHaveValue("130");
});
it("natural payment identity and actual values are submitted", async () => {
  render(<CorporateTaxPanel year={2026} month="2026-10" />);
  fireEvent.click(
    await screen.findByRole("button", { name: "납부 일정 추가" }),
  );
  fireEvent.change(screen.getByLabelText("예정 금액"), {
    target: { value: "70" },
  });
  fireEvent.click(screen.getByRole("button", { name: "납부 저장" }));
  await waitFor(() =>
    expect(fetcher.mock.calls.some((c) => c[1]?.method === "PUT")).toBe(true),
  );
  const call = fetcher.mock.calls.find((c) => c[1]?.method === "PUT")!;
  expect(call[0]).toBe("/api/finance/tax/payments?year=2026");
  expect(JSON.parse(call[1]!.body as string)).toEqual({
    payments: [
      {
        tax_pay_se_cd: "FINAL",
        planned_date: "2027-03-31",
        planned_amount: 70,
        actual_date: null,
        actual_amount: null,
      },
    ],
  });
});
