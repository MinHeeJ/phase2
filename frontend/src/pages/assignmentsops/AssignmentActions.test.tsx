import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { AssignmentActions } from "./AssignmentActions";
import { AssignmentPreviewPanel } from "./AssignmentPreviewPanel";
import type { AssignmentSelection, AssignmentProposal } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const original: AssignmentSelection = {
  asgn_id: 7,
  asgn_no: "A-0007",
  dev_id: 2,
  dev_nm: "나가는 사람",
  prj_id: 3,
  bgn_dt: "2026-10-01",
  end_dt: "2026-12-31",
  asgn_stts_cd: "ACTIVE",
  updated_at: "2026-10-01T00:00:00.123456",
  warnings: [],
};
let fetcher: ReturnType<typeof vi.fn>;
let detail: AssignmentSelection;
let failRead: boolean, failWrite: boolean;
const reload = vi.fn().mockResolvedValue(undefined),
  open = vi.fn();
beforeEach(() => {
  detail = { ...original };
  failRead = false;
  failWrite = false;
  reload.mockClear();
  open.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/codes/EXT_POSSIBILITY")
      return response([
        { code: "H", name: "높음" },
        { code: "M", name: "보통" },
        { code: "L", name: "낮음" },
      ]);
    if (url === "/api/codes/ASGN_CHG_RSN")
      return response([{ code: "PERSON", name: "인력 사정" }]);
    if (url.startsWith("/api/developers/options"))
      return response([
        {
          id: 2,
          name: "나가는 사람",
          mngNo: "K-0002",
          mainJob: "백엔드",
          empType: "FT",
        },
        {
          id: 4,
          name: "들어오는 사람",
          mngNo: "K-0004",
          mainJob: "백엔드",
          empType: "FT",
        },
      ]);
    if (url === "/api/assignments/7") {
      if (failRead) throw new Error("offline");
      return response(detail);
    }
    if (url === "/api/assignments/check")
      return response({
        warnings: [
          { code: "OVER_MM", message: "MM 1.0 초과", work_ym: "2026-10" },
          { code: "PRE_CONTRACT", message: "미계약 선투입" },
          { code: "ON_LEAVE", message: "휴직 기간 배정" },
          { code: "DISPATCH_NONREG", message: "파견에 프리랜서·외주 배정" },
        ],
        months: [
          {
            work_ym: "2026-10",
            auto_mm_qty: 0.52,
            mm_qty: 0.52,
            mnl_yn: "N",
            person_month_mm_qty: 1.52,
            closed_yn: "N",
          },
        ],
      });
    if (init?.method === "POST") {
      if (failWrite)
        return new Response(
          JSON.stringify({
            success: false,
            code: "INVALID_INPUT",
            field: "aply_dt",
            message: "교체일은 투입일 다음 날부터 종료일까지입니다.",
            requestId: "replace-request",
          }),
          { status: 400 },
        );
      if (url.endsWith("/replace"))
        return response({
          previous_assignment: detail,
          new_assignment: { ...detail, asgn_id: 8 },
          changes: [],
        });
      return response({
        items: [
          {
            asgn_id: 7,
            status: "APPLIED",
            code: null,
            message: "저장했습니다.",
            updated_at: "2026-10-14T00:00:00.123457",
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
const writes = () =>
  fetcher.mock.calls.filter(
    (c) => c[1]?.method === "POST" && !c[0].endsWith("/check"),
  );
const actions = (
  props: {
    assignment?: AssignmentSelection;
    year?: number;
    readonly?: boolean;
  } = {},
) =>
  render(
    <AssignmentActions
      selected={[original]}
      assignment={props.assignment}
      filter={{ year: props.year ?? 2026 }}
      today="2026-10-14"
      readonly={props.readonly}
      reload={reload}
      onOpenAssignment={open}
    />,
  );
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
async function replace() {
  actions({ assignment: original });
  fireEvent.click(screen.getByTestId("assignment-open-replace"));
  await screen.findByRole("option", { name: "들어오는 사람 · K-0004" });
}
async function fillReplace() {
  await replace();
  change("교체일", "2026-11-16");
  change("들어오는 사람", "4");
  change("사유", "PERSON");
  change("메모", "교체 요청");
}
describe("assignment actions at /UI-12", () => {
  it("old year is read only but export remains available", () => {
    actions({ year: 2025 });
    expect(screen.getByText("읽기 전용")).toBeVisible();
    expect(
      screen.queryByTestId("assignment-open-chance"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("assignment-open-cancel"),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("assignment-export")).toBeVisible();
  });
  it("keeps future-year actions available like the assignment list", () => {
    actions({ year: 2027 });
    expect(screen.queryByText("읽기 전용")).not.toBeInTheDocument();
    expect(screen.getByTestId("assignment-open-chance")).toBeEnabled();
    expect(screen.getByTestId("assignment-open-cancel")).toBeEnabled();
  });
  it.each(["CANCELLED", "REPLACED"])(
    "%s detail hides forbidden actions",
    (status) => {
      actions({ assignment: { ...original, asgn_stts_cd: status } });
      expect(
        screen.queryByTestId("assignment-open-replace"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByTestId("assignment-open-cancel"),
      ).not.toBeInTheDocument();
    },
  );
  it("closed project actions remain hidden", () => {
    actions({ readonly: true });
    expect(
      screen.queryByTestId("assignment-open-chance"),
    ).not.toBeInTheDocument();
  });
  it("chance requires a choice and cancel sends nothing", async () => {
    actions();
    fireEvent.click(screen.getByTestId("assignment-open-chance"));
    await screen.findByRole("option", { name: "높음" });
    fireEvent.click(screen.getByTestId("assignment-chance-save"));
    expect(await screen.findByText("연장 가능성을 선택하세요.")).toBeVisible();
    fireEvent.click(screen.getByTestId("assignment-chance-cancel"));
    expect(writes()).toHaveLength(0);
  });
  it.each(["H", "UNDECIDED"])(
    "chance sends exact versions and %s with no extra fields",
    async (value) => {
      actions();
      fireEvent.click(screen.getByTestId("assignment-open-chance"));
      await screen.findByRole("option", { name: "높음" });
      change("연장 가능성", value);
      fireEvent.click(screen.getByTestId("assignment-chance-save"));
      await screen.findByText("연장 가능성을 변경했습니다.");
      expect(JSON.parse(writes()[0][1].body)).toEqual({
        ids: [7],
        value: value === "H" ? "H" : null,
        versions: [{ asgn_id: 7, updated_at: original.updated_at }],
      });
      expect(reload).toHaveBeenCalledOnce();
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    },
  );
  it("cancel preview uses read only detail and shows actual exclusion reason", async () => {
    detail = {
      ...original,
      warnings: [
        {
          code: "ASSIGNMENT_IN_USE",
          message: "청구 상세 연결 이력이 있습니다.",
        },
      ],
    };
    actions();
    fireEvent.click(screen.getByTestId("assignment-open-cancel"));
    await screen.findByText("청구 상세 연결 이력이 있습니다.");
    expect(screen.getByTestId("assignment-cancel-submit")).toBeDisabled();
    expect(
      screen.getByText("취소한 투입은 MM·원가·청구에서 빠집니다."),
    ).toBeVisible();
    expect(writes()).toHaveLength(0);
    fireEvent.click(screen.getByTestId("assignment-cancel-dismiss"));
    expect(writes()).toHaveLength(0);
  });
  it("cancel invokes mutation only on danger action and refreshes", async () => {
    actions();
    fireEvent.click(screen.getByTestId("assignment-open-cancel"));
    await screen.findByText("취소 가능");
    expect(writes()).toHaveLength(0);
    fireEvent.click(screen.getByTestId("assignment-cancel-submit"));
    await screen.findByText("배정을 취소했습니다.");
    expect(writes()[0][0]).toBe("/api/assignments/cancel");
    expect(reload).toHaveBeenCalledOnce();
  });
  it("cancel failed eligibility cannot invoke mutation", async () => {
    failRead = true;
    actions();
    fireEvent.click(screen.getByTestId("assignment-open-cancel"));
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.getByTestId("assignment-cancel-submit")).toBeDisabled();
    expect(writes()).toHaveLength(0);
  });
  it("replacement defaults tomorrow and excludes outgoing person", async () => {
    await replace();
    expect(screen.getByLabelText("교체일")).toHaveValue("2026-10-15");
    expect(
      screen.queryByRole("option", { name: "나가는 사람 · K-0002" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByText("청구서에는 계약상 인력 나가는 사람이 그대로 나갑니다."),
    ).toBeVisible();
  });
  it.each(["교체일", "들어오는 사람", "사유"])(
    "replacement requires %s",
    async (label) => {
      await fillReplace();
      change(label, "");
      fireEvent.click(screen.getByTestId("assignment-replace-save"));
      await screen.findByText("필수 항목과 입력 범위를 확인하세요.");
      expect(writes()).toHaveLength(0);
    },
  );
  it("replacement success opens new assignment without second confirmation", async () => {
    await fillReplace();
    fireEvent.click(screen.getByTestId("assignment-replace-save"));
    await screen.findByText("교체를 저장했습니다.");
    expect(open).toHaveBeenCalledWith(8);
    expect(reload).toHaveBeenCalledOnce();
    expect(JSON.parse(writes()[0][1].body)).toEqual({
      updated_at: original.updated_at,
      aply_dt: "2026-11-16",
      dev_id: 4,
      rsn_cd: "PERSON",
      rsn_desc: "교체 요청",
    });
  });
  it("replacement field failure preserves values", async () => {
    failWrite = true;
    await fillReplace();
    fireEvent.click(screen.getByTestId("assignment-replace-save"));
    await screen.findAllByText("교체일은 투입일 다음 날부터 종료일까지입니다.");
    expect(screen.getByLabelText("메모")).toHaveValue("교체 요청");
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(open).not.toHaveBeenCalled();
  });
  it("dirty cancel uses shared confirmation wording and no mutation", async () => {
    await fillReplace();
    fireEvent.click(screen.getByTestId("assignment-replace-cancel"));
    expect(screen.getByTestId("assignment-discard-dialog")).toBeVisible();
    fireEvent.click(screen.getByTestId("assignment-keep-writing"));
    await waitFor(() =>
      expect(
        screen.queryByTestId("assignment-discard-dialog"),
      ).not.toBeInTheDocument(),
    );
    expect(screen.getByLabelText("메모")).toHaveValue("교체 요청");
    fireEvent.click(screen.getByTestId("assignment-replace-cancel"));
    fireEvent.click(screen.getByTestId("assignment-discard"));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(writes()).toHaveLength(0);
  });
});
describe("assignment create warning preview", () => {
  const draft: AssignmentProposal = {
    prj_id: 3,
    dev_id: 4,
    job_cd: "BE",
    bgn_dt: "2026-10-16",
    end_dt: "2026-10-31",
    asgn_rt: 1,
    asgn_se_cd: "NEW",
  };
  it("valid draft shows all warning names and month total without saving", async () => {
    render(<AssignmentPreviewPanel draft={draft} valid />);
    const warning = await screen.findByTestId("assignment-preview-warnings");
    for (const name of [
      "MM 1.0 초과",
      "미계약 선투입",
      "휴직 기간 배정",
      "파견에 프리랜서·외주 배정",
    ])
      expect(within(warning).getByText(name)).toBeVisible();
    expect(screen.getByText("0.52")).toBeVisible();
    expect(screen.getByText("1.52").className).toContain("danger");
    expect(writes()).toHaveLength(0);
  });
  it("incomplete draft does not request or discard draft", () => {
    render(<AssignmentPreviewPanel draft={draft} valid={false} />);
    expect(fetcher).not.toHaveBeenCalled();
    expect(draft.bgn_dt).toBe("2026-10-16");
  });
  it("request failure is displayed and draft preserved", async () => {
    fetcher.mockRejectedValue(new Error("offline"));
    render(<AssignmentPreviewPanel draft={draft} valid />);
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(draft.dev_id).toBe(4);
  });
});
