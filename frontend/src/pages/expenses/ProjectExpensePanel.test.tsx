import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { ProjectExpensePanel } from "./ProjectExpensePanel";
import { updateProjectExpense, type ProjectExpense } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let rows: ProjectExpense[];
let fetcher: ReturnType<typeof vi.fn>;
let readFailure: boolean;
let closeFailure: boolean;
let codeFailure: boolean;
let writeFailure: Response | null;
const changed = vi.fn().mockResolvedValue(undefined);
const original: ProjectExpense = {
  expns_id: 7,
  expns_ym: "2026-10",
  expns_item_cd: "TRIP",
  expns_amt: 300000,
  vat_amt: 30000,
  pay_dt: "2026-10-15",
  rmrk_desc: "출장",
};
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  changed.mockClear();
  rows = [{ ...original }];
  readFailure = false;
  closeFailure = false;
  codeFailure = false;
  writeFailure = null;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/codes/EXPENSE_ITEM") {
      if (codeFailure) throw new Error("offline");
      return response([
        { code: "TRIP", name: "출장비" },
        { code: "ETC", name: "기타 경비" },
      ]);
    }
    if (url.startsWith("/api/admin/code-groups/EXPENSE_ITEM/codes?"))
      return response({
        items: [
          { dtl_cd: "TRIP", cd_nm: "출장비", use_yn: "Y" },
          { dtl_cd: "OLD", cd_nm: "이전 경비", use_yn: "N" },
        ],
        page: 1,
        size: 100,
        total: 2,
      });
    if (url.startsWith("/api/month-close?")) {
      if (closeFailure) throw new Error("offline");
      return response([
        { month: "2026-09", status: "CLOSED" },
        { month: "2026-10", status: "OPEN" },
      ]);
    }
    if (init?.method && init.method !== "GET") {
      if (writeFailure) return writeFailure.clone();
      if (init.method === "DELETE") {
        rows = rows.filter((r) => r.expns_id !== Number(url.split("/").at(-1)));
        return response({ prj_id: 3, expns_id: 7 });
      }
      const input = JSON.parse(init.body as string);
      if (init.method === "PUT") {
        rows = rows.map((r) => ({ ...r, ...input }));
        return response(rows[0]);
      }
      const row = { ...input, expns_id: 8 };
      rows.unshift(row);
      return response(row);
    }
    if (url === "/api/projects/3/expenses") {
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
const panel = (useYn: "Y" | "N" = "Y") =>
  render(
    <ProjectExpensePanel projectId={3} useYn={useYn} onChanged={changed} />,
  );
const writes = () =>
  fetcher.mock.calls.filter((c) => c[1]?.method && c[1].method !== "GET");
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
async function add() {
  const button = await screen.findByTestId("project-expense-add");
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
  await screen.findByRole("option", { name: "출장비" });
}
async function fill() {
  change("월", "2026-10");
  change("경비 구분", "TRIP");
  change("금액", "300000");
  await waitFor(() =>
    expect(screen.getByTestId("project-expense-save")).toBeEnabled(),
  );
}
const failure = (message: string, field: string | null, status = 409) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code: status === 409 ? "CLOSED_MONTH" : "INVALID_INPUT",
      message,
      field,
      requestId: "expense-request",
    }),
    { status },
  );

describe("project direct expenses", () => {
  it("renders the contract columns, newest month first and totals from response only", async () => {
    rows.push({
      ...original,
      expns_id: 6,
      expns_ym: "2026-09",
      expns_amt: 50000,
      vat_amt: 5000,
    });
    panel();
    const row = await screen.findByTestId("project-expense-row-7");
    expect(within(row).getByText("출장비")).toBeVisible();
    for (const label of ["월", "경비 구분", "금액", "부가세", "지급일", "비고"])
      expect(
        within(screen.getAllByRole("table")[0]).getByRole("columnheader", {
          name: label,
        }),
      ).toBeVisible();
    expect(
      screen
        .getAllByTestId(/^project-expense-row-/)
        .map((r) => r.dataset.testid),
    ).toEqual(["project-expense-row-7", "project-expense-row-6"]);
    expect(
      screen.getByTestId("project-expense-total-2026-10"),
    ).toHaveTextContent("300,000");
    expect(
      screen.getByTestId("project-expense-total-2026-09"),
    ).toHaveTextContent("50,000");
    expect(writes()).toHaveLength(0);
  });
  it("closed rows have locked badge and no edit or delete", async () => {
    rows = [{ ...original, expns_ym: "2026-09" }];
    panel();
    expect(
      within(await screen.findByTestId("project-expense-row-7")).getByText(
        "마감",
      ).className,
    ).toContain("locked");
    expect(
      screen.queryByTestId("project-expense-edit-7"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("project-expense-delete-7"),
    ).not.toBeInTheDocument();
  });
  it("historical inactive names remain readable but are not new choices", async () => {
    rows = [{ ...original, expns_item_cd: "OLD" }];
    panel();
    expect(await screen.findByText("이전 경비")).toBeVisible();
    await add();
    expect(
      screen.queryByRole("option", { name: "이전 경비" }),
    ).not.toBeInTheDocument();
  });
  it("empty list has no invented rows or totals", async () => {
    rows = [];
    panel();
    expect(
      await screen.findByText("등록된 직접 경비가 없습니다."),
    ).toBeVisible();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
  it("read failure supports retry and never looks like empty success", async () => {
    readFailure = true;
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(
      screen.queryByText("등록된 직접 경비가 없습니다."),
    ).not.toBeInTheDocument();
    readFailure = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("project-expense-row-7")).toBeVisible();
  });
  it("month-close failure fails closed and supports retry", async () => {
    closeFailure = true;
    panel();
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.getByTestId("project-expense-add")).toBeDisabled();
    expect(
      screen.queryByTestId("project-expense-edit-7"),
    ).not.toBeInTheDocument();
    closeFailure = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("project-expense-edit-7")).toBeVisible();
  });
  it("VAT defaults using integer floor and remains editable without being overwritten", async () => {
    panel();
    await add();
    await fill();
    expect(screen.getByLabelText("부가세")).toHaveValue("30000");
    change("금액", "300009");
    expect(screen.getByLabelText("부가세")).toHaveValue("30000");
    change("부가세", "123");
    change("금액", "400000");
    expect(screen.getByLabelText("부가세")).toHaveValue("123");
    expect(screen.getByLabelText("비고")).toHaveAttribute("maxlength", "200");
    expect(
      screen.getByText("외주 인력비는 넣지 않습니다(투입·지급에서 처리)."),
    ).toBeVisible();
  });
  it.each(["월", "경비 구분", "금액"])(
    "requires %s with focus and no write",
    async (label) => {
      panel();
      await add();
      await fill();
      change(label, label === "금액" ? "0" : "");
      await waitFor(() =>
        expect(screen.getByTestId("project-expense-save")).toBeEnabled(),
      );
      fireEvent.click(screen.getByTestId("project-expense-save"));
      await waitFor(() => expect(screen.getByLabelText(label)).toHaveFocus());
      expect(writes()).toHaveLength(0);
    },
  );
  it("blocks a known closed target month", async () => {
    panel();
    await add();
    await fill();
    change("월", "2026-09");
    await screen.findByText("2026-09은 마감된 달이라 입력할 수 없습니다.");
    await waitFor(() =>
      expect(screen.getByTestId("project-expense-save")).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId("project-expense-save"));
    expect(writes()).toHaveLength(0);
  });
  it("successful create refreshes table, totals and parent history", async () => {
    panel();
    await add();
    await fill();
    fireEvent.click(screen.getByTestId("project-expense-save"));
    expect(await screen.findByText("저장했습니다.")).toBeVisible();
    expect(changed).toHaveBeenCalledOnce();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(
      screen.getByTestId("project-expense-total-2026-10"),
    ).toHaveTextContent("600,000");
    expect(writes()[0][0]).toBe("/api/projects/3/expenses");
    expect(JSON.parse(writes()[0][1].body as string)).not.toHaveProperty(
      "expns_id",
    );
  });
  it("edits the selected identity only and preserves custom VAT", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("project-expense-edit-7"));
    await screen.findByRole("option", { name: "출장비" });
    expect(screen.getByLabelText("금액")).toHaveValue("300,000");
    change("비고", "수정한 비고");
    await waitFor(() =>
      expect(screen.getByTestId("project-expense-save")).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId("project-expense-save"));
    await screen.findByText("저장했습니다.");
    expect(writes()[0][0]).toBe("/api/projects/3/expenses/7");
    expect(writes()[0][1].method).toBe("PUT");
    expect(screen.getByText("수정한 비고")).toBeVisible();
  });
  it("server field errors retain draft and show request id", async () => {
    panel();
    await add();
    await fill();
    writeFailure = failure("입력값을 확인하세요.", "expns_amt", 400);
    fireEvent.click(screen.getByTestId("project-expense-save"));
    await screen.findAllByText("입력값을 확인하세요.");
    expect(screen.getByLabelText("금액")).toHaveValue("300,000");
    expect(screen.getByLabelText("금액")).toHaveFocus();
    expect(screen.getByText("문의 번호 expense-request")).toBeVisible();
    expect(changed).not.toHaveBeenCalled();
  });
  it("stale closure rejection keeps the form and data", async () => {
    panel();
    await add();
    await fill();
    writeFailure = failure(
      "2026-10은 마감된 달이라 입력할 수 없습니다.",
      "expns_ym",
    );
    fireEvent.click(screen.getByTestId("project-expense-save"));
    await screen.findAllByText("2026-10은 마감된 달이라 입력할 수 없습니다.");
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(rows).toEqual([original]);
  });
  it("delete uses the selected path without inventing a confirmation dialog and refreshes totals", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("project-expense-delete-7"));
    expect(await screen.findByText("삭제했습니다.")).toBeVisible();
    expect(changed).toHaveBeenCalledOnce();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(
      await screen.findByText("등록된 직접 경비가 없습니다."),
    ).toBeVisible();
    expect(writes()[0][0]).toBe("/api/projects/3/expenses/7");
    expect(writes()[0][1].method).toBe("DELETE");
  });
  it("rejected delete keeps the existing row", async () => {
    writeFailure = failure("마감된 경비입니다.", "expns_ym");
    panel();
    fireEvent.click(await screen.findByTestId("project-expense-delete-7"));
    expect(await screen.findByText("마감된 경비입니다.")).toBeVisible();
    expect(rows).toEqual([original]);
    expect(changed).not.toHaveBeenCalled();
  });
  it("disabled project permits reads only", async () => {
    panel("N");
    await screen.findByTestId("project-expense-row-7");
    expect(
      screen.getByText("사용 안 함으로 바꾼 프로젝트입니다."),
    ).toBeVisible();
    expect(screen.queryByTestId("project-expense-add")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("project-expense-edit-7"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("project-expense-delete-7"),
    ).not.toBeInTheDocument();
  });
  it("read-only viewport closes the form and hides writes", async () => {
    panel();
    await add();
    Object.defineProperty(window, "innerWidth", { value: 767 });
    fireEvent(window, new Event("resize"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByTestId("project-expense-add")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("project-expense-edit-7"),
    ).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("code loading failure disables save", async () => {
    codeFailure = true;
    panel();
    await addWithoutOptions();
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(screen.getByTestId("project-expense-save")).toBeDisabled();
  });
  it("dirty cancel asks before discarding, and does not write", async () => {
    panel();
    await add();
    await fill();
    fireEvent.click(screen.getByTestId("project-expense-cancel"));
    expect(
      screen.getByRole("dialog", { name: "저장하지 않은 내용이 있습니다." }),
    ).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("explicit payload never copies response identity", async () => {
    await updateProjectExpense(3, 7, original);
    expect(JSON.parse(writes()[0][1].body as string)).not.toHaveProperty(
      "expns_id",
    );
  });
});
async function addWithoutOptions() {
  const button = await screen.findByTestId("project-expense-add");
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
}
