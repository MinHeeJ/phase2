import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { ProjectContractChangePanel } from "./ProjectContractChangePanel";
import type { ContractChangeProject, ContractChangeRow } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const original: ContractChangeRow = {
  chgId: 7,
  cntrct_chg_ty_cd: "ADD",
  cntrct_chg_stts_cd: "PLAN",
  chg_dt: "2026-10-01",
  bfr_cntrct_amt: 500000000,
  aft_cntrct_amt: 550000000,
  bfr_end_dt: null,
  aft_end_dt: null,
  incdec_mm_qty: 1.25,
  free_mm_qty: null,
  rsn_desc: "범위 확대",
  realoc_need_yn: "N",
  cnfrm_at: null,
  cnfrm_user_id: null,
};
let rows: ContractChangeRow[];
let fetcher: ReturnType<typeof vi.fn>;
let failRead: boolean;
let failWrite: boolean;
let project: ContractChangeProject;
const changed = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  rows = [
    { ...original },
    {
      ...original,
      chgId: 8,
      cntrct_chg_stts_cd: "CONFIRM",
      cnfrm_at: "2026-10-02T12:00:00",
    },
  ];
  project = {
    prj_id: 3,
    cntrct_ty_cd: "TK",
    cur_end_dt: "2026-12-31",
    use_yn: "Y",
  };
  failRead = false;
  failWrite = false;
  changed.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/codes/CONTRACT_CHANGE")
      return response([
        { code: "ADD", name: "추가 계약" },
        { code: "CUT", name: "감액" },
        { code: "TERM", name: "기간 변경" },
        { code: "FREE", name: "무상지원 합의" },
      ]);
    if (init?.method && init.method !== "GET") {
      if (failWrite)
        return new Response(
          JSON.stringify({
            success: false,
            code: "INVALID_INPUT",
            message: "사유를 확인하세요.",
            field: "rsn_desc",
            requestId: "contract-request",
          }),
          { status: 400 },
        );
      const id = Number(url.split("/").at(url.endsWith("confirm") ? -2 : -1));
      if (init.method === "DELETE") {
        rows = rows.filter((r) => r.chgId !== id);
        return response({ prj_id: 3, chgId: id });
      }
      if (url.endsWith("confirm")) {
        rows = rows.map((r) =>
          r.chgId === id
            ? {
                ...r,
                cntrct_chg_stts_cd: "CONFIRM",
                realoc_need_yn: project.cntrct_ty_cd === "TK" ? "Y" : "N",
              }
            : r,
        );
        return response(rows.find((r) => r.chgId === id));
      }
      const input = JSON.parse(init.body as string);
      const row = {
        ...original,
        ...input,
        chgId: init.method === "PUT" ? id : 9,
        realoc_need_yn:
          input.cntrct_chg_stts_cd === "CONFIRM" &&
          project.cntrct_ty_cd === "TK" &&
          input.cntrct_chg_ty_cd !== "FREE"
            ? "Y"
            : "N",
      };
      if (init.method === "PUT")
        rows = rows.map((r) => (r.chgId === id ? row : r));
      else rows.push(row);
      return response(row);
    }
    if (url === "/api/projects/3/contract-changes") {
      if (failRead) throw new Error("offline");
      return response({
        cntrct_bgn_dt: "2026-01-01",
        init_cntrct_amt: 500000000,
        items: rows,
      });
    }
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = () =>
  render(<ProjectContractChangePanel project={project} onChanged={changed} />);
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const writes = () =>
  fetcher.mock.calls.filter((c) => c[1]?.method && c[1].method !== "GET");
async function add(type = "ADD") {
  fireEvent.click(await screen.findByTestId("contract-change-add"));
  await screen.findByRole("option", { name: "기간 변경" });
  change("종류", type);
}
async function fill(type = "ADD") {
  await add(type);
  change("변경일", "2026-10-23");
  change("사유", "새 계약 변경");
  if (type === "ADD" || type === "CUT") change("금액", "50000000");
  if (type === "TERM") change("변경 후 종료일", "2027-03-31");
  if (type === "FREE") change("무상지원 MM", "2.50");
}
describe("project contract changes", () => {
  it("renders initial contract first, exact columns and PLAN-only actions", async () => {
    panel();
    await screen.findByTestId("contract-change-row-7");
    expect(screen.getAllByRole("row")[1]).toHaveAttribute(
      "data-testid",
      "contract-change-initial",
    );
    for (const name of ["변경일", "내용", "변경 후 금액", "사유", "상태"])
      expect(screen.getByRole("columnheader", { name })).toBeVisible();
    expect(
      within(screen.getByTestId("contract-change-row-7")).getByText("예정")
        .className,
    ).toContain("warning");
    expect(
      within(screen.getByTestId("contract-change-row-8")).getByText("확정")
        .className,
    ).toContain("success");
    expect(
      screen.queryByTestId("contract-change-edit-8"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("contract-change-delete-8"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("contract-change-confirm-8"),
    ).not.toBeInTheDocument();
    expect(screen.getByText("기본 사용자")).toBeVisible();
  });
  it("renders empty history with real initial contract", async () => {
    rows = [];
    panel();
    expect(
      await screen.findByText("등록된 계약 변경이 없습니다."),
    ).toBeVisible();
    expect(screen.getByTestId("contract-change-initial")).toHaveTextContent(
      "500,000,000원",
    );
  });
  it("renders loading and failed request with retry", async () => {
    failRead = true;
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    await screen.findByText("서버에 연결할 수 없습니다.");
    failRead = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("contract-change-row-7")).toBeVisible();
  });
  it.each(["ADD", "CUT", "TERM", "FREE"])(
    "saves conditional %s payload and refreshes",
    async (type) => {
      panel();
      await fill(type);
      expect(screen.getByLabelText("상태")).toHaveValue("PLAN");
      fireEvent.click(screen.getByTestId("contract-change-save"));
      await screen.findByText("저장했습니다.");
      const body = JSON.parse(writes()[0][1].body as string);
      expect(body.cntrct_chg_ty_cd).toBe(type);
      expect(body).not.toHaveProperty("chgId");
      if (type === "TERM") {
        expect(body.aft_end_dt).toBe("2027-03-31");
        expect(body).not.toHaveProperty("amount");
      }
      if (type === "FREE") {
        expect(body.free_mm_qty).toBe(2.5);
        expect(body).not.toHaveProperty("incdec_mm_qty");
      }
      expect(changed).toHaveBeenCalledOnce();
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(
        screen.queryByText(
          "현재 계약금액을 바꿨습니다. 배분표를 다시 맞춰 주세요.",
        ),
      ).not.toBeInTheDocument();
    },
  );
  it.each(["종류", "변경일", "금액", "사유"])(
    "requires %s without sending a write",
    async (label) => {
      panel();
      await fill();
      change(label, "");
      fireEvent.click(screen.getByTestId("contract-change-save"));
      await screen.findByText("필수 항목과 입력 범위를 확인하세요.");
      expect(screen.getByLabelText(label)).toHaveFocus();
      expect(writes()).toHaveLength(0);
    },
  );
  it("TERM rejects current end and missing end", async () => {
    panel();
    await fill("TERM");
    change("변경 후 종료일", project.cur_end_dt);
    fireEvent.click(screen.getByTestId("contract-change-save"));
    await screen.findByText("현재 종료일과 다른 종료일을 입력하세요.");
    expect(writes()).toHaveLength(0);
    change("변경 후 종료일", "");
    fireEvent.click(screen.getByTestId("contract-change-save"));
    expect(writes()).toHaveLength(0);
  });
  it("FREE requires MM and hides monetary fields", async () => {
    panel();
    await add("FREE");
    change("변경일", "2026-10-23");
    change("사유", "지원");
    expect(screen.queryByLabelText("금액")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("contract-change-save"));
    await screen.findByText("필수 항목과 입력 범위를 확인하세요.");
    expect(screen.getByLabelText("무상지원 MM")).toHaveFocus();
    expect(writes()).toHaveLength(0);
  });
  it("preserves signed MM precision rules", async () => {
    panel();
    await fill();
    change("증감 MM", "1.001");
    fireEvent.click(screen.getByTestId("contract-change-save"));
    await screen.findByText("필수 항목과 입력 범위를 확인하세요.");
    expect(writes()).toHaveLength(0);
    change("증감 MM", "-1.25");
    fireEvent.click(screen.getByTestId("contract-change-save"));
    await screen.findByText("저장했습니다.");
    expect(JSON.parse(writes()[0][1].body as string).incdec_mm_qty).toBe(-1.25);
  });
  it("MO has no MM input and IN only offers period change", async () => {
    project.cntrct_ty_cd = "IN";
    panel();
    await add("TERM");
    expect(
      screen.queryByRole("option", { name: "추가 계약" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByLabelText("금액")).not.toBeInTheDocument();
  });
  it("non-turnkey monetary change omits non-applicable MM", async () => {
    project.cntrct_ty_cd = "MO";
    panel();
    await fill();
    expect(screen.queryByLabelText("증감 MM")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("contract-change-save"));
    await screen.findByText("저장했습니다.");
    expect(JSON.parse(writes()[0][1].body as string).incdec_mm_qty).toBeNull();
  });
  it("edit reconstructs proposed delta and PUTs selected row", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("contract-change-edit-7"));
    await screen.findByRole("option", { name: "추가 계약" });
    expect(screen.getByLabelText("금액")).toHaveValue("50,000,000");
    change("사유", "수정 사유");
    fireEvent.click(screen.getByTestId("contract-change-save"));
    await screen.findByText("저장했습니다.");
    expect(writes()[0][0]).toBe("/api/projects/3/contract-changes/7");
    expect(writes()[0][1].method).toBe("PUT");
    expect(await screen.findByText("수정 사유")).toBeVisible();
  });
  it("confirm cancel leaves plan unchanged and confirmation has irreversible wording", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("contract-change-confirm-7"));
    expect(
      screen.getByText(
        "현재 계약금액·종료일에 바로 반영되고 되돌릴 수 없습니다.",
      ),
    ).toBeVisible();
    fireEvent.click(screen.getByTestId("contract-change-action-cancel"));
    expect(writes()).toHaveLength(0);
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId("contract-change-confirm-7"));
    fireEvent.click(screen.getByTestId("contract-change-action-submit"));
    await screen.findByText(
      "현재 계약금액을 바꿨습니다. 배분표를 다시 맞춰 주세요.",
    );
    expect(writes()[0][0]).toBe("/api/projects/3/contract-changes/7/confirm");
    expect(
      screen.queryByTestId("contract-change-edit-7"),
    ).not.toBeInTheDocument();
    expect(changed).toHaveBeenCalledOnce();
  });
  it("confirmed form save shows TK reallocation message", async () => {
    panel();
    await fill();
    change("상태", "CONFIRM");
    fireEvent.click(screen.getByTestId("contract-change-save"));
    expect(
      await screen.findByText(
        "현재 계약금액을 바꿨습니다. 배분표를 다시 맞춰 주세요.",
      ),
    ).toBeVisible();
  });
  it("FREE confirmation never shows reallocation message", async () => {
    panel();
    await fill("FREE");
    change("상태", "CONFIRM");
    fireEvent.click(screen.getByTestId("contract-change-save"));
    await screen.findByText("저장했습니다.");
    expect(
      screen.queryByText(
        "현재 계약금액을 바꿨습니다. 배분표를 다시 맞춰 주세요.",
      ),
    ).not.toBeInTheDocument();
  });
  it("delete cancel preserves row and delete refreshes", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("contract-change-delete-7"));
    fireEvent.click(screen.getByTestId("contract-change-action-cancel"));
    expect(writes()).toHaveLength(0);
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId("contract-change-delete-7"));
    fireEvent.click(screen.getByTestId("contract-change-action-submit"));
    await screen.findByText("삭제했습니다.");
    expect(
      screen.queryByTestId("contract-change-row-7"),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("contract-change-row-8")).toBeVisible();
    expect(writes()[0][1].method).toBe("DELETE");
  });
  it("server field errors retain draft and focus field", async () => {
    failWrite = true;
    panel();
    await fill();
    fireEvent.click(screen.getByTestId("contract-change-save"));
    await screen.findAllByText("사유를 확인하세요.");
    expect(screen.getByLabelText("사유")).toHaveFocus();
    expect(screen.getByLabelText("사유")).toHaveValue("새 계약 변경");
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(changed).not.toHaveBeenCalled();
  });
  it("dirty cancellation requires explicit discard", async () => {
    panel();
    await fill();
    fireEvent.click(screen.getByTestId("contract-change-cancel"));
    expect(screen.getByTestId("contract-change-discard-dialog")).toBeVisible();
    fireEvent.click(screen.getByTestId("contract-change-keep"));
    await waitFor(() =>
      expect(
        screen.queryByTestId("contract-change-discard-dialog"),
      ).not.toBeInTheDocument(),
    );
    expect(screen.getByLabelText("사유")).toHaveValue("새 계약 변경");
    fireEvent.click(screen.getByTestId("contract-change-cancel"));
    fireEvent.click(screen.getByTestId("contract-change-discard"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("disabled projects remain readable with no business actions", async () => {
    project.use_yn = "N";
    panel();
    await screen.findByTestId("contract-change-row-7");
    expect(
      screen.getByText("사용 안 함으로 바꾼 프로젝트입니다."),
    ).toBeVisible();
    expect(screen.queryByTestId("contract-change-add")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("contract-change-edit-7"),
    ).not.toBeInTheDocument();
  });
  it("mobile and resize are read only and close open dialogs", async () => {
    panel();
    await add();
    Object.defineProperty(window, "innerWidth", { value: 767 });
    fireEvent(window, new Event("resize"));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(screen.queryByTestId("contract-change-add")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("contract-change-confirm-7"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("contract-change-delete-7"),
    ).not.toBeInTheDocument();
  });
});
