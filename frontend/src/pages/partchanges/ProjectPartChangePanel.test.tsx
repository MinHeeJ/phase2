import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { ProjectPartChangePanel } from "./ProjectPartChangePanel";
import {
  createProjectPartChange,
  type PartChangeProjectView,
  type ProjectPartChange,
} from "./client";
import * as permissions from "../../shared/types";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failure = (message: string, field: string | null = null, status = 409) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code: status === 409 ? "CLOSED_MONTH" : "INVALID_INPUT",
      message,
      field,
      requestId: "part-request",
    }),
    { status },
  );
const original: ProjectPartChange = {
  bsn_part_id: 11,
  aply_bgn_ym: "2026-03",
  rsn_desc: null,
  chg_user_id: null,
  chg_at: "2026-03-01T09:00:00",
  partName: "A파트",
};
const project: PartChangeProjectView = {
  prj_id: 37,
  prj_nm: "실제 응답 프로젝트",
  cntrct_ty_cd: "MO",
  use_yn: "Y",
  bsn_part_id: 11,
  partName: "A파트",
};
let rows: ProjectPartChange[], months: { month: string; status: string }[];
let fetcher: ReturnType<typeof vi.fn>;
let writeFailure: Response | null,
  readFailure: boolean,
  choicesFailure: boolean,
  noOptions: boolean;
const changed = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  rows = [{ ...original }];
  months = [
    { month: "2026-09", status: "CLOSED" },
    { month: "2026-10", status: "OPEN" },
  ];
  writeFailure = null;
  readFailure = false;
  choicesFailure = false;
  noOptions = false;
  changed.mockReset().mockResolvedValue(undefined);
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/sales-parts/options") {
      if (choicesFailure) throw new Error("offline");
      return response(
        noOptions
          ? []
          : [
              { id: "11", name: "A파트", hq: false },
              { id: "12", name: "B파트", hq: false },
              { id: "13", name: "본사", hq: true },
            ],
      );
    }
    if (url.startsWith("/api/month-close?")) return response(months);
    if (url === "/api/projects/37/part-changes") {
      if (init?.method === "POST") {
        if (writeFailure) return writeFailure.clone();
        const input = JSON.parse(init.body as string);
        const row = {
          ...original,
          ...input,
          partName: input.bsn_part_id === 13 ? "본사" : "B파트",
        };
        rows = [
          ...rows.filter((r) => r.aply_bgn_ym !== input.aply_bgn_ym),
          row,
        ].sort((a, b) => a.aply_bgn_ym.localeCompare(b.aply_bgn_ym));
        return response(rows);
      }
      if (readFailure) throw new Error("offline");
      return response(rows);
    }
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const writes = () =>
  fetcher.mock.calls.filter((call) => call[1]?.method === "POST");
const panel = (value = project) =>
  render(<ProjectPartChangePanel project={value} onChanged={changed} />);
const change = (name: string, value: string) =>
  fireEvent.change(screen.getByLabelText(name), { target: { value } });
async function open() {
  fireEvent.click(await screen.findByTestId("project-part-change-open"));
  await screen.findByRole("option", { name: "B파트" });
  await waitFor(() =>
    expect(screen.getByLabelText("적용 시작 월")).toHaveValue("2026-10"),
  );
}
async function fill() {
  change("새 영업 파트", "12");
  change("사유", "담당 파트 조정");
}

describe("project sales-part history and change", () => {
  it("renders API history with null initial reason and keeps server month order", async () => {
    rows.push({
      ...original,
      bsn_part_id: 12,
      partName: "B파트",
      aply_bgn_ym: "2026-10",
      rsn_desc: "변경 사유",
    });
    panel();
    expect(
      await screen.findByTestId("project-part-change-row-2026-03"),
    ).toHaveTextContent("2026-03 A파트");
    expect(
      screen.getByTestId("project-part-change-row-2026-10"),
    ).toHaveTextContent("2026-10 B파트변경 사유");
    expect(
      screen
        .getAllByTestId(/^project-part-change-row-/)
        .map((r) => r.dataset.testid),
    ).toEqual([
      "project-part-change-row-2026-03",
      "project-part-change-row-2026-10",
    ]);
    expect(writes()).toHaveLength(0);
  });
  it("shows loading then the precise empty state with no invented history", async () => {
    rows = [];
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    expect(
      await screen.findByText("등록된 영업 파트 이력이 없습니다."),
    ).toBeVisible();
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });
  it("failed list reads are retryable and never appear as an empty success", async () => {
    readFailure = true;
    panel();
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(
      screen.queryByText("등록된 영업 파트 이력이 없습니다."),
    ).not.toBeInTheDocument();
    readFailure = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(
      await screen.findByTestId("project-part-change-row-2026-03"),
    ).toBeVisible();
  });
  it("uses selected project name, maximum closure, next-month default and active options including HQ", async () => {
    months.unshift({ month: "2026-07", status: "CLOSED" });
    panel();
    await open();
    const dialog = screen.getByRole("dialog", { name: "영업 파트 변경" });
    expect(dialog).toHaveTextContent(
      "「실제 응답 프로젝트」의 영업 파트를 바꿉니다.",
    );
    expect(dialog).toHaveTextContent(
      "적용 시작 월부터 새 파트로 잡히고, 마감한 달(2026-09까지)의 실적·정산은 바뀌지 않습니다.",
    );
    expect(dialog).toHaveTextContent(
      "마감 안 된 달(2026-10)부터 고를 수 있습니다.",
    );
    expect(
      within(dialog).queryByRole("option", { name: "A파트" }),
    ).not.toBeInTheDocument();
    expect(
      within(dialog).getByRole("option", { name: "본사(직접 영업)" }),
    ).toBeVisible();
    expect(screen.getByLabelText("사유")).toHaveAttribute("maxlength", "200");
    expect(screen.getByLabelText("사유")).toBeRequired();
    expect(screen.getByLabelText("적용 시작 월")).toHaveAttribute(
      "min",
      "2026-10",
    );
  });
  it("requires a new part without writing and focuses its field", async () => {
    panel();
    await open();
    fireEvent.click(screen.getByTestId("project-part-save"));
    expect(
      await screen.findByText("새 영업 파트를 골라 주세요."),
    ).toBeVisible();
    expect(screen.getByLabelText("새 영업 파트")).toHaveFocus();
    expect(writes()).toHaveLength(0);
  });
  it("requires nonblank reason while preserving selected part and month", async () => {
    panel();
    await open();
    change("새 영업 파트", "12");
    change("사유", "   ");
    fireEvent.click(screen.getByTestId("project-part-save"));
    expect(await screen.findByText("사유를 입력해 주세요.")).toBeVisible();
    expect(screen.getByLabelText("사유")).toHaveFocus();
    expect(screen.getByLabelText("새 영업 파트")).toHaveValue("12");
    expect(writes()).toHaveLength(0);
  });
  it("rejects reasons over 200 characters", async () => {
    panel();
    await open();
    await fill();
    change("사유", "가".repeat(201));
    fireEvent.click(screen.getByTestId("project-part-save"));
    expect(
      await screen.findByText("사유는 200자 이하로 입력해 주세요."),
    ).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("rejects a closed month locally with the exact message under the month input", async () => {
    panel();
    await open();
    await fill();
    change("적용 시작 월", "2026-09");
    fireEvent.click(screen.getByTestId("project-part-save"));
    expect(
      await screen.findByText("적용 시작 월은 2026-09 이후로 골라 주세요."),
    ).toBeVisible();
    expect(screen.getByLabelText("적용 시작 월")).toHaveFocus();
    expect(writes()).toHaveLength(0);
  });
  it("requires a valid month", async () => {
    panel();
    await open();
    await fill();
    change("적용 시작 월", "");
    fireEvent.click(screen.getByTestId("project-part-save"));
    expect(await screen.findByText("올바른 연월을 입력하세요.")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("saves anonymous explicit payload, closes, refreshes history and parent, and reports part/month", async () => {
    panel();
    await open();
    await fill();
    fireEvent.click(screen.getByTestId("project-part-save"));
    expect(
      await screen.findByText(
        "B파트 파트로 변경했습니다. 2026-10부터 적용됩니다.",
      ),
    ).toBeVisible();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(
      await screen.findByTestId("project-part-change-row-2026-10"),
    ).toHaveTextContent("B파트");
    expect(changed).toHaveBeenCalledOnce();
    expect(writes()[0][0]).toBe("/api/projects/37/part-changes");
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({
      bsn_part_id: 12,
      aply_bgn_ym: "2026-10",
      rsn_desc: "담당 파트 조정",
    });
    expect(writes()[0][1]).not.toHaveProperty("headers.Authorization");
  });
  it("same-month save refreshes a corrected single row without duplicating it", async () => {
    rows.push({
      ...original,
      bsn_part_id: 13,
      partName: "본사",
      aply_bgn_ym: "2026-10",
      rsn_desc: "이전 사유",
    });
    panel();
    await open();
    await fill();
    fireEvent.click(screen.getByTestId("project-part-save"));
    expect(
      await screen.findByText(
        "B파트 파트로 변경했습니다. 2026-10부터 적용됩니다.",
      ),
    ).toBeVisible();
    expect(screen.getAllByTestId(/^project-part-change-row-/)).toHaveLength(2);
    expect(
      screen.getByTestId("project-part-change-row-2026-10"),
    ).toHaveTextContent("담당 파트 조정");
  });
  it.each([
    [409, "aply_bgn_ym", "적용 시작 월은 2026-10 이후로 골라 주세요."],
    [400, "rsn_desc", "사유를 입력해 주세요."],
  ])(
    "preserves all inputs and original history after server %s",
    async (status, field, message) => {
      panel();
      await open();
      await fill();
      writeFailure = failure(message, field, status);
      fireEvent.click(screen.getByTestId("project-part-save"));
      await screen.findAllByText(message);
      expect(screen.getByRole("dialog")).toBeVisible();
      expect(screen.getByLabelText("새 영업 파트")).toHaveValue("12");
      expect(screen.getByLabelText("사유")).toHaveValue("담당 파트 조정");
      expect(
        screen.getByLabelText(field === "rsn_desc" ? "사유" : "적용 시작 월"),
      ).toHaveFocus();
      expect(screen.getByText("문의 번호 part-request")).toBeVisible();
      expect(rows).toEqual([original]);
      expect(changed).not.toHaveBeenCalled();
    },
  );
  it("choices failure blocks submission and retries without inventing choices", async () => {
    choicesFailure = true;
    panel();
    fireEvent.click(await screen.findByTestId("project-part-change-open"));
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(screen.getByTestId("project-part-save")).toBeDisabled();
    choicesFailure = false;
    fireEvent.click(within(screen.getByRole("dialog")).getByText("다시 시도"));
    expect(await screen.findByRole("option", { name: "B파트" })).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("empty active options show a dedicated message and disable change", async () => {
    noOptions = true;
    panel();
    fireEvent.click(await screen.findByTestId("project-part-change-open"));
    expect(
      await screen.findByText("선택할 수 있는 다른 영업 파트가 없습니다."),
    ).toBeVisible();
    expect(screen.getByTestId("project-part-save")).toBeDisabled();
  });
  it("future history is labelled as scheduled with the actual application month", async () => {
    rows.push({
      ...original,
      bsn_part_id: 12,
      partName: "B파트",
      aply_bgn_ym: "2099-12",
    });
    panel();
    expect(await screen.findByText("적용 예정 · 2099-12부터")).toBeVisible();
    expect(screen.getByText("적용 예정 · 2099-12부터").className).toContain(
      "info",
    );
  });
  it("no closure is explained and there is no invented minimum month", async () => {
    months = [];
    panel();
    fireEvent.click(await screen.findByTestId("project-part-change-open"));
    await screen.findByRole("option", { name: "B파트" });
    expect(screen.getByText(/마감한 달이 없습니다/)).toBeVisible();
    expect(screen.getByLabelText("적용 시작 월")).not.toHaveAttribute("min");
  });
  it("clean cancel closes without a request", async () => {
    panel();
    await open();
    fireEvent.click(screen.getByTestId("project-part-cancel"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("dirty cancel retains draft until the user explicitly leaves", async () => {
    panel();
    await open();
    await fill();
    fireEvent.click(screen.getByTestId("project-part-cancel"));
    const confirmation = screen.getByRole("dialog", {
      name: "저장하지 않은 내용이 있습니다.",
    });
    fireEvent.click(within(confirmation).getByText("계속 작성"));
    await waitFor(() =>
      expect(
        screen.queryByRole("dialog", {
          name: "저장하지 않은 내용이 있습니다.",
        }),
      ).not.toBeInTheDocument(),
    );
    expect(screen.getByLabelText("사유")).toHaveValue("담당 파트 조정");
    fireEvent.click(screen.getByTestId("project-part-cancel"));
    fireEvent.click(
      within(
        screen.getByRole("dialog", { name: "저장하지 않은 내용이 있습니다." }),
      ).getByText("나가기"),
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("IN preserves historical rows but has no change action", async () => {
    panel({ ...project, cntrct_ty_cd: "IN", bsn_part_id: null });
    expect(
      await screen.findByTestId("project-part-change-row-2026-03"),
    ).toBeVisible();
    expect(screen.getByText("대표 파트: 없음")).toBeVisible();
    expect(
      screen.queryByTestId("project-part-change-open"),
    ).not.toBeInTheDocument();
  });
  it("disabled projects retain history and show read-only guidance", async () => {
    panel({ ...project, use_yn: "N" });
    expect(
      await screen.findByTestId("project-part-change-row-2026-03"),
    ).toBeVisible();
    expect(screen.getByText(/다시 사용한 뒤 변경할 수 있습니다/)).toBeVisible();
    expect(
      screen.queryByTestId("project-part-change-open"),
    ).not.toBeInTheDocument();
  });
  it("mobile is read-only and resize removes a mutation dialog", async () => {
    panel();
    await open();
    Object.defineProperty(window, "innerWidth", { value: 767 });
    fireEvent(window, new Event("resize"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("project-part-change-open"),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("project-part-change-row-2026-03")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("binds update permission to the action", async () => {
    const can = vi.spyOn(permissions, "can").mockReturnValue(false);
    panel();
    await screen.findByTestId("project-part-change-row-2026-03");
    expect(can).toHaveBeenCalledWith("UI-01.update");
    expect(
      screen.queryByTestId("project-part-change-open"),
    ).not.toBeInTheDocument();
  });
  it("parent refresh failure cannot produce a false success or duplicate automatic save", async () => {
    changed.mockRejectedValue(new Error("refresh failed"));
    panel();
    await open();
    await fill();
    fireEvent.click(screen.getByTestId("project-part-save"));
    expect(
      await screen.findByText(
        "저장 후 조회에 실패했습니다. 현재 상태를 확인하세요.",
      ),
    ).toBeVisible();
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(screen.getByTestId("project-part-save")).toBeDisabled();
    expect(writes()).toHaveLength(1);
  });
  it("client whitelists payload and omits response-only metadata", async () => {
    await createProjectPartChange(37, {
      ...original,
      bsn_part_id: 12,
      rsn_desc: " 변경 ",
    });
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({
      bsn_part_id: 12,
      aply_bgn_ym: "2026-03",
      rsn_desc: "변경",
    });
  });
});
