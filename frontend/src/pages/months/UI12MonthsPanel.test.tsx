import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { UI12MonthsPanel, type UI12MonthsPanelProps } from "./UI12MonthsPanel";
import type { AssignmentMonthsResult } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failure = (
  code: string,
  message: string,
  field: string | null = null,
  status = 409,
) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code,
      message,
      field,
      requestId: "months-request",
    }),
    { status },
  );
let data: AssignmentMonthsResult;
let fetcher: ReturnType<typeof vi.fn>;
let readFailure: boolean;
let writeFailure: Response | null;
const changed = vi.fn().mockResolvedValue(undefined);
const dirty = vi.fn();
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  changed.mockClear();
  dirty.mockClear();
  readFailure = false;
  writeFailure = null;
  data = {
    asgn_id: 17,
    updated_at: "2026-10-14T09:00:00.123456",
    warnings: [{ code: "OVER_MM", message: "MM 1.0 초과", work_ym: "2026-09" }],
    items: [
      {
        work_ym: "2026-09",
        auto_mm_qty: 0.52,
        mm_qty: 0.52,
        mnl_yn: "N",
        person_month_mm_qty: 1.2,
        closed_yn: "Y",
      },
      {
        work_ym: "2026-10",
        auto_mm_qty: 0.52,
        mm_qty: 0.5,
        mnl_yn: "Y",
        person_month_mm_qty: 0.5,
        closed_yn: "N",
      },
    ],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (
      url !== "/api/assignments/17/months" &&
      url !== "/api/assignments/18/months"
    )
      throw new Error(`Unexpected ${url}`);
    if (init?.method === "PUT") {
      if (writeFailure) return writeFailure.clone();
      const body = JSON.parse(init.body as string);
      data = {
        ...data,
        updated_at: "2026-10-14T09:00:01.123457",
        items: data.items.map((row) => {
          const edit = body.months.find(
            (m: { work_ym: string }) => m.work_ym === row.work_ym,
          );
          const quantity = edit
            ? Math.round(edit.mm_qty * 100) / 100
            : row.mm_qty;
          return {
            ...row,
            mm_qty: quantity,
            mnl_yn: quantity === row.auto_mm_qty ? "N" : "Y",
            person_month_mm_qty: quantity,
          };
        }),
      };
      return response(data);
    }
    if (readFailure) throw new Error("offline");
    return response(data);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = (props: Partial<UI12MonthsPanelProps> = {}) =>
  render(
    <UI12MonthsPanel
      assignmentId={17}
      onChanged={changed}
      onDirtyChange={dirty}
      {...props}
    />,
  );
const writes = () =>
  fetcher.mock.calls.filter((call) => call[1]?.method === "PUT");
const input = () => screen.getByRole("textbox", { name: "2026-10 MM" });
async function loaded() {
  return screen.findByTestId("assignment-month-row-2026-10");
}
const edit = (value: string) =>
  fireEvent.change(input(), { target: { value } });
const save = () =>
  fireEvent.click(screen.getByRole("button", { name: "월별 저장" }));

describe("assignment monthly MM", () => {
  it("shows contract columns, automatic value, manual badge, lock and warning-coloured person total", async () => {
    panel();
    const row = await loaded();
    for (const name of [
      "월",
      "자동 MM",
      "MM 입력",
      "수정함",
      "그 사람 그 달 합계",
      "동작",
    ])
      expect(screen.getByRole("columnheader", { name })).toBeVisible();
    expect(within(row).getByText("0.52")).toBeVisible();
    expect(within(row).getByText("수정함")).toBeVisible();
    expect(input()).toHaveValue("0.50");
    expect(screen.getByRole("textbox", { name: "2026-09 MM" })).toHaveAttribute(
      "readonly",
    );
    expect(
      screen.queryByTestId("assignment-month-auto-2026-09"),
    ).not.toBeInTheDocument();
    expect(screen.getByText("🔒 마감")).toBeVisible();
    expect(
      screen.getByTestId("assignment-month-total-2026-09").className,
    ).toMatch(/over/);
    expect(screen.getByText(/MM 1.0 초과/)).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("automatic restore changes only draft until explicit save and keeps full timestamp", async () => {
    panel();
    await loaded();
    fireEvent.click(screen.getByRole("button", { name: "자동값으로" }));
    expect(input()).toHaveValue("0.52");
    expect(writes()).toHaveLength(0);
    expect(dirty).toHaveBeenLastCalledWith(true);
    save();
    await screen.findByText("월별 MM을 저장했습니다.");
    expect(JSON.parse(writes()[0][1]!.body as string)).toEqual({
      updated_at: "2026-10-14T09:00:00.123456",
      months: [{ work_ym: "2026-10", mm_qty: 0.52 }],
    });
    expect(
      within(screen.getByTestId("assignment-month-row-2026-10")).queryByText(
        "수정함",
      ),
    ).not.toBeInTheDocument();
    expect(changed).toHaveBeenCalledTimes(1);
    expect(
      fetcher.mock.calls.filter((c) => c[1]?.method !== "PUT"),
    ).toHaveLength(2);
  });
  it("saves zero and refreshes real response instead of assuming original automatic value", async () => {
    panel();
    await loaded();
    edit("0");
    save();
    await screen.findByText("월별 MM을 저장했습니다.");
    expect(input()).toHaveValue("0.00");
    expect(JSON.parse(writes()[0][1]!.body as string).months).toEqual([
      { work_ym: "2026-10", mm_qty: 0 },
    ]);
  });
  it("lets server round the third decimal before requery", async () => {
    panel();
    await loaded();
    edit("0.523");
    save();
    await screen.findByText("월별 MM을 저장했습니다.");
    expect(input()).toHaveValue("0.52");
    expect(JSON.parse(writes()[0][1]!.body as string).months[0].mm_qty).toBe(
      0.523,
    );
  });
  it.each(["-0.1", "1.20", "", "not a number"])(
    "rejects invalid MM %s without sending",
    async (value) => {
      panel();
      await loaded();
      edit(value);
      save();
      expect(
        screen.getByText("MM을 0~1.00 사이 숫자로 입력하세요."),
      ).toBeVisible();
      expect(input()).toHaveAttribute("aria-invalid", "true");
      expect(writes()).toHaveLength(0);
    },
  );
  it.each([
    { assignmentStatus: "CANCELLED" },
    { projectStatus: "END" },
    { projectStatus: "LOST" },
    { useYn: "N" },
    { readOnly: true },
  ])(
    "hides mutations for locked lifecycle or old-year readOnly %j",
    async (props) => {
      panel(props);
      await loaded();
      expect(input()).toHaveAttribute("readonly");
      expect(
        screen.queryByRole("button", { name: "월별 저장" }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "자동값으로" }),
      ).not.toBeInTheDocument();
      expect(writes()).toHaveLength(0);
    },
  );
  it("allows open-month editing on a replaced original", async () => {
    panel({ assignmentStatus: "REPLACED" });
    await loaded();
    expect(input()).not.toHaveAttribute("readonly");
    expect(screen.getByRole("button", { name: "자동값으로" })).toBeEnabled();
  });
  it("does not invent a role-denied view for anonymous users", async () => {
    panel();
    await loaded();
    expect(screen.getByRole("button", { name: "월별 저장" })).toBeVisible();
    expect(screen.queryByText(/권한이 없습니다/)).not.toBeInTheDocument();
  });
  it("shows a loading state without stale rows", async () => {
    fetcher.mockImplementation(() => new Promise(() => {}));
    panel();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "월별 저장" }),
    ).not.toBeInTheDocument();
  });
  it("renders empty result without editing controls", async () => {
    data.items = [];
    data.warnings = [];
    panel();
    await screen.findByText("월별 투입 정보가 없습니다.");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "월별 저장" }),
    ).not.toBeInTheDocument();
  });
  it("failed read displays retry and recovers", async () => {
    readFailure = true;
    panel();
    await screen.findByRole("alert");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    readFailure = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await loaded();
  });
  it("stale save shows original conflict and preserves draft without automatic retry", async () => {
    writeFailure = failure(
      "STALE_ASSIGNMENT",
      "다른 사람이 먼저 바꿨습니다. 창을 다시 열어 주세요.",
    );
    panel();
    await loaded();
    edit("0.40");
    save();
    await screen.findByText(
      "다른 사람이 먼저 바꿨습니다. 창을 다시 열어 주세요.",
    );
    expect(input()).toHaveValue("0.40");
    expect(writes()).toHaveLength(1);
    expect(changed).not.toHaveBeenCalled();
  });
  it("server field error is shown below the edited input and draft stays", async () => {
    writeFailure = failure(
      "INVALID_INPUT",
      "MM을 0~1.00 사이 숫자로 입력하세요.",
      "mm_qty",
      400,
    );
    panel();
    await loaded();
    edit("0.40");
    save();
    await waitFor(() =>
      expect(input()).toHaveAttribute("aria-invalid", "true"),
    );
    expect(input()).toHaveValue("0.40");
    expect(screen.getByText("문의 번호 months-request")).toBeVisible();
  });
  it("discard through parent unmount never sends edited values", async () => {
    const view = panel();
    await loaded();
    edit("0.40");
    view.unmount();
    expect(writes()).toHaveLength(0);
    expect(dirty).toHaveBeenLastCalledWith(false);
  });
  it("a new callback identity does not discard an unsaved draft", async () => {
    const view = panel();
    await loaded();
    edit("0.40");
    view.rerender(
      <UI12MonthsPanel
        assignmentId={17}
        onChanged={changed}
        onDirtyChange={() => {}}
      />,
    );
    expect(input()).toHaveValue("0.40");
  });
});
