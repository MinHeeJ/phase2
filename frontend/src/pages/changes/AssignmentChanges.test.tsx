import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { UI12ChangesPanel } from "./UI12ChangesPanel";
import {
  AssignmentChangeForm,
  AssignmentFixForm,
} from "./AssignmentChangeForms";
import type { AssignmentChange, ChangeAssignment } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const original: AssignmentChange = {
  asgn_chg_id: 7,
  asgn_id: 3,
  asgn_chg_ty_cd: "EXTEND",
  aply_dt: "2027-01-01",
  bfr_val: "2026-12-31",
  aft_val: "2027-03-31",
  new_asgn_id: null,
  rsn_cd: "CUST",
  rsn_nm: "고객 요청",
  rsn_desc: "일정 변경",
  retro_yn: "N",
  fixed_yn: "N",
  fix_trgt_chg_id: null,
  fix_trgt_aply_dt: null,
  fix_trgt_chg_ty_cd: null,
  chg_user_id: null,
  chg_user_nm: null,
  chg_at: "2026-10-14T12:00:00.123456",
};
let assignment: ChangeAssignment,
  rows: AssignmentChange[],
  fetcher: ReturnType<typeof vi.fn>;
let failRead: boolean, writeError: string | null;
const changed = vi.fn().mockResolvedValue(undefined),
  saved = vi.fn(),
  cancel = vi.fn();
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  assignment = {
    asgn_id: 3,
    updated_at: "2026-10-14T12:00:00.123456",
    bgn_dt: "2026-09-01",
    end_dt: "2026-12-31",
    asgn_se_cd: "NEW",
    ext_psblty_cd: "M",
    cntrct_dev_id: null,
    rplc_asgn_id: null,
    asgn_stts_cd: "ACTIVE",
    rates: [{ aply_bgn_dt: "2026-09-01", bill_untprc_amt: 7000000 }],
  };
  rows = [
    { ...original },
    { ...original, asgn_chg_id: 8, asgn_chg_ty_cd: "START" },
  ];
  failRead = false;
  writeError = null;
  changed.mockClear();
  saved.mockClear();
  cancel.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/codes/ASGN_CHG_RSN")
      return response([{ code: "CUST", name: "고객 요청" }]);
    if (url === "/api/codes/ASSIGN_KIND")
      return response([
        { code: "NEW", name: "신규" },
        { code: "ADD", name: "추가투입" },
        { code: "SUB", name: "대체투입" },
      ]);
    if (url === "/api/codes/EXT_POSSIBILITY")
      return response([
        { code: "H", name: "높음" },
        { code: "M", name: "보통" },
        { code: "L", name: "낮음" },
      ]);
    if (url.startsWith("/api/month-close"))
      return response([{ month: "2026-09", status: "CLOSED" }]);
    if (url.startsWith("/api/developers/options"))
      return response([
        { id: 11, name: "계약 인력", mngNo: "K-0011", mainJob: "백엔드 개발" },
      ]);
    if (init?.method === "POST") {
      if (writeError)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: writeError,
            field: writeError === "STALE_ASSIGNMENT" ? "updated_at" : "rsn_cd",
            message:
              writeError === "STALE_ASSIGNMENT"
                ? "다른 사람이 먼저 바꿨습니다. 창을 다시 열어 주세요."
                : "사유를 골라 주세요.",
            requestId: "changes-request",
          }),
          { status: writeError === "STALE_ASSIGNMENT" ? 409 : 400 },
        );
      const body = JSON.parse(init.body as string);
      if (url.endsWith("/fix")) {
        rows = rows.map((r) =>
          r.asgn_chg_id === 7 ? { ...r, fixed_yn: "Y" } : r,
        );
        const correction: AssignmentChange = {
          ...original,
          asgn_chg_id: 9,
          asgn_chg_ty_cd: "FIX",
          aft_val: body.aft_val,
          fix_trgt_chg_id: 7,
          fix_trgt_aply_dt: original.aply_dt,
          fix_trgt_chg_ty_cd: "EXTEND",
        };
        rows.push(correction);
        return response({ assignment, fix: correction, target: rows[0] });
      }
      return response({
        assignment: { ...assignment, end_dt: body.end_dt ?? assignment.end_dt },
        change: { ...original, asgn_chg_ty_cd: body.asgn_chg_ty_cd },
      });
    }
    if (url.startsWith("/api/assignments/3/changes")) {
      if (failRead) throw new Error("offline");
      const type = new URL(url, "http://test.invalid").searchParams.get("type");
      const items = rows.filter((r) => !type || type === r.asgn_chg_ty_cd);
      return response({ items, total: items.length });
    }
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const writes = () => fetcher.mock.calls.filter((c) => c[1]?.method === "POST");
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const panel = (props = {}) =>
  render(
    <UI12ChangesPanel
      assignment={assignment}
      contractType="MO"
      onChanged={changed}
      {...props}
    />,
  );
const form = (props = {}) =>
  render(
    <AssignmentChangeForm
      assignment={assignment}
      contractType="MO"
      today="2026-10-14"
      reload={changed}
      onCancel={cancel}
      onSaved={saved}
      {...props}
    />,
  );
const fix = (props = {}) =>
  render(
    <AssignmentFixForm
      assignment={assignment}
      contractType="MO"
      target={original}
      reload={changed}
      onCancel={cancel}
      onSaved={saved}
      {...props}
    />,
  );
async function readyForm() {
  await screen.findByRole("option", { name: "고객 요청" });
  await waitFor(() =>
    expect(screen.getByTestId("changes-form-save")).toBeEnabled(),
  );
}

describe("assignment history", () => {
  it("reads anonymously and refetches type chips with accurate empty state", async () => {
    panel();
    await screen.findByTestId("changes-row-7");
    expect(screen.getAllByText("고객 요청 · 일정 변경")).toHaveLength(2);
    expect(
      within(screen.getByTestId("changes-row-8")).queryByRole("button", {
        name: "정정",
      }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("changes-filter-extend"));
    await waitFor(() =>
      expect(screen.queryByTestId("changes-row-8")).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId("changes-filter-rate"));
    await screen.findByText("이 종류의 기록이 없습니다.");
    expect(
      fetcher.mock.calls.some(
        (c) => c[0] === "/api/assignments/3/changes?type=RATE",
      ),
    ).toBe(true);
    expect(writes()).toHaveLength(0);
  });
  it("shows fixed dimming, retro badge and correction target while hiding prohibited actions", async () => {
    rows = [
      { ...original, fixed_yn: "Y", retro_yn: "Y" },
      {
        ...original,
        asgn_chg_id: 9,
        asgn_chg_ty_cd: "FIX",
        fix_trgt_aply_dt: "2027-01-01",
        fix_trgt_chg_ty_cd: "EXTEND",
      },
    ];
    panel();
    const row = await screen.findByTestId("changes-row-7");
    expect(row.className).toContain("fixed");
    expect(screen.getByText("정정됨")).toBeInTheDocument();
    expect(screen.getByText("소급 입력")).toBeInTheDocument();
    expect(
      screen.getByText("정정 대상: 2027-01-01 · 연장"),
    ).toBeInTheDocument();
    expect(
      within(row).queryByRole("button", { name: "정정" }),
    ).not.toBeInTheDocument();
    expect(
      within(screen.getByTestId("changes-row-9")).queryByRole("button", {
        name: "정정",
      }),
    ).not.toBeInTheDocument();
  });
  it.each(["CANCELLED", "ENDED"])(
    "keeps %s history readable but hides correction",
    async (status) => {
      assignment.asgn_stts_cd = status;
      panel();
      await screen.findByTestId("changes-row-7");
      expect(screen.queryByTestId("changes-correct-7")).not.toBeInTheDocument();
    },
  );
  it("permits an eligible correction for a replaced original", async () => {
    assignment.asgn_stts_cd = "REPLACED";
    panel();
    expect(await screen.findByTestId("changes-correct-7")).toBeEnabled();
  });
  it.each([{ readOnly: true }, { projectWritable: false }])(
    "hides controls for old-year/project readonly context %j",
    async (props) => {
      panel(props);
      await screen.findByTestId("changes-row-7");
      expect(screen.queryByTestId("changes-correct-7")).not.toBeInTheDocument();
    },
  );
  it("shows failure and supports a successful retry", async () => {
    failRead = true;
    panel();
    await screen.findByText("서버에 연결할 수 없습니다.");
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: /다시/ }));
    await screen.findByTestId("changes-row-7");
  });
  it("shows loading until read finishes", async () => {
    const fallback = fetcher.getMockImplementation()!;
    let resolve!: (r: Response) => void;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      url === "/api/assignments/3/changes"
        ? new Promise<Response>((r) => {
            resolve = r;
          })
        : fallback(url, init),
    );
    panel();
    expect(screen.queryByTestId("changes-row-7")).not.toBeInTheDocument();
    resolve(response({ items: rows, total: rows.length }));
    await screen.findByTestId("changes-row-7");
  });
  it("correction refreshes same history and current assignment without extra confirmation", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("changes-correct-7"));
    await screen.findByRole("option", { name: "고객 요청" });
    change("바른 내용", "바른 종료일 설명");
    change("사유", "CUST");
    fireEvent.click(screen.getByTestId("changes-correction-save"));
    await screen.findByText("정정 기록을 남겼습니다.");
    expect(changed).toHaveBeenCalledOnce();
    expect(await screen.findByText("정정됨")).toBeInTheDocument();
    expect(writes()).toHaveLength(1);
    expect(JSON.parse(writes()[0][1]!.body as string)).toEqual({
      updated_at: assignment.updated_at,
      aft_val: "바른 종료일 설명",
      rsn_cd: "CUST",
    });
  });
});
describe("change record", () => {
  it("fixes extension application day and submits branch-only payload with full precision", async () => {
    form();
    await readyForm();
    expect(screen.getByLabelText("연장 시작일")).toHaveValue("2027-01-01");
    expect(screen.getByLabelText("연장 시작일")).toHaveAttribute("readonly");
    change("새 종료일", "2027-03-31");
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await waitFor(() => expect(saved).toHaveBeenCalledOnce());
    expect(changed).toHaveBeenCalledOnce();
    expect(JSON.parse(writes()[0][1]!.body as string)).toEqual({
      updated_at: assignment.updated_at,
      asgn_chg_ty_cd: "EXTEND",
      aply_dt: "2027-01-01",
      end_dt: "2027-03-31",
      rsn_cd: null,
      rsn_desc: null,
    });
  });
  it("rejects equal extension locally without sending", async () => {
    form();
    await readyForm();
    change("새 종료일", assignment.end_dt);
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await screen.findByText(
      "새 종료일은 현재 종료일(2026-12-31)보다 늦어야 합니다.",
    );
    expect(writes()).toHaveLength(0);
  });
  it("early departure requires reason and sends no unrelated values", async () => {
    form();
    await readyForm();
    change("변경 종류", "EARLY_END");
    change("철수일", "2026-11-15");
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await screen.findByText("사유를 골라 주세요.");
    expect(writes()).toHaveLength(0);
    change("사유", "CUST");
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(JSON.parse(writes()[0][1]!.body as string)).not.toHaveProperty(
      "end_dt",
    );
  });
  it("hides RATE for turnkey pricing", async () => {
    form({ contractType: "TK" });
    await readyForm();
    expect(
      screen.queryByRole("option", { name: "단가 변경" }),
    ).not.toBeInTheDocument();
  });
  it("replaced assignment exposes only RATE", async () => {
    assignment.asgn_stts_cd = "REPLACED";
    form();
    await readyForm();
    const options = within(screen.getByLabelText("변경 종류"))
      .getAllByRole("option")
      .map((e) => (e as HTMLOptionElement).value)
      .filter(Boolean);
    expect(options).toEqual(["RATE"]);
  });
  it("RATE derives first assignment day in start month and checks same price", async () => {
    assignment.bgn_dt = "2026-10-15";
    assignment.rates = [
      { aply_bgn_dt: "2026-10-15", bill_untprc_amt: 7000000 },
    ];
    form();
    await readyForm();
    change("변경 종류", "RATE");
    change("적용 시작월", "2026-10");
    change("새 청구 단가", "7000000");
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await screen.findByText("현재 값과 같습니다.");
    expect(writes()).toHaveLength(0);
    change("새 청구 단가", "7200000");
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(JSON.parse(writes()[0][1]!.body as string).aply_dt).toBe(
      "2026-10-15",
    );
  });
  it("EXT_CHANCE preserves explicit clearing to null", async () => {
    form();
    await readyForm();
    change("변경 종류", "EXT_CHANCE");
    change("연장 가능성", "");
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(JSON.parse(writes()[0][1]!.body as string)).toHaveProperty(
      "ext_psblty_cd",
      null,
    );
  });
  it("PERIOD explains correction intent and blocks unchanged values", async () => {
    form();
    await readyForm();
    change("변경 종류", "PERIOD");
    expect(screen.getByText(/최초 입력 오류/)).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await screen.findByText("바뀐 내용이 없습니다.");
    expect(writes()).toHaveLength(0);
  });
  it("PERIOD end changes need a reason and closed start month cannot move", async () => {
    form();
    await readyForm();
    change("변경 종류", "PERIOD");
    change("종료일", "2027-01-31");
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await screen.findByText("사유를 골라 주세요.");
    change("사유", "CUST");
    change("투입일", "2026-10-01");
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await screen.findByText("마감된 시작월은 바꿀 수 없습니다.");
    expect(writes()).toHaveLength(0);
  });
  it("SUB transition asks for actual contract-person identity", async () => {
    form();
    await readyForm();
    change("변경 종류", "PERIOD");
    change("투입 구분", "SUB");
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await screen.findByText("계약상 인력을 고르세요.");
    await screen.findByRole("option", { name: /계약 인력/ });
    fireEvent.change(screen.getByTestId("developer-option-select"), {
      target: { value: "11" },
    });
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(JSON.parse(writes()[0][1]!.body as string).cntrct_dev_id).toBe(11);
  });
  it("replacement-created SUB identity stays immutable", async () => {
    assignment.rplc_asgn_id = 2;
    assignment.asgn_se_cd = "SUB";
    assignment.cntrct_dev_id = 11;
    form();
    await readyForm();
    change("변경 종류", "PERIOD");
    expect(screen.getByLabelText("투입 구분")).toBeDisabled();
    await screen.findByTestId("developer-option-select");
    expect(screen.getByTestId("developer-option-select")).toBeDisabled();
  });
  it("retroactive date displays warning and requires reason", async () => {
    form();
    await readyForm();
    change("변경 종류", "EXT_CHANCE");
    change("바꾼 날", "2026-10-13");
    change("연장 가능성", "H");
    expect(screen.getByText("소급 입력입니다.")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await screen.findByText("사유를 골라 주세요.");
    expect(writes()).toHaveLength(0);
  });
  it("dirty cancellation requires discard and never transmits", async () => {
    form();
    await readyForm();
    change("새 종료일", "2027-03-31");
    fireEvent.click(screen.getByTestId("changes-form-cancel"));
    expect(cancel).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("changes-keep"));
    expect(screen.getByLabelText("새 종료일")).toHaveValue("2027-03-31");
    await waitFor(() =>
      expect(
        screen.queryByTestId("changes-discard-dialog"),
      ).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId("changes-form-cancel"));
    fireEvent.click(screen.getByTestId("changes-discard"));
    expect(cancel).toHaveBeenCalledOnce();
    expect(writes()).toHaveLength(0);
  });
  it("server field failure preserves draft", async () => {
    writeError = "INVALID_INPUT";
    form();
    await readyForm();
    change("새 종료일", "2027-03-31");
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await screen.findAllByText("사유를 골라 주세요.");
    expect(screen.getByLabelText("새 종료일")).toHaveValue("2027-03-31");
    expect(saved).not.toHaveBeenCalled();
  });
  it("stale response prevents repeated save and instructs reopening", async () => {
    writeError = "STALE_ASSIGNMENT";
    form();
    await readyForm();
    change("새 종료일", "2027-03-31");
    fireEvent.click(screen.getByTestId("changes-form-save"));
    await screen.findByText(
      "다른 사람이 먼저 바꿨습니다. 창을 다시 열어 주세요.",
    );
    expect(screen.getByTestId("changes-form-save")).toBeDisabled();
    expect(saved).not.toHaveBeenCalled();
  });
});
describe("history correction", () => {
  it("validates corrected text and reason before sending", async () => {
    fix();
    await screen.findByRole("option", { name: "고객 요청" });
    fireEvent.click(screen.getByTestId("changes-correction-save"));
    await screen.findByText("바른 내용을 적어 주세요.");
    expect(writes()).toHaveLength(0);
    change("바른 내용", "수정 설명");
    fireEvent.click(screen.getByTestId("changes-correction-save"));
    await screen.findByText("사유를 골라 주세요.");
    expect(writes()).toHaveLength(0);
    expect(
      screen.getByText("지금 값을 바꾸려면 [+ 변경 기록]을 따로 넣으세요."),
    ).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
  it("clean cancellation sends nothing", async () => {
    fix();
    fireEvent.click(screen.getByTestId("changes-correction-cancel"));
    expect(cancel).toHaveBeenCalledOnce();
    expect(writes()).toHaveLength(0);
  });
  it("dirty correction cancellation retains draft until discard", async () => {
    fix();
    change("바른 내용", "수정 설명");
    fireEvent.click(screen.getByTestId("changes-correction-cancel"));
    fireEvent.click(screen.getByTestId("changes-keep"));
    expect(screen.getByLabelText("바른 내용")).toHaveValue("수정 설명");
    expect(writes()).toHaveLength(0);
  });
});
