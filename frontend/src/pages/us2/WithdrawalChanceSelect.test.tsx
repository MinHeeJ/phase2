import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  WithdrawalChanceSelect,
  type WithdrawalChanceRow,
} from "./WithdrawalChanceSelect";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let row: WithdrawalChanceRow;
let fetcher: ReturnType<typeof vi.fn>;
let error: { code: string; status: number; message: string } | null;
let codesFail: boolean;
const changed = vi.fn<() => Promise<unknown>>();
const today = "2026-10-14";
const version = "2026-10-13T12:00:00.123456";
beforeEach(() => {
  row = {
    asgn_id: 37,
    updated_at: version,
    ext_psblty_cd: "M",
    bgn_dt: "2026-09-01",
    end_dt: "2026-12-31",
    asgn_stts_cd: "ACTIVE",
    chance_locked: false,
    chance_lock_reason: null,
  };
  error = null;
  codesFail = false;
  changed.mockReset().mockResolvedValue(undefined);
  fetcher = vi.fn(async (_url: string, init?: RequestInit) => {
    if (init?.method === "POST") {
      if (error)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            ...error,
            field: "updated_at",
            requestId: "chance-test",
          }),
          { status: error.status },
        );
      const body = JSON.parse(init.body as string);
      return response({
        assignment: {
          ...row,
          ext_psblty_cd: body.ext_psblty_cd,
          updated_at: "2026-10-14T12:00:00.654321",
        },
        change: { asgn_chg_ty_cd: "EXT_CHANCE" },
      });
    }
    if (codesFail) throw new Error("offline");
    return response([
      { code: "H", name: "높음" },
      { code: "M", name: "보통" },
      { code: "L", name: "낮음" },
    ]);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const writes = () =>
  fetcher.mock.calls.filter((call) => call[1]?.method === "POST");
const select = () => screen.getByRole("combobox", { name: "연장 가능성" });
const change = (value: string) =>
  fireEvent.change(select(), { target: { value } });
async function mount(year = 2026) {
  const result = render(
    <WithdrawalChanceSelect
      row={row}
      today={today}
      year={year}
      onChanged={changed}
    />,
  );
  await screen.findByRole("option", { name: "높음" });
  return result;
}

describe("withdrawal chance inline command", () => {
  it("shows the current DB value and code names", async () => {
    await mount();
    expect(select()).toHaveValue("M");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/codes/EXT_POSSIBILITY",
      expect.anything(),
    );
  });
  it("shows NULL as 미정 and sends exact branch-only wire with all timestamp precision", async () => {
    row.ext_psblty_cd = null;
    await mount();
    expect(select()).toHaveValue("");
    change("H");
    await screen.findByText("연장 가능성을 '높음'(으)로 바꿨습니다.");
    expect(writes()).toHaveLength(1);
    expect(writes()[0][0]).toBe("/api/assignments/37/changes");
    expect(JSON.parse(writes()[0][1]!.body as string)).toEqual({
      asgn_chg_ty_cd: "EXT_CHANCE",
      aply_dt: today,
      ext_psblty_cd: "H",
      updated_at: version,
    });
    expect(select()).toHaveValue("H");
    expect(changed).toHaveBeenCalledOnce();
  });
  it("skips identical values without refreshing or generating history", async () => {
    await mount();
    change("M");
    expect(writes()).toHaveLength(0);
    expect(changed).not.toHaveBeenCalled();
  });
  it("clears a chance explicitly to NULL", async () => {
    await mount();
    change("");
    await screen.findByText("연장 가능성을 '미정'(으)로 바꿨습니다.");
    expect(JSON.parse(writes()[0][1]!.body as string).ext_psblty_cd).toBeNull();
    expect(select()).toHaveValue("");
  });
  it("skips an identical NULL", async () => {
    row.ext_psblty_cd = null;
    await mount();
    change("");
    expect(writes()).toHaveLength(0);
  });
  it("refreshes the whole list and its summary after M to H", async () => {
    let count = 1;
    changed.mockImplementation(async () => {
      count -= 1;
    });
    await mount();
    change("H");
    await waitFor(() => expect(changed).toHaveBeenCalledOnce());
    expect(count).toBe(0);
  });
  it("disables while saving and never duplicates a pending POST", async () => {
    const fallback = fetcher.getMockImplementation()!;
    let resolve!: (value: Response) => void;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      init?.method === "POST"
        ? new Promise<Response>((r) => {
            resolve = r;
          })
        : fallback(url, init),
    );
    await mount();
    change("H");
    expect(select()).toBeDisabled();
    expect(select()).toHaveValue("H");
    change("L");
    expect(writes()).toHaveLength(1);
    resolve(
      response({
        assignment: {
          ...row,
          ext_psblty_cd: "H",
          updated_at: "2026-10-14T12:00:00.654321",
        },
      }),
    );
    await waitFor(() => expect(select()).toBeEnabled());
    expect(writes()).toHaveLength(1);
  });
  it("uses the committed version for a later edit even before parent rows update", async () => {
    await mount();
    change("H");
    await waitFor(() => expect(select()).toBeEnabled());
    change("L");
    await screen.findByText("연장 가능성을 '낮음'(으)로 바꿨습니다.");
    expect(JSON.parse(writes()[1][1]!.body as string).updated_at).toBe(
      "2026-10-14T12:00:00.654321",
    );
  });
  it.each([
    [400, "INVALID_INPUT", "입력값을 확인하세요."],
    [409, "PROJECT_CLOSED", "입력할 수 없는 프로젝트입니다."],
    [409, "ASSIGNMENT_LOCKED", "변경할 수 없는 투입 상태입니다."],
    [500, "SERVER_ERROR", "서버 오류가 발생했습니다."],
  ])(
    "rolls back %s %s and preserves its actual reason without a stale refetch",
    async (status, code, message) => {
      error = { status, code, message };
      await mount();
      change("H");
      await screen.findByText(message);
      await waitFor(() => expect(select()).toHaveValue("M"));
      expect(changed).not.toHaveBeenCalled();
      expect(writes()).toHaveLength(1);
    },
  );
  it("rolls back a network failure without retrying the command", async () => {
    const fallback = fetcher.getMockImplementation()!;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      init?.method === "POST"
        ? Promise.reject(new Error("offline"))
        : fallback(url, init),
    );
    await mount();
    change("H");
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(select()).toHaveValue("M");
    expect(writes()).toHaveLength(1);
  });
  it("handles two-window stale with exact toast, rollback and one full-list refetch", async () => {
    error = {
      status: 409,
      code: "STALE_ASSIGNMENT",
      message: "다른 사람이 먼저 바꿨습니다. 창을 다시 열어 주세요.",
    };
    await mount();
    change("L");
    await screen.findByText(
      "다른 사람이 먼저 바꿨습니다. 목록을 새로 고칩니다.",
    );
    await waitFor(() => expect(select()).toHaveValue("M"));
    expect(changed).toHaveBeenCalledOnce();
    expect(writes()).toHaveLength(1);
  });
  it("adopts refreshed row data after a stale response", async () => {
    error = { status: 409, code: "STALE_ASSIGNMENT", message: "stale" };
    const view = await mount();
    change("L");
    await waitFor(() => expect(changed).toHaveBeenCalledOnce());
    row = {
      ...row,
      ext_psblty_cd: "H",
      updated_at: "2026-10-14T12:00:00.654321",
    };
    view.rerender(
      <WithdrawalChanceSelect
        row={row}
        today={today}
        year={2026}
        onChanged={changed}
      />,
    );
    expect(select()).toHaveValue("H");
  });
  it("does not roll back a committed value when list refresh fails; retry is read-only", async () => {
    changed.mockRejectedValueOnce(new Error("offline"));
    await mount();
    change("H");
    await screen.findByText("목록을 다시 조회하지 못했습니다.");
    expect(select()).toHaveValue("H");
    changed.mockResolvedValue(undefined);
    fireEvent.click(screen.getByRole("button", { name: "목록 새로고침" }));
    await waitFor(() =>
      expect(
        screen.queryByText("목록을 다시 조회하지 못했습니다."),
      ).not.toBeInTheDocument(),
    );
    expect(writes()).toHaveLength(1);
    expect(changed).toHaveBeenCalledTimes(2);
  });
  it("retains rollback and lock when stale refetch fails", async () => {
    error = { status: 409, code: "STALE_ASSIGNMENT", message: "stale" };
    changed.mockRejectedValue(new Error("offline"));
    await mount();
    change("H");
    await screen.findByText("목록을 다시 조회하지 못했습니다.");
    expect(select()).toHaveValue("M");
    expect(select()).toBeDisabled();
    expect(writes()).toHaveLength(1);
  });
  it.each([
    [2025, {}, "지난 해 · 읽기 전용"],
    [2026, { end_dt: "2026-10-13" }, "이미 철수한 투입입니다."],
    [2026, { asgn_stts_cd: "REPLACED" }, "교체된 투입입니다."],
    [2026, { asgn_stts_cd: "CANCELLED" }, "변경할 수 없는 투입 상태입니다."],
    [
      2026,
      { bgn_dt: "2026-11-01" },
      "투입일(2026-11-01) 전에는 변경할 수 없습니다.",
    ],
    [
      2026,
      { chance_locked: true, chance_lock_reason: "종료 프로젝트입니다." },
      "종료 프로젝트입니다.",
    ],
    [
      2026,
      { chance_locked: true, chance_lock_reason: "실패 프로젝트입니다." },
      "실패 프로젝트입니다.",
    ],
    [
      2026,
      { chance_locked: true, chance_lock_reason: "사용 안 함 프로젝트입니다." },
      "사용 안 함 프로젝트입니다.",
    ],
  ])(
    "locks %s %j visibly without sending a command",
    async (year, fields, reason) => {
      row = { ...row, ...fields };
      await mount(year);
      expect(select()).toBeDisabled();
      expect(select()).toHaveAttribute("title", reason);
      expect(screen.getByText(reason)).toBeVisible();
      change("H");
      expect(writes()).toHaveLength(0);
    },
  );
  it("allows a start/end date equal to today", async () => {
    row.bgn_dt = today;
    row.end_dt = today;
    await mount();
    expect(select()).toBeEnabled();
  });
  it("stops table-row navigation on select and retry interaction", async () => {
    const open = vi.fn();
    render(
      <div onClick={open}>
        <WithdrawalChanceSelect
          row={row}
          today={today}
          year={2026}
          onChanged={changed}
        />
      </div>,
    );
    await screen.findByRole("option", { name: "높음" });
    fireEvent.click(select());
    expect(open).not.toHaveBeenCalled();
  });
  it("shows code-load failure, disables mutation and retries the API", async () => {
    codesFail = true;
    render(
      <WithdrawalChanceSelect
        row={row}
        today={today}
        year={2026}
        onChanged={changed}
      />,
    );
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(select()).toBeDisabled();
    codesFail = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByRole("option", { name: "높음" });
    expect(select()).toBeEnabled();
    expect(writes()).toHaveLength(0);
  });
});
