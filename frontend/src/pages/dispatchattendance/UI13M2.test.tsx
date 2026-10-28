import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { UI13M2 } from "./UI13M2";
import { seoulMonth } from "../shared/PeriodBar";
import table from "../developers/Developers.module.css";

const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const month = seoulMonth();
let fetcher: ReturnType<typeof vi.fn>;
let method: string,
  closed: boolean,
  empty: boolean,
  failRead: boolean,
  failWrite: boolean;
const close = vi.fn(),
  saved = vi.fn();
beforeEach(() => {
  method = "DAY";
  closed = false;
  empty = false;
  failRead = false;
  failWrite = false;
  close.mockReset();
  saved.mockReset();
  vi.stubGlobal("innerWidth", 1280);
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/projects/options")
      return ok([
        { id: 7, no: "P-7", name: "파견 프로젝트", type: "DP" },
        { id: 8, no: "P-8", name: "턴키 프로젝트", type: "TK" },
      ]);
    if (url === "/api/projects/7") return ok({ stlm_mthd_cd: method });
    if (url === "/api/projects/7/assignments")
      return ok([{ asgn_id: 10, name: "근태 시험 인력" }]);
    if (url.startsWith("/api/month-close?"))
      return ok([
        {
          month: new URLSearchParams(url.split("?")[1]).get("from"),
          status: closed ? "CLOSED" : "OPEN",
        },
      ]);
    if (url.startsWith("/api/dispatch-attendance?")) {
      if (
        (init?.method === "PUT" && failWrite) ||
        (init?.method !== "PUT" && failRead)
      ) {
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: failWrite ? "ATTENDANCE_BILLED" : "SERVER_ERROR",
            message: failWrite
              ? "확정 청구에 연결된 근태입니다."
              : "조회에 실패했습니다.",
            field: failWrite ? "items[0].work_day_qty" : null,
            requestId: "attendance-test",
          }),
          { status: failWrite ? 409 : 500 },
        );
      }
      return ok({
        month,
        project_id: 7,
        warnings: [],
        items: empty
          ? []
          : [
              {
                asgn_id: 10,
                dev_id: 20,
                work_day_qty: method === "DAY" ? 10 : null,
                rglr_tm_qty: method === "HOUR" ? 160 : null,
                ovtm_tm_qty: 0,
                mth_work_day_qty: 21,
                calculated_mm: method === "DAY" ? 0.48 : null,
                assignment_mm: 0.5,
                price_available: false,
                warnings: [
                  "단가 미입력: 근태 저장은 가능하나 청구 생성은 불가합니다.",
                ],
              },
            ],
      });
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = (props: Partial<React.ComponentProps<typeof UI13M2>> = {}) =>
  render(
    <UI13M2
      projectId={7}
      month={month}
      onClose={close}
      onSaved={saved}
      {...props}
    />,
  );
const ready = () => screen.findByTestId("attendance-row-10");
const change = (label = "20 근무일수", value = "11") =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const writes = () =>
  fetcher.mock.calls.filter(([, init]) => init?.method === "PUT");
describe("manual dispatch attendance", () => {
  it("renders shared table, required fields, MM comparison and price warning", async () => {
    mount();
    await ready();
    expect(screen.getByRole("table")).toHaveClass(table.table);
    expect(screen.getByText("근태 시험 인력")).toBeVisible();
    expect(screen.getByText("0.48")).toBeVisible();
    expect(screen.getByText("0.50")).toBeVisible();
    expect(screen.getByText("단가 미입력", { exact: true })).toBeVisible();
    expect(
      screen.getByRole("columnheader", { name: "근무일수 *" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("option", { name: /턴키 프로젝트/ }),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("attendance-save")).toBeDisabled();
  });
  it("sends only edited natural identities, saves without price, refreshes parent and attendance", async () => {
    mount();
    await ready();
    change();
    fireEvent.click(screen.getByTestId("attendance-save"));
    await screen.findByTestId("attendance-success");
    expect(writes()).toHaveLength(1);
    expect(JSON.parse(writes()[0][1].body)).toEqual({
      items: [
        {
          asgn_id: 10,
          dev_id: 20,
          work_day_qty: 11,
          ovtm_tm_qty: 0,
          mth_work_day_qty: 21,
        },
      ],
    });
    expect(writes()[0][0]).toBe(
      `/api/dispatch-attendance?month=${month}&projectId=7`,
    );
    expect(saved).toHaveBeenCalledOnce();
    expect(
      fetcher.mock.calls.filter(
        ([url, init]) =>
          url.startsWith("/api/dispatch-attendance?") && !init?.method,
      ),
    ).toHaveLength(2);
  });
  it("allows HOUR with absent work days, zero regular hours and null MM", async () => {
    method = "HOUR";
    mount();
    await ready();
    expect(
      screen.getByRole("columnheader", { name: "정규 근무시간 *" }),
    ).toBeVisible();
    change("20 정규 근무시간", "0");
    fireEvent.click(screen.getByTestId("attendance-save"));
    await screen.findByTestId("attendance-success");
    expect(JSON.parse(writes()[0][1].body).items[0]).not.toHaveProperty(
      "work_day_qty",
    );
    expect(JSON.parse(writes()[0][1].body).items[0].rglr_tm_qty).toBe(0);
  });
  it.each(["", "0", "-1", "32", "1.25"])(
    "rejects invalid denominator %s without sending",
    async (value) => {
      mount();
      await ready();
      change("20 해당 월 근무일수", value);
      fireEvent.click(screen.getByTestId("attendance-save"));
      expect(
        await screen.findByTestId("attendance-validation"),
      ).toHaveTextContent("해당 월 근무일수");
      expect(writes()).toHaveLength(0);
    },
  );
  it.each(["DAY", "HOUR"])("requires %s specific quantity", async (unit) => {
    method = unit;
    mount();
    await ready();
    change(unit === "DAY" ? "20 근무일수" : "20 정규 근무시간", "");
    fireEvent.click(screen.getByTestId("attendance-save"));
    expect(await screen.findByTestId("attendance-validation")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("keeps values and field error on confirmed conflict", async () => {
    failWrite = true;
    mount();
    await ready();
    change();
    fireEvent.click(screen.getByTestId("attendance-save"));
    expect(
      await screen.findByTestId("attendance-field-error"),
    ).toHaveTextContent("items[0].work_day_qty");
    expect(screen.getByLabelText("20 근무일수")).toHaveValue("11");
    expect(saved).not.toHaveBeenCalled();
  });
  it("empty results have no mutation request", async () => {
    empty = true;
    mount();
    expect(await screen.findByTestId("attendance-empty")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("failed read shows request id and retry without a save control", async () => {
    failRead = true;
    mount();
    expect(await screen.findByText("조회에 실패했습니다.")).toBeVisible();
    expect(screen.getByText("문의 번호 attendance-test")).toBeVisible();
    expect(screen.queryByTestId("attendance-save")).not.toBeInTheDocument();
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await ready();
  });
  it("shows loading before data resolves", async () => {
    const original = fetcher.getMockImplementation()!;
    let release!: () => void;
    const waiting = new Promise<void>((resolve) => {
      release = resolve;
    });
    fetcher.mockImplementation(async (url, init) => {
      if (url.startsWith("/api/dispatch-attendance")) await waiting;
      return original(url, init);
    });
    mount();
    expect(screen.getByRole("status", { name: "불러오는 중" })).toBeVisible();
    release();
    await ready();
  });
  it.each(["past", "mobile", "readonly", "closed"])(
    "%s hides save and locks every quantity",
    async (mode) => {
      if (mode === "mobile") vi.stubGlobal("innerWidth", 767);
      if (mode === "closed") closed = true;
      mount({
        readOnly: mode === "readonly",
        month: mode === "past" ? "2020-10" : month,
      });
      await ready();
      expect(screen.queryByTestId("attendance-save")).not.toBeInTheDocument();
      expect(screen.getByLabelText("20 근무일수")).toHaveAttribute("readonly");
      expect(writes()).toHaveLength(0);
    },
  );
  it("dirty close requires discard, cancel sends nothing", async () => {
    mount();
    await ready();
    change();
    fireEvent.click(screen.getByTestId("attendance-close"));
    expect(
      await screen.findByTestId("attendance-discard-dialog"),
    ).toBeVisible();
    expect(close).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("attendance-keep"));
    expect(screen.getByLabelText("20 근무일수")).toHaveValue("11");
    fireEvent.click(screen.getByTestId("attendance-close"));
    fireEvent.click(screen.getByTestId("attendance-discard"));
    expect(close).toHaveBeenCalledOnce();
    expect(writes()).toHaveLength(0);
  });
  it("can mount sibling upload region with selected scope and refresh callback", async () => {
    const region = vi.fn(() => <div data-testid="upload-region">B.3</div>);
    mount({ uploadRegion: region });
    await ready();
    expect(screen.getByTestId("upload-region")).toBeVisible();
    expect(region).toHaveBeenLastCalledWith(
      expect.objectContaining({
        projectId: 7,
        month,
        onApplied: expect.any(Function),
      }),
    );
  });
  it("prevents silently switching scope with unsaved inputs", async () => {
    mount();
    await ready();
    change();
    fireEvent.click(screen.getByTestId("attendance-search"));
    expect(
      await screen.findByTestId("attendance-validation"),
    ).toHaveTextContent("먼저 저장");
    expect(writes()).toHaveLength(0);
  });
});
