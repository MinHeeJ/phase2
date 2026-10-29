import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DispatchAttendanceDialog } from "./DispatchAttendanceDialog";
import type { DispatchAttendanceResult } from "./client";
import table from "../developers/Developers.module.css";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let attendance: DispatchAttendanceResult;
let fetcher: ReturnType<typeof vi.fn>;
let failRead: boolean;
let failWrite: boolean;
let closed: boolean;
let mode: string;
const saved = vi.fn().mockResolvedValue(undefined);
const close = vi.fn();
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-23T00:00:00Z"));
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  failRead = false;
  failWrite = false;
  closed = false;
  mode = "DAY";
  saved.mockClear();
  close.mockClear();
  attendance = {
    month: "2026-10",
    project_id: 1,
    items: [
      {
        asgn_id: 3,
        dev_id: 2,
        work_day_qty: 1,
        mth_work_day_qty: 6,
        calculated_mm: 0.17,
        assignment_mm: 0.8,
        price_available: false,
        warnings: [
          "단가 누락: 근태 저장은 가능하지만 청구 자동 생성은 불가합니다.",
        ],
      },
    ],
    warnings: [],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/projects/options")
      return response([
        { id: 1, name: "파견 프로젝트", type: "DP" },
        { id: 8, name: "턴키", type: "TK" },
      ]);
    if (url === "/api/projects/1")
      return response({ prj_id: 1, stlm_mthd_cd: mode });
    if (url === "/api/projects/1/assignments")
      return response([{ asgn_id: 3, name: "근태 인력" }]);
    if (url.startsWith("/api/month-close"))
      return response([
        { month: "2026-10", status: closed ? "CLOSED" : "OPEN" },
      ]);
    if (init?.method === "PUT") {
      if (failWrite)
        return new Response(
          JSON.stringify({
            success: false,
            code: "VALIDATION_ERROR",
            message: "월 근무일수를 확인하세요.",
            field: "items[0].mth_work_day_qty",
            requestId: "attendance-request",
          }),
          { status: 400 },
        );
      const body = JSON.parse(String(init.body));
      attendance = {
        ...attendance,
        items: body.items.map((row: object) => ({
          ...attendance.items[0],
          ...row,
        })),
      };
      return response(attendance);
    }
    if (url.startsWith("/api/dispatch-attendance")) {
      if (failRead)
        return new Response(
          JSON.stringify({
            success: false,
            message: "조회 실패",
            requestId: "attendance-read",
          }),
          { status: 500 },
        );
      return response(attendance);
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
function open(year = 2026) {
  return render(
    <DispatchAttendanceDialog
      year={year}
      initialMonth={`${year}-10`}
      initialProjectId={1}
      onSaved={saved}
      onClose={close}
    />,
  );
}
describe("파견 근태 작업 창", () => {
  it("loads public API data, reuses UI-02 table and shows price/MM warnings", async () => {
    open();
    expect(await screen.findByLabelText("근태 인력 근무일수")).toHaveValue(1);
    expect(screen.getByRole("table")).toHaveClass(table.table);
    expect(screen.getByText("단가 누락")).toBeInTheDocument();
    expect(screen.getByText("0.17")).toBeInTheDocument();
    expect(screen.getByText("0.80")).toBeInTheDocument();
    expect(
      screen.queryByRole("option", { name: "턴키" }),
    ).not.toBeInTheDocument();
  });
  it("edits and saves only approved input fields, then refreshes its parent", async () => {
    open();
    fireEvent.change(await screen.findByLabelText("근태 인력 근무일수"), {
      target: { value: "0" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(saved).toHaveBeenCalledOnce());
    expect(await screen.findByText("근태를 저장했습니다.")).toBeInTheDocument();
    const write = fetcher.mock.calls.find(([, init]) => init?.method === "PUT");
    expect(write?.[0]).toBe(
      "/api/dispatch-attendance?month=2026-10&projectId=1",
    );
    expect(JSON.parse(String(write?.[1].body))).toEqual({
      items: [{ asgn_id: 3, dev_id: 2, work_day_qty: 0, mth_work_day_qty: 6 }],
    });
  });
  it("keeps entered values and marks the exact field after a failed request", async () => {
    failWrite = true;
    open();
    const field = await screen.findByLabelText("근태 인력 해당 월 근무일수");
    fireEvent.change(field, { target: { value: "0" } });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(
      await screen.findByText("문의 번호 attendance-request"),
    ).toBeInTheDocument();
    expect(field).toHaveValue(0);
    expect(field).toHaveAttribute("aria-invalid", "true");
    expect(saved).not.toHaveBeenCalled();
  });
  it("closed month is readonly and hides save", async () => {
    closed = true;
    open();
    expect(await screen.findByLabelText("근태 인력 근무일수")).toHaveAttribute(
      "readonly",
    );
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByText("마감된 달은 근태를 수정할 수 없습니다."),
    ).toBeInTheDocument();
  });
  it("past year is readonly and hides mutation controls", async () => {
    open(2025);
    expect(await screen.findByLabelText("근태 인력 근무일수")).toHaveAttribute(
      "readonly",
    );
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("mobile keeps the scroll table but hides mutations", async () => {
    window.innerWidth = 767;
    open();
    expect(await screen.findByLabelText("근태 인력 근무일수")).toHaveAttribute(
      "readonly",
    );
    expect(screen.getByRole("table").parentElement).toHaveClass(
      table.tableScroll,
    );
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("empty result has the shared wording and no enabled save", async () => {
    attendance.items = [];
    open();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
  });
  it("read failure has a retry path instead of showing an editable stale table", async () => {
    failRead = true;
    open();
    expect(await screen.findByText("조회 실패")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByLabelText("근태 인력 근무일수")).toHaveValue(1);
  });
  it("HOUR missing workdays is explained without inventing days", async () => {
    mode = "HOUR";
    delete attendance.items[0].work_day_qty;
    open();
    expect(
      await screen.findByText("근무일수 미입력: 산출 MM 없음"),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("근태 인력 근무일수")).toHaveValue(null);
  });
  it("dirty close requires explicit discard and can keep input", async () => {
    open();
    fireEvent.change(await screen.findByLabelText("근태 인력 근무일수"), {
      target: { value: "2" },
    });
    fireEvent.click(screen.getByRole("button", { name: /^닫기$/ }));
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(screen.getByLabelText("근태 인력 근무일수")).toHaveValue(2);
    expect(close).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /^닫기$/ }));
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    expect(close).toHaveBeenCalledOnce();
  });
});
