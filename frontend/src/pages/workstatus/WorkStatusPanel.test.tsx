import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { WorkStatusPanel } from "./WorkStatusPanel";
import { updateDeveloperWorkStatus, type WorkStatusRow } from "./client";

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
      requestId: "work-request",
    }),
    { status },
  );
let rows: WorkStatusRow[];
let fetcher: ReturnType<typeof vi.fn>;
let writeFailure: Response | null;
let readFailure: boolean;
const changed = vi.fn().mockResolvedValue(undefined);
const original: WorkStatusRow = {
  work_stts_hist_id: 7,
  dev_id: 3,
  work_stts_cd: "VACATION",
  bgn_dt: "2026-10-01",
  end_dt: "2026-10-03",
  paid_yn: "Y",
  rmrk_desc: "휴가 기록",
};
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  changed.mockClear();
  rows = [{ ...original }];
  writeFailure = null;
  readFailure = false;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/codes/WORK_STATUS")
      return response([
        { code: "VACATION", name: "휴가" },
        { code: "PARENTAL", name: "육아휴직" },
        { code: "UNPAID", name: "무급휴가" },
        { code: "LEAVE", name: "휴직" },
        { code: "RESIGN", name: "퇴사" },
      ]);
    if (init?.method && init.method !== "GET") {
      if (writeFailure) return writeFailure.clone();
      if (init.method === "DELETE") {
        rows = rows.filter(
          (r) => r.work_stts_hist_id !== Number(url.split("/").at(-1)),
        );
        return response({ work_stts_hist_id: 7, deleted: true, rsgn_dt: null });
      }
      const input = JSON.parse(init.body as string);
      if (init.method === "PUT") {
        rows = rows.map((r) => ({ ...r, ...input }));
        return response(rows[0]);
      }
      const row = { ...input, work_stts_hist_id: 8, dev_id: 3 };
      rows.unshift(row);
      return response(row);
    }
    if (url.startsWith("/api/developers/3/work-status?")) {
      if (readFailure) throw new Error("offline");
      return response({ items: rows, total: rows.length, page: 0, size: 50 });
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
  fetcher.mock.calls.filter((c) => c[1]?.method && c[1].method !== "GET");
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const panel = () =>
  render(<WorkStatusPanel developerId={3} onChanged={changed} />);
async function add() {
  fireEvent.click(await screen.findByTestId("work-status-add"));
  await screen.findByRole("option", { name: "육아휴직" });
}
async function fill() {
  change("상태", "PARENTAL");
  change("시작일", "2026-10-10");
  change("종료일", "2026-10-31");
}

describe("developer work status", () => {
  it("renders all history columns, vacation pay and API dates in server order", async () => {
    rows.push({
      ...original,
      work_stts_hist_id: 6,
      work_stts_cd: "LEAVE",
      bgn_dt: "2026-08-01",
      end_dt: null,
      rmrk_desc: null,
    });
    panel();
    const row = await screen.findByTestId("work-status-row-7");
    await waitFor(() => expect(within(row).getByText("휴가")).toBeVisible());
    for (const name of ["상태", "시작일", "종료일", "유급", "메모", "작업"])
      expect(screen.getByRole("columnheader", { name })).toBeVisible();
    expect(within(row).getByText("유급")).toBeVisible();
    expect(within(row).getByText("휴가 기록")).toBeVisible();
    expect(
      within(await screen.findByTestId("work-status-row-6")).getAllByText("–"),
    ).toHaveLength(3);
    expect(
      screen.getAllByTestId(/^work-status-row-/).map((r) => r.dataset.testid),
    ).toEqual(["work-status-row-7", "work-status-row-6"]);
    expect(
      fetcher.mock.calls.some((c) =>
        String(c[0]).includes("page=0&size=50&sort=bgn_dt%2Cdesc"),
      ),
    ).toBe(true);
  });
  it("shows precise empty message without sample rows", async () => {
    rows = [];
    panel();
    expect(
      await screen.findByText("등록된 근무 상태가 없습니다."),
    ).toBeVisible();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
  it("shows loading and failed read with retry, not an empty success", async () => {
    readFailure = true;
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(
      screen.queryByText("등록된 근무 상태가 없습니다."),
    ).not.toBeInTheDocument();
    readFailure = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("work-status-row-7")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("provides API options, required fields, optional end and 500 character memo", async () => {
    panel();
    await add();
    expect(screen.getByLabelText("시작일")).toBeRequired();
    expect(screen.getByLabelText("종료일")).not.toBeRequired();
    expect(screen.getByLabelText("메모")).toHaveAttribute("maxlength", "500");
    expect(
      within(screen.getByRole("dialog")).getAllByRole("option"),
    ).toHaveLength(6);
    fireEvent.click(screen.getByTestId("work-status-save"));
    expect(await screen.findByText("상태를 골라 주세요.")).toBeVisible();
    expect(screen.getByLabelText("상태")).toHaveFocus();
    expect(writes()).toHaveLength(0);
  });
  it("requires start and preserves draft on validation failure", async () => {
    panel();
    await add();
    change("상태", "PARENTAL");
    fireEvent.click(screen.getByTestId("work-status-save"));
    expect(await screen.findByText("시작일을 입력해 주세요.")).toBeVisible();
    expect(screen.getByLabelText("시작일")).toHaveFocus();
    expect(screen.getByLabelText("상태")).toHaveValue("PARENTAL");
    expect(writes()).toHaveLength(0);
  });
  it("rejects reversed dates without a request", async () => {
    panel();
    await add();
    await fill();
    change("종료일", "2026-10-09");
    fireEvent.click(screen.getByTestId("work-status-save"));
    expect(
      await screen.findByText("종료일은 시작일 이후여야 합니다."),
    ).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("vacation alone has initially unchecked pay, switching type resets it", async () => {
    panel();
    await add();
    change("상태", "VACATION");
    expect(screen.getByLabelText("유급")).not.toBeChecked();
    fireEvent.click(screen.getByLabelText("유급"));
    change("상태", "PARENTAL");
    expect(screen.queryByLabelText("유급")).not.toBeInTheDocument();
    change("상태", "VACATION");
    expect(screen.getByLabelText("유급")).not.toBeChecked();
  });
  it("resignation hides end and normalizes hidden payload without identity fields", async () => {
    panel();
    await add();
    await fill();
    change("상태", "RESIGN");
    expect(screen.queryByLabelText("종료일")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("work-status-save"));
    expect(await screen.findByText("저장했습니다.")).toBeVisible();
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({
      work_stts_cd: "RESIGN",
      bgn_dt: "2026-10-10",
      end_dt: null,
      paid_yn: "N",
      rmrk_desc: null,
    });
    expect(changed).toHaveBeenCalledOnce();
  });
  it("cancel returns to unchanged history without any write", async () => {
    panel();
    await add();
    await fill();
    fireEvent.click(screen.getByTestId("work-status-cancel"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(rows).toEqual([original]);
    expect(writes()).toHaveLength(0);
  });
  it("successful parental save refreshes history and parent status", async () => {
    panel();
    await add();
    await fill();
    fireEvent.click(screen.getByTestId("work-status-save"));
    expect(await screen.findByText("저장했습니다.")).toBeVisible();
    expect(await screen.findByTestId("work-status-row-8")).toBeVisible();
    expect(changed).toHaveBeenCalledOnce();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("opens the same form with selected values and PUTs the selected histId", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("work-status-edit-7"));
    await screen.findByRole("option", { name: "휴가" });
    expect(
      screen.getByRole("dialog", { name: "근무 상태 수정" }),
    ).toBeVisible();
    expect(screen.getByLabelText("유급")).toBeChecked();
    change("메모", "변경한 메모");
    fireEvent.click(screen.getByTestId("work-status-save"));
    expect(await screen.findByText("저장했습니다.")).toBeVisible();
    expect(writes()[0][0]).toBe("/api/developers/3/work-status/7");
    expect(writes()[0][1].method).toBe("PUT");
    expect(screen.getByText("변경한 메모")).toBeVisible();
  });
  it.each([
    [
      "PERIOD_OVERLAP",
      "다른 근무 상태(휴가 2026-10-01~2026-10-03)와 기간이 겹칩니다.",
      "bgn_dt",
      409,
    ],
    ["INVALID_INPUT", "이미 퇴사가 등록되어 있습니다.", "work_stts_cd", 400],
    ["CLOSED_MONTH", "마감한 달은 수정·삭제할 수 없습니다.", null, 409],
  ])("keeps form and draft for %s", async (code, message, field, status) => {
    panel();
    await add();
    await fill();
    writeFailure = failure(code, message, field, status);
    fireEvent.click(screen.getByTestId("work-status-save"));
    await screen.findAllByText(message);
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(screen.getByLabelText("시작일")).toHaveValue("2026-10-10");
    expect(screen.getByText("문의 번호 work-request")).toBeVisible();
    expect(changed).not.toHaveBeenCalled();
  });
  it("delete cancel keeps row and confirmation is required before DELETE", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("work-status-delete-7"));
    expect(
      screen.getByRole("dialog", { name: "이 근무 상태를 지울까요?" }),
    ).toBeVisible();
    expect(writes()).toHaveLength(0);
    expect(
      screen.getByTestId("work-status-delete-confirm").className,
    ).toContain("destructive");
    fireEvent.click(screen.getByTestId("work-status-delete-cancel"));
    expect(rows).toEqual([original]);
    expect(writes()).toHaveLength(0);
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId("work-status-delete-7"));
    fireEvent.click(screen.getByTestId("work-status-delete-confirm"));
    expect(await screen.findByText("삭제했습니다.")).toBeVisible();
    expect(
      await screen.findByText("등록된 근무 상태가 없습니다."),
    ).toBeVisible();
    expect(changed).toHaveBeenCalledOnce();
    expect(writes()[0][1].method).toBe("DELETE");
  });
  it("closed deletion stays in dialog and preserves the original row", async () => {
    writeFailure = failure(
      "CLOSED_MONTH",
      "마감한 달은 수정·삭제할 수 없습니다.",
    );
    panel();
    fireEvent.click(await screen.findByTestId("work-status-delete-7"));
    fireEvent.click(screen.getByTestId("work-status-delete-confirm"));
    expect(
      await screen.findByText("마감한 달은 수정·삭제할 수 없습니다."),
    ).toBeVisible();
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(rows).toEqual([original]);
    expect(changed).not.toHaveBeenCalled();
  });
  it("mobile is read-only and resize closes an existing mutation dialog", async () => {
    panel();
    await add();
    Object.defineProperty(window, "innerWidth", { value: 767 });
    fireEvent(window, new Event("resize"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByTestId("work-status-add")).not.toBeInTheDocument();
    expect(screen.queryByTestId("work-status-edit-7")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("work-status-delete-7"),
    ).not.toBeInTheDocument();
    expect(await screen.findByTestId("work-status-row-7")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("desktop at 768 allows future permission adapter actions", async () => {
    Object.defineProperty(window, "innerWidth", { value: 768 });
    panel();
    expect(await screen.findByTestId("work-status-edit-7")).toBeVisible();
  });
  it("explicit client payload excludes response-only developer and history identifiers", async () => {
    await updateDeveloperWorkStatus(3, 7, original);
    expect(JSON.parse(writes()[0][1].body as string)).not.toHaveProperty(
      "dev_id",
    );
    expect(JSON.parse(writes()[0][1].body as string)).not.toHaveProperty(
      "work_stts_hist_id",
    );
  });
});
