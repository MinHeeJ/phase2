import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import {
  DispatchAttendanceUploadActions,
  DispatchAttendanceUploadDialog,
} from "./DispatchAttendanceUploadDialog";
import * as permissions from "../../shared/types";
import table from "../developers/Developers.module.css";

vi.mock("../shared/PeriodBar", () => ({ seoulMonth: () => "2026-10" }));
const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failure = (
  message: string,
  code = "VALIDATION_ERROR",
  field = "file",
  status = 400,
) =>
  new Response(
    JSON.stringify({
      success: false,
      code,
      field,
      message,
      requestId: "attendance-test",
    }),
    {
      status,
      headers: { "Content-Type": "application/json" },
    },
  );
let fetcher: ReturnType<typeof vi.fn>;
let rejected: boolean,
  closed: boolean,
  noProjects: boolean,
  badProject: boolean;
let validateFailure: boolean, applyFailure: boolean, rowsFailure: boolean;
const applied = vi.fn(),
  close = vi.fn();
const review = () => ({
  upload_id: 123,
  form_type: "B.3",
  month: "2026-10",
  row_count: 1,
  error_count: rejected ? 1 : 0,
  warning_count: 0,
  can_apply: !rejected,
  overwrite_notice: "선택 월·프로젝트의 근태 전체를 교체합니다.",
  normalized_rows_available: true,
  issues_url: "/api/uploads/123/issues",
});
beforeEach(() => {
  rejected =
    closed =
    noProjects =
    badProject =
    validateFailure =
    applyFailure =
    rowsFailure =
      false;
  applied.mockReset();
  close.mockReset();
  Object.defineProperty(window, "innerWidth", {
    value: 1280,
    configurable: true,
  });
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/projects/options")
      return ok(
        noProjects
          ? []
          : [
              { id: 11, no: "P-2611", name: "검사 프로젝트", type: "DP" },
              { id: 12, no: "P-2612", name: "다른 파견", type: "DP" },
              { id: 13, no: "P-2613", name: "월단위", type: "MO" },
            ],
      );
    if (/^\/api\/projects\/[0-9]+$/.test(url))
      return ok({
        cntrct_ty_cd: "DP",
        stlm_mthd_cd: badProject ? "MONTH" : "DAY",
      });
    if (url.startsWith("/api/month-close"))
      return ok([{ month: "2026-10", status: closed ? "CLOSED" : "OPEN" }]);
    if (url === "/api/uploads/dispatch-attendance/validate")
      return validateFailure
        ? failure(
            "해당 월 근무일수를 확인하세요.",
            "VALIDATION_ERROR",
            "mth_work_day_qty",
          )
        : ok(review());
    if (url.startsWith("/api/uploads/123/rows?"))
      return rowsFailure
        ? failure("검사 행 조회 실패", "SERVER_ERROR", "", 500)
        : ok({
            items: [
              {
                row: 6,
                severity: rejected ? "ERROR" : "NORMAL",
                management_number: "K-0101",
                name: "검사 인력",
                attendance: {
                  asgn_id: 99,
                  dev_id: 22,
                  work_day_qty: 20,
                  rglr_tm_qty: 160,
                  ovtm_tm_qty: 0,
                  mth_work_day_qty: 20,
                },
                calculated_mm: 1,
                assignment_mm: 0.95,
                price_available: true,
              },
            ],
            total: 1,
            page: 0,
            size: 50,
          });
    if (url.startsWith("/api/uploads/123/issues?"))
      return ok({
        items: rejected
          ? [
              {
                row: 6,
                column: "해당 월 근무일수",
                severity: "ERROR",
                raw_value: "",
                message: "월 근무일수가 필요합니다.",
                mapping_allowed: false,
              },
            ]
          : [],
        total: rejected ? 1 : 0,
        page: 0,
        size: 50,
      });
    if (url === "/api/uploads/dispatch-attendance/apply")
      return applyFailure
        ? failure(
            "확정 청구에 사용된 근태입니다.",
            "ATTENDANCE_BILLED",
            "month",
            409,
          )
        : ok({
            upload_id: 123,
            form_type: "B.3",
            applied_count: 1,
            message: "선택 범위를 반영했습니다.",
          });
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function mount(
  props: Partial<Parameters<typeof DispatchAttendanceUploadDialog>[0]> = {},
) {
  return render(
    <DispatchAttendanceUploadDialog
      month="2026-10"
      projectId={11}
      onApplied={applied}
      onClose={close}
      {...props}
    />,
  );
}
const choose = (file = new File(["xlsx bytes"], "근태.xlsx")) =>
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [file] },
  });
async function inspect() {
  choose();
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "검사" })).toBeEnabled(),
  );
  fireEvent.click(screen.getByRole("button", { name: "검사" }));
  await screen.findByRole("table", { name: "근태 검사 미리보기" });
  await screen.findByText("검사 인력");
}
describe("B.3 attendance upload", () => {
  it("loads DB project options and shows the eight columns without fabricated data", async () => {
    mount();
    await screen.findByRole("option", { name: "P-2611 · 검사 프로젝트" });
    expect(
      screen.queryByRole("option", { name: /월단위/ }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "검사" })).toBeDisabled();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByText(/xlsx 10MB · 10,000행 이하/)).toBeVisible();
  });
  it("sends multipart scope, shows actual row and MM, and applies only after explicit confirmation", async () => {
    mount();
    await inspect();
    const request = fetcher.mock.calls.find(
      ([url]) => url === "/api/uploads/dispatch-attendance/validate",
    )!;
    const init = request[1] as RequestInit;
    const form = init.body as FormData;
    expect(form.get("month")).toBe("2026-10");
    expect(form.get("projectId")).toBe("11");
    expect(form.get("file")).toBeInstanceOf(File);
    expect(init.headers).toBeUndefined();
    const preview = screen.getByRole("table", { name: "근태 검사 미리보기" });
    expect(preview).toHaveClass(table.table);
    expect(preview.parentElement).toHaveClass(table.tableScroll);
    expect(within(preview).getByText("6")).toBeVisible();
    expect(within(preview).getByText("0.95")).toBeVisible();
    expect(within(preview).queryByRole("spinbutton")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "반영" })).toBeDisabled();
    fireEvent.click(
      screen.getByRole("checkbox", {
        name: "선택 월·프로젝트 전체 교체를 확인했습니다.",
      }),
    );
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "반영" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "반영" }));
    await screen.findByText("선택 범위를 반영했습니다.");
    const applyRequest = fetcher.mock.calls.find(
      ([url]) => url === "/api/uploads/dispatch-attendance/apply",
    )!;
    expect(JSON.parse((applyRequest[1] as RequestInit).body as string)).toEqual(
      { upload_id: 123, confirm: true },
    );
    expect(applied).toHaveBeenCalledWith(
      expect.objectContaining({ applied_count: 1 }),
    );
    expect(screen.getByRole("button", { name: "반영" })).toBeDisabled();
  });
  it("a single row error blocks whole-file apply and displays the actual issue", async () => {
    rejected = true;
    mount();
    await inspect();
    expect(await screen.findByText("월 근무일수가 필요합니다.")).toBeVisible();
    expect(
      screen.getByText("오류가 하나라도 있으면 파일 전체를 반영하지 않습니다."),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "반영" })).toBeDisabled();
    expect(screen.getByRole("checkbox")).toBeDisabled();
  });
  it("invalid file extension is caught before request", async () => {
    mount();
    choose(new File(["csv"], "근태.csv"));
    expect(
      await screen.findByTestId("attendance-upload-field-error"),
    ).toHaveTextContent("10MB 이하 xlsx");
    expect(fetcher.mock.calls.some(([url]) => url.endsWith("/validate"))).toBe(
      false,
    );
  });
  it("oversize file is caught before request", async () => {
    mount();
    choose(new File([new Uint8Array(10 * 1024 * 1024 + 1)], "근태.xlsx"));
    expect(
      await screen.findByTestId("attendance-upload-field-error"),
    ).toHaveTextContent("10MB 이하");
  });
  it("request failure preserves file and scope and exposes the flat API field", async () => {
    validateFailure = true;
    mount();
    choose();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "검사" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "검사" }));
    expect(
      await screen.findByTestId("attendance-upload-field-error"),
    ).toHaveTextContent("mth_work_day_qty");
    expect(screen.getByLabelText("프로젝트")).toHaveValue("11");
    expect(screen.getByRole("button", { name: "검사" })).toBeEnabled();
  });
  it("scope change retires prior review so it cannot be applied", async () => {
    mount();
    await inspect();
    fireEvent.change(screen.getByLabelText("프로젝트"), {
      target: { value: "12" },
    });
    expect(
      screen.queryByRole("table", { name: "근태 검사 미리보기" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "반영" })).toBeDisabled();
  });
  it("apply conflict keeps the dialog and requires reinspection", async () => {
    applyFailure = true;
    mount();
    await inspect();
    fireEvent.click(screen.getByRole("checkbox"));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "반영" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "반영" }));
    expect(
      await screen.findByTestId("attendance-upload-field-error"),
    ).toHaveTextContent("확정 청구");
    expect(screen.getByRole("button", { name: "반영" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "다시 검사" })).toBeEnabled();
    expect(close).not.toHaveBeenCalled();
  });
  it("failed row query blocks apply until results are available", async () => {
    rowsFailure = true;
    mount();
    choose();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "검사" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "검사" }));
    expect(await screen.findByText("검사 행 조회 실패")).toBeVisible();
    fireEvent.click(screen.getByRole("checkbox"));
    expect(screen.getByRole("button", { name: "반영" })).toBeDisabled();
  });
  it("empty project options have an empty state", async () => {
    noProjects = true;
    mount({ projectId: undefined });
    expect(await screen.findByText("파견 프로젝트가 없습니다.")).toBeVisible();
    expect(screen.getByRole("button", { name: "검사" })).toBeDisabled();
  });
  it("closed month hides write controls", async () => {
    closed = true;
    mount();
    expect(
      await screen.findByText("마감된 달은 근태를 반영할 수 없습니다."),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "반영" }),
    ).not.toBeInTheDocument();
  });
  it("past year is read/download only", async () => {
    mount({ month: "2025-10" });
    expect(
      screen.getByText("과거 연도는 조회·다운로드만 가능합니다."),
    ).toBeVisible();
    expect(screen.queryByLabelText("파일 선택")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "양식 내려받기" })).toBeVisible();
  });
  it("read-only state hides uploader and apply", () => {
    mount({ readOnly: true });
    expect(screen.queryByLabelText("파일 선택")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "반영" }),
    ).not.toBeInTheDocument();
  });
  it("mobile actions are not rendered", () => {
    Object.defineProperty(window, "innerWidth", {
      value: 600,
      configurable: true,
    });
    render(
      <DispatchAttendanceUploadActions month="2026-10" onApplied={applied} />,
    );
    expect(
      screen.queryByRole("button", { name: "엑셀 업로드" }),
    ).not.toBeInTheDocument();
  });
  it("reserved permission denial hides all write affordances", () => {
    vi.spyOn(permissions, "can").mockImplementation(
      (key) => key !== "UI-13.upload",
    );
    mount();
    expect(screen.queryByLabelText("파일 선택")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "반영" }),
    ).not.toBeInTheDocument();
  });
  it("MONTH settlement cannot be uploaded", async () => {
    badProject = true;
    mount();
    expect(
      await screen.findByText("일·시간 단가 파견 프로젝트만 올릴 수 있습니다."),
    ).toBeVisible();
    choose();
    expect(screen.getByRole("button", { name: "검사" })).toBeDisabled();
  });
  it("close never applies data", () => {
    mount();
    fireEvent.click(screen.getByRole("button", { name: "닫기" }));
    expect(close).toHaveBeenCalledOnce();
    expect(applied).not.toHaveBeenCalled();
  });
});
