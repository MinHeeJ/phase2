import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  WithdrawalChanceSelect,
  type WithdrawalChanceRow,
} from "./WithdrawalChanceSelect";

const options = [
  { code: "H", name: "높음" },
  { code: "M", name: "보통" },
  { code: "L", name: "낮음" },
];
const today = "2026-10-14";
let row: WithdrawalChanceRow;
let fetcher: ReturnType<typeof vi.fn>;
let reload: ReturnType<typeof vi.fn>;
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failure = (status: number, code: string) =>
  new Response(
    JSON.stringify({
      success: false,
      code,
      message: "저장 실패",
      requestId: "test",
    }),
    { status, headers: { "Content-Type": "application/json" } },
  );
function show(year = 2026) {
  return render(
    <WithdrawalChanceSelect
      row={row}
      year={year}
      options={options}
      reload={reload}
      today={today}
    />,
  );
}
beforeEach(() => {
  row = {
    asgn_id: 7,
    bgn_dt: "2026-09-01",
    end_dt: "2026-12-31",
    asgn_stts_cd: "ACTIVE",
    prj_stts_cd: "RUN",
    use_yn: "Y",
    ext_psblty_cd: null,
    updated_at: "2026-10-13T12:00:00.123456",
  };
  reload = vi.fn().mockResolvedValue(undefined);
  fetcher = vi.fn().mockResolvedValue(response({ assignment: {}, change: {} }));
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.unstubAllGlobals());

describe("철수 목록 연장 가능성", () => {
  it("미정에서 높음으로 바꾸면 기존 변경 API에 버전과 오늘을 보내고 목록과 안내를 재조회한다", async () => {
    show();
    expect(screen.getByRole("combobox")).toHaveValue("");
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "H" } });
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0][0]).toBe("/api/assignments/7/changes");
    const request = fetcher.mock.calls[0][1] as RequestInit;
    expect(request.method).toBe("POST");
    expect(JSON.parse(request.body as string)).toEqual({
      updated_at: row.updated_at,
      asgn_chg_ty_cd: "EXT_CHANCE",
      aply_dt: today,
      ext_psblty_cd: "H",
    });
    expect(screen.getByRole("status")).toHaveTextContent(
      "연장 가능성을 '높음'(으)로 바꿨습니다.",
    );
  });
  it("같은 값을 선택하면 요청과 재조회가 없다", () => {
    row.ext_psblty_cd = "M";
    show();
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "M" } });
    expect(fetcher).not.toHaveBeenCalled();
    expect(reload).not.toHaveBeenCalled();
  });
  it("미정은 null로 전송한다", async () => {
    row.ext_psblty_cd = "M";
    show();
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "" } });
    await waitFor(() => expect(reload).toHaveBeenCalled());
    expect(JSON.parse(fetcher.mock.calls[0][1].body).ext_psblty_cd).toBeNull();
  });
  it.each([
    ["지난 해", {}, 2025, "지난 해"],
    ["이미 철수", { end_dt: "2026-10-13" }, 2026, "이미 철수"],
    ["교체", { asgn_stts_cd: "REPLACED" }, 2026, "교체"],
    ["취소", { asgn_stts_cd: "CANCELLED" }, 2026, "취소"],
    ["종료 프로젝트", { prj_stts_cd: "END" }, 2026, "프로젝트"],
    ["실주 프로젝트", { prj_stts_cd: "LOST" }, 2026, "프로젝트"],
    ["미사용 프로젝트", { use_yn: "N" }, 2026, "프로젝트"],
    ["미래 투입", { bgn_dt: "2026-10-15" }, 2026, "투입일 전"],
  ])("%s은 잠금 이유를 표시한다", (_name, patch, year, reason) => {
    row = { ...row, ...patch };
    show(year);
    expect(screen.getByRole("combobox")).toBeDisabled();
    expect(screen.getByRole("combobox")).toHaveAttribute(
      "title",
      expect.stringContaining(reason),
    );
  });
  it("D-0과 투입 당일은 변경 가능하다", () => {
    row.bgn_dt = today;
    row.end_dt = today;
    show();
    expect(screen.getByRole("combobox")).not.toBeDisabled();
  });
  it("다른 창의 변경으로 409가 발생하면 값을 되돌리고 목록을 재조회한다", async () => {
    row.ext_psblty_cd = "M";
    fetcher.mockResolvedValue(failure(409, "STALE_ASSIGNMENT"));
    show();
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "H" } });
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("combobox")).toHaveValue("M");
    expect(screen.getByRole("alert")).toHaveTextContent(
      "다른 사람이 먼저 바꿨습니다. 목록을 새로 고칩니다.",
    );
  });
  it("다른 오류도 선택을 원복하고 오류를 표시한다", async () => {
    row.ext_psblty_cd = "M";
    fetcher.mockResolvedValue(failure(400, "INVALID_INPUT"));
    show();
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "H" } });
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("저장 실패"),
    );
    expect(screen.getByRole("combobox")).toHaveValue("M");
    expect(reload).not.toHaveBeenCalled();
  });
  it("충돌 후 재조회 실패 시 재시도할 때까지 편집을 잠근다", async () => {
    fetcher.mockResolvedValue(failure(409, "STALE_ASSIGNMENT"));
    reload.mockRejectedValueOnce(new Error("offline"));
    show();
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "H" } });
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent(
        "목록을 새로 고치지 못했습니다",
      ),
    );
    expect(screen.getByRole("combobox")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() =>
      expect(screen.getByRole("combobox")).not.toBeDisabled(),
    );
  });
});
