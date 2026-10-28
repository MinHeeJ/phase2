import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import {
  DispatchAttendanceDialog,
  DispatchAttendancePanel,
} from "./DispatchAttendancePanel";
import type { DispatchAttendanceRow } from "./client";
import table from "../developers/Developers.module.css";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let rows: DispatchAttendanceRow[];
let fetcher: ReturnType<typeof vi.fn>;
let closed: boolean;
let method: string;
let failRead: boolean;
let failWrite: boolean;
const changed = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-28T00:00:00Z"));
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  rows = [
    {
      asgn_id: 20,
      dev_id: 10,
      work_day_qty: 1,
      rglr_tm_qty: null,
      ovtm_tm_qty: 0,
      mth_work_day_qty: 8,
      calculated_mm: 0.13,
      assignment_mm: 1,
      price_available: false,
      warnings: ["단가 미입력: 근태 저장은 가능하나 청구 생성은 불가합니다."],
    },
  ];
  closed = false;
  method = "DAY";
  failRead = false;
  failWrite = false;
  changed.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/projects/options"))
      return response([
        {
          id: 1,
          no: "P-ATTEND",
          name: "파견 시험",
          type: "DP",
          custName: null,
        },
      ]);
    if (url === "/api/projects/1")
      return response({ prj_id: 1, cntrct_ty_cd: "DP", stlm_mthd_cd: method });
    if (url === "/api/projects/1/assignments")
      return response([{ asgn_id: 20, name: "근태 인력" }]);
    if (url.startsWith("/api/month-close"))
      return response([
        { month: "2026-10", status: closed ? "CLOSED" : "OPEN" },
      ]);
    if (url.startsWith("/api/dispatch-attendance?")) {
      if (init?.method === "PUT") {
        if (failWrite)
          return new Response(
            JSON.stringify({
              success: false,
              code: "ATTENDANCE_BILLED",
              message: "확정 청구에 연결된 근태입니다.",
              field: "items[0].work_day_qty",
            }),
            { status: 409 },
          );
        const input = JSON.parse(init.body as string);
        rows = rows.map((row) => ({
          ...row,
          ...input.items.find(
            (i: { dev_id: number }) => i.dev_id === row.dev_id,
          ),
        }));
      } else if (failRead) throw new Error("offline");
      return response({
        month: "2026-10",
        project_id: 1,
        items: rows,
        warnings: [],
      });
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = (month = "2026-10") =>
  render(
    <DispatchAttendancePanel month={month} projectId={1} onChanged={changed} />,
  );
const writeCalls = () =>
  fetcher.mock.calls.filter((c) => c[1]?.method === "PUT");
const change = (name: string, value: string) =>
  fireEvent.change(screen.getByLabelText(name), { target: { value } });

describe("파견 근태", () => {
  it("renders API-backed identities, exact columns, MM comparison and shared table classes", async () => {
    panel();
    await screen.findByText("근태 인력");
    expect(screen.getByRole("table")).toHaveClass(table.table);
    expect(screen.getByRole("table").parentElement).toHaveClass(
      table.tableScroll,
    );
    expect(
      screen.getByRole("columnheader", { name: "⑦ 투입 MM" }),
    ).toBeInTheDocument();
    expect(screen.getByText("0.13")).toBeInTheDocument();
    expect(screen.getByText("1.00")).toBeInTheDocument();
    expect(screen.getByText("단가 미입력")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
  });
  it("saves only input fields to selected scope and refreshes parent", async () => {
    panel();
    await screen.findByText("근태 인력");
    change("근무일수 20", "2");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByTestId("attendance-success");
    const [url, init] = writeCalls()[0];
    expect(url).toBe("/api/dispatch-attendance?month=2026-10&projectId=1");
    expect(JSON.parse(init!.body as string)).toEqual({
      items: [
        {
          asgn_id: 20,
          dev_id: 10,
          work_day_qty: 2,
          rglr_tm_qty: null,
          ovtm_tm_qty: 0,
          mth_work_day_qty: 8,
        },
      ],
    });
    expect(changed).toHaveBeenCalledOnce();
  });
  it("allows zero days", async () => {
    panel();
    await screen.findByText("근태 인력");
    change("근무일수 20", "0");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByTestId("attendance-success");
    expect(writeCalls()).toHaveLength(1);
  });
  it.each(["0", "-1", "32", ""])(
    "rejects denominator %s without a write",
    async (value) => {
      panel();
      await screen.findByText("근태 인력");
      change("해당 월 근무일수 20", value);
      fireEvent.click(screen.getByRole("button", { name: "저장" }));
      await screen.findByText("해당 월 근무일수 입력값을 확인하세요.");
      expect(writeCalls()).toHaveLength(0);
    },
  );
  it("requires DAY days", async () => {
    panel();
    await screen.findByText("근태 인력");
    change("근무일수 20", "");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByText("근무일수 입력값을 확인하세요.");
    expect(writeCalls()).toHaveLength(0);
  });
  it("requires HOUR regular hours but allows no days", async () => {
    method = "HOUR";
    rows[0].work_day_qty = null;
    panel();
    await screen.findByText("근태 인력");
    change("초과 근무시간 20", "1");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByText("정규 근무시간 입력값을 확인하세요.");
    change("정규 근무시간 20", "0");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByTestId("attendance-success");
    expect(writeCalls()).toHaveLength(1);
  });
  it("preserves input and shows field/server conflict after failed save", async () => {
    failWrite = true;
    panel();
    await screen.findByText("근태 인력");
    change("근무일수 20", "2");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByText("확정 청구에 연결된 근태입니다.");
    expect(screen.getByLabelText("근무일수 20")).toHaveValue(2);
    expect(screen.getByLabelText("근무일수 20")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(changed).not.toHaveBeenCalled();
  });
  it("hides writes and locks fields in a closed month", async () => {
    closed = true;
    panel();
    await screen.findByText("근태 인력");
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("근무일수 20")).toBeDisabled();
    expect(
      screen.getByText("마감된 달은 수정할 수 없습니다."),
    ).toBeInTheDocument();
  });
  it("past years remain read-only", async () => {
    panel("2025-10");
    await screen.findByText("근태 인력");
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByText("과거 연도는 조회만 가능합니다."),
    ).toBeInTheDocument();
  });
  it("mobile remains read-only without write or upload controls", async () => {
    Object.defineProperty(window, "innerWidth", { value: 600, writable: true });
    render(
      <DispatchAttendancePanel
        month="2026-10"
        projectId={1}
        upload={<button>검사</button>}
      />,
    );
    await screen.findByText("근태 인력");
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "검사" }),
    ).not.toBeInTheDocument();
  });
  it("shows empty results", async () => {
    rows = [];
    panel();
    await screen.findByText("조회 결과가 없습니다.");
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
  });
  it("shows failed query without editable stale rows", async () => {
    failRead = true;
    panel();
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.queryByLabelText("근무일수 20")).not.toBeInTheDocument();
  });
  it("disables old scope editing until new query is selected", async () => {
    panel();
    await screen.findByText("근태 인력");
    fireEvent.change(screen.getByTestId("attendance-month"), {
      target: { value: "2026-11" },
    });
    expect(screen.getByLabelText("근무일수 20")).toBeDisabled();
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
  });
  it("warns before discarding dirty values and retains them when cancelled", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    panel();
    await screen.findByText("근태 인력");
    change("근무일수 20", "2");
    fireEvent.click(screen.getByRole("button", { name: "조회" }));
    expect(confirm).toHaveBeenCalledOnce();
    expect(screen.getByLabelText("근무일수 20")).toHaveValue(2);
  });
  it("dialog closes without writing", async () => {
    const close = vi.fn();
    render(
      <DispatchAttendanceDialog
        month="2026-10"
        projectId={1}
        onClose={close}
      />,
    );
    await screen.findByText("근태 인력");
    fireEvent.click(screen.getByTestId("attendance-close"));
    expect(close).toHaveBeenCalledOnce();
    expect(writeCalls()).toHaveLength(0);
  });
});
