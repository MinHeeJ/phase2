import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { ProjectBillStagePanel } from "./ProjectBillStagePanel";
import { saveProjectBillStages, type ProjectBillStage } from "./client";

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
      requestId: "bill-request",
    }),
    { status },
  );
const original: ProjectBillStage = {
  bill_stg_id: 7,
  bill_stg_nm: "착수금",
  sply_amt: 500000000,
  bill_plan_dt: "2026-11-01",
  stg_odr_no: 1,
  vat_amt: 50000000,
  billed: false,
};
let rows: ProjectBillStage[];
let fetcher: ReturnType<typeof vi.fn>;
let writeFailure: Response | null;
let readFailure: boolean;
const changed = vi.fn().mockResolvedValue(undefined);
const dirty = vi.fn();
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  rows = [{ ...original }];
  writeFailure = null;
  readFailure = false;
  changed.mockClear();
  dirty.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (!url.match(/^\/api\/projects\/\d+\/bill-stages$/))
      throw new Error(`Unexpected request ${url}`);
    if (init?.method === "PUT") {
      if (writeFailure) return writeFailure.clone();
      const input = JSON.parse(init.body as string);
      rows = input.items.map((row: ProjectBillStage, index: number) => ({
        ...row,
        bill_stg_id: row.bill_stg_id ?? 10 + index,
        stg_odr_no: index + 1,
        vat_amt: Math.floor(row.sply_amt / 10),
        billed:
          row.bill_stg_id === 7 &&
          rows.some((r) => r.bill_stg_id === 7 && r.billed),
      }));
    } else if (readFailure) throw new Error("offline");
    return response({
      items: rows,
      cur_cntrct_amt: 500000000,
      totalSupplyAmount: rows.reduce((sum, row) => sum + row.sply_amt, 0),
    });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = (
  props: Partial<Parameters<typeof ProjectBillStagePanel>[0]> = {},
) =>
  render(
    <ProjectBillStagePanel
      projectId={3}
      contractType="TK"
      useYn="Y"
      onChanged={changed}
      onDirtyChange={dirty}
      {...props}
    />,
  );
const writes = () => fetcher.mock.calls.filter((c) => c[1]?.method === "PUT");
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
async function ready() {
  await screen.findByTestId("bill-stage-row-0");
}
async function save() {
  fireEvent.click(screen.getByTestId("bill-stage-save"));
}

describe("project bill stages", () => {
  it("renders API columns, VAT and totals without mismatch when equal", async () => {
    panel();
    await ready();
    for (const name of [
      "차수",
      "단계명",
      "공급가액",
      "부가세",
      "청구 예정일",
      "청구 여부",
    ])
      expect(screen.getByRole("columnheader", { name })).toBeVisible();
    expect(screen.getByLabelText("1차 단계명")).toHaveValue("착수금");
    expect(screen.getByLabelText("1차 청구 예정일")).toHaveValue("2026-11-01");
    expect(screen.getByText("50,000,000")).toBeVisible();
    expect(screen.getByText("미청구").className).toContain("neutral");
    expect(screen.queryByTestId("bill-stage-mismatch")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("shows precise empty state and allows an empty save", async () => {
    rows = [];
    panel();
    expect(
      await screen.findByText("등록된 청구 단계가 없습니다."),
    ).toBeVisible();
    await save();
    expect(await screen.findByTestId("bill-stage-success")).toHaveTextContent(
      "청구 단계를 저장했습니다.",
    );
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({ items: [] });
  });
  it("shows loading then a failed read and retries", async () => {
    readFailure = true;
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(
      screen.queryByText("등록된 청구 단계가 없습니다."),
    ).not.toBeInTheDocument();
    readFailure = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await ready();
  });
  it("adds named first stages, deletes without confirmation and restores on cancel", async () => {
    panel();
    await ready();
    fireEvent.click(screen.getByTestId("bill-stage-add"));
    expect(screen.getByLabelText("2차 단계명")).toHaveValue("중도금");
    fireEvent.click(screen.getByTestId("bill-stage-remove-0"));
    expect(screen.getByLabelText("1차 단계명")).toHaveValue("중도금");
    expect(dirty).toHaveBeenCalledWith(true);
    fireEvent.click(screen.getByTestId("bill-stage-cancel"));
    expect(screen.getByLabelText("1차 단계명")).toHaveValue("착수금");
    expect(screen.queryByTestId("bill-stage-row-1")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("warns in orange without preventing save and refreshes same tab plus history", async () => {
    panel();
    await ready();
    change("1차 공급가액", "490000000");
    const warning = screen.getByTestId("bill-stage-mismatch");
    expect(warning).toHaveTextContent(
      "청구 단계 합계(490,000,000)가 현재 계약금액(500,000,000)과 다릅니다.",
    );
    expect(warning.className).toContain("warning");
    await save();
    expect(await screen.findByTestId("bill-stage-success")).toBeVisible();
    expect(changed).toHaveBeenCalledOnce();
    expect(screen.getByLabelText("1차 공급가액")).toHaveValue("490000000");
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({
      items: [
        {
          bill_stg_id: 7,
          bill_stg_nm: "착수금",
          sply_amt: 490000000,
          bill_plan_dt: "2026-11-01",
        },
      ],
    });
    expect(
      fetcher.mock.calls.filter((c) => !c[1]?.method || c[1].method === "GET")
        .length,
    ).toBeGreaterThan(1);
  });
  it("equal three-stage amounts have no warning and save one complete list", async () => {
    rows = [100000000, 200000000, 200000000].map((sply_amt, index) => ({
      ...original,
      bill_stg_id: 7 + index,
      sply_amt,
      stg_odr_no: index + 1,
    }));
    panel();
    await ready();
    expect(screen.queryByTestId("bill-stage-mismatch")).not.toBeInTheDocument();
    await save();
    await screen.findByTestId("bill-stage-success");
    expect(JSON.parse(writes()[0][1].body as string).items).toHaveLength(3);
  });
  it.each([
    ["1차 단계명", " ", "단계명은 필수이며 100자 이하로 입력하세요."],
    ["1차 공급가액", "0", "공급가액은 1원 이상의 정수로 입력하세요."],
    ["1차 공급가액", "1.5", "공급가액은 1원 이상의 정수로 입력하세요."],
    [
      "1차 공급가액",
      "9223372036854775808",
      "공급가액은 1원 이상의 정수로 입력하세요.",
    ],
    ["1차 청구 예정일", "", "청구 예정일을 입력하세요."],
  ])(
    "validates %s = %s without write and focuses field",
    async (label, value, message) => {
      panel();
      await ready();
      change(label, value);
      await save();
      expect(await screen.findByText(message)).toBeVisible();
      expect(screen.getByLabelText(label)).toHaveFocus();
      expect(writes()).toHaveLength(0);
      expect(screen.getByLabelText(label)).toHaveValue(value);
    },
  );
  it("limits names to 100 and disables adding after 20", async () => {
    rows = Array.from({ length: 20 }, (_, index) => ({
      ...original,
      bill_stg_id: index + 1,
      stg_odr_no: index + 1,
    }));
    panel();
    await ready();
    expect(screen.getByLabelText("1차 단계명")).toHaveAttribute(
      "maxlength",
      "100",
    );
    expect(screen.getByTestId("bill-stage-add")).toBeDisabled();
    expect(screen.getAllByTestId(/^bill-stage-row-/)).toHaveLength(20);
  });
  it("billed rows are readonly with locked badge and cannot be deleted", async () => {
    rows[0].billed = true;
    panel();
    await ready();
    for (const label of ["1차 단계명", "1차 공급가액", "1차 청구 예정일"])
      expect(screen.getByLabelText(label)).toHaveAttribute("readonly");
    expect(screen.getByText("청구됨").className).toContain("locked");
    expect(screen.getByTestId("bill-stage-remove-0")).toBeDisabled();
    fireEvent.click(screen.getByTestId("bill-stage-add"));
    expect(screen.getByLabelText("2차 단계명")).not.toHaveAttribute("readonly");
  });
  it("does not allow deletion that would renumber a later billed stage", async () => {
    rows.push({ ...original, bill_stg_id: 8, stg_odr_no: 2, billed: true });
    panel();
    await ready();
    expect(screen.getByTestId("bill-stage-remove-0")).toBeDisabled();
  });
  it.each([
    ["STAGE_BILLED", "청구된 단계는 수정·삭제할 수 없습니다.", "items", 409],
    [
      "INVALID_INPUT",
      "청구 예정일을 확인하세요.",
      "items[0].bill_plan_dt",
      400,
    ],
  ])(
    "preserves entered values on server %s",
    async (code, message, field, status) => {
      panel();
      await ready();
      change("1차 단계명", "변경명");
      writeFailure = failure(code, message, field, status);
      await save();
      await screen.findAllByText(message);
      expect(screen.getByLabelText("1차 단계명")).toHaveValue("변경명");
      expect(screen.getByText("문의 번호 bill-request")).toBeVisible();
      if (field === "items[0].bill_plan_dt")
        expect(screen.getByLabelText("1차 청구 예정일")).toHaveFocus();
      expect(changed).not.toHaveBeenCalled();
      expect(
        screen.queryByTestId("bill-stage-success"),
      ).not.toBeInTheDocument();
    },
  );
  it("disabled project retains reads and hides mutation controls", async () => {
    panel({ useYn: "N" });
    await ready();
    expect(
      screen.getByText("사용 안 함 프로젝트는 조회만 가능합니다."),
    ).toBeVisible();
    expect(screen.queryByTestId("bill-stage-add")).not.toBeInTheDocument();
    expect(screen.queryByTestId("bill-stage-save")).not.toBeInTheDocument();
    expect(screen.getByLabelText("1차 단계명")).toHaveAttribute("readonly");
    expect(writes()).toHaveLength(0);
  });
  it("mobile is readonly and resizing discards unsaved edits", async () => {
    panel();
    await ready();
    change("1차 단계명", "수정");
    Object.defineProperty(window, "innerWidth", { value: 767 });
    fireEvent(window, new Event("resize"));
    expect(screen.queryByTestId("bill-stage-save")).not.toBeInTheDocument();
    expect(screen.getByLabelText("1차 단계명")).toHaveValue("착수금");
    expect(screen.getByLabelText("1차 단계명")).toHaveAttribute("readonly");
    expect(writes()).toHaveLength(0);
  });
  it.each(["MO", "DP", "IN"])(
    "does not show a tab or call stage API for %s",
    (contractType) => {
      panel({ contractType });
      expect(screen.queryByRole("tabpanel")).not.toBeInTheDocument();
      expect(fetcher).not.toHaveBeenCalled();
    },
  );
  it("selected project change reloads and does not leak previous draft", async () => {
    const view = panel();
    await ready();
    change("1차 단계명", "미저장");
    rows = [{ ...original, bill_stg_id: 8, bill_stg_nm: "다른 프로젝트" }];
    view.rerender(
      <ProjectBillStagePanel
        projectId={4}
        contractType="TK"
        useYn="Y"
        onChanged={changed}
      />,
    );
    await waitFor(() =>
      expect(screen.getByLabelText("1차 단계명")).toHaveValue("다른 프로젝트"),
    );
    expect(
      fetcher.mock.calls.some((c) => c[0] === "/api/projects/4/bill-stages"),
    ).toBe(true);
  });
  it("client excludes response-only VAT, billed and ordinal fields", async () => {
    await saveProjectBillStages(3, [original]);
    const input = JSON.parse(writes()[0][1].body as string).items[0];
    expect(input).not.toHaveProperty("billed");
    expect(input).not.toHaveProperty("vat_amt");
    expect(input).not.toHaveProperty("stg_odr_no");
  });
  it("new rows omit optional identity but retain existing identity", async () => {
    panel();
    await ready();
    fireEvent.click(screen.getByTestId("bill-stage-add"));
    change("2차 공급가액", "101");
    change("2차 청구 예정일", "2026-12-01");
    expect(
      within(screen.getByTestId("bill-stage-row-1")).getByText("10"),
    ).toBeVisible();
    await save();
    await screen.findByTestId("bill-stage-success");
    const inputs = JSON.parse(writes()[0][1].body as string).items;
    expect(inputs[0].bill_stg_id).toBe(7);
    expect(inputs[1]).not.toHaveProperty("bill_stg_id");
  });
});
