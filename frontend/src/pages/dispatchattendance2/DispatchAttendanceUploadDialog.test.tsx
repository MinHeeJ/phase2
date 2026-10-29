import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import {
  DispatchAttendanceUploadActions,
  DispatchAttendanceUploadDialog,
} from "./DispatchAttendanceUploadDialog";
import table from "../developers/Developers.module.css";
const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let rejected: boolean, failed: boolean, empty: boolean;
const applied = vi.fn(),
  close = vi.fn();
const result = () => ({
  upload_id: 101,
  form_type: "B.3",
  month: "2026-10",
  row_count: empty ? 0 : 1,
  error_count: rejected ? 1 : 0,
  warning_count: 0,
  can_apply: !rejected && !empty,
  overwrite_notice: "선택 범위의 근태 전체 교체. 작성 청구는 유지됩니다.",
});
beforeEach(() => {
  rejected = false;
  failed = false;
  empty = false;
  applied.mockReset();
  close.mockReset();
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    value: 1280,
  });
  fetcher = vi.fn(async (url: string) => {
    if (url.startsWith("/api/projects/options"))
      return ok([
        { id: 3, no: "P-3", name: "파견 프로젝트", type: "DP" },
        { id: 4, no: "P-4", name: "내부", type: "IN" },
      ]);
    if (url.endsWith("/validate") || url.endsWith("/revalidate"))
      return ok(result());
    if (url.includes("/issues?"))
      return ok({
        items: rejected
          ? [
              {
                row: 6,
                column: "해당 월 근무일수",
                severity: "ERROR",
                message: "월 근무일수를 입력하세요.",
                raw_value: "",
                mapping_allowed: false,
              },
            ]
          : [],
        total: rejected ? 1 : 0,
      });
    if (url.includes("/rows?"))
      return ok({
        items: empty
          ? []
          : [
              {
                row: 6,
                severity: rejected ? "ERROR" : "NORMAL",
                attendance: rejected
                  ? null
                  : {
                      dev_id: 7,
                      work_day_qty: 10,
                      rglr_tm_qty: 80,
                      ovtm_tm_qty: 0,
                      mth_work_day_qty: 20,
                    },
                calculated_mm: rejected ? null : 0.5,
                assignment_mm: 0.5,
                warnings: [],
              },
            ],
        total: empty ? 0 : 1,
      });
    if (url.endsWith("/apply"))
      return failed
        ? new Response(
            JSON.stringify({
              success: false,
              code: "CLOSED_MONTH",
              message: "마감 월입니다.",
              field: "month",
              requestId: "request-1",
            }),
            { status: 409 },
          )
        : ok({
            upload_id: 101,
            form_type: "B.3",
            applied_count: 1,
            message: "근태를 반영했습니다.",
          });
    throw new Error(`Unexpected ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = () =>
  render(
    <DispatchAttendanceUploadDialog
      year={2026}
      currentYear={2026}
      initialMonth="2026-10"
      onApplied={applied}
      onClose={close}
    />,
  );
async function choose() {
  await screen.findByText("P-3 · 파견 프로젝트");
  fireEvent.change(screen.getByLabelText("프로젝트"), {
    target: { value: "3" },
  });
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [new File(["xlsx"], "근태.xlsx")] },
  });
}
async function inspect() {
  await choose();
  fireEvent.click(screen.getByRole("button", { name: "검사" }));
  await screen.findByText("엑셀 업로드 결과 · 파견 근태");
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "반영" })).toBeEnabled(),
  );
}
describe("B.3 attendance upload", () => {
  it("requires file and real selected scope and excludes internal projects", async () => {
    mount();
    expect(screen.getByRole("button", { name: "검사" })).toBeDisabled();
    await choose();
    expect(
      screen.queryByRole("option", { name: /내부/ }),
    ).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("연월"), {
      target: { value: "2025-10" },
    });
    expect(screen.getByRole("button", { name: "검사" })).toBeDisabled();
  });
  it("sends multipart month and project, previews actual rows with shared table style, confirms numeric ID and refreshes", async () => {
    mount();
    await inspect();
    const call = fetcher.mock.calls.find(
      (c) => c[0] === "/api/uploads/dispatch-attendance/validate",
    )!;
    const body = (call[1] as RequestInit).body as FormData;
    expect(body.get("month")).toBe("2026-10");
    expect(body.get("projectId")).toBe("3");
    expect(body.get("file")).toBeInstanceOf(File);
    expect(screen.getByTestId("attendance-preview-6")).toHaveTextContent("0.5");
    expect(screen.getAllByRole("table")[0]).toHaveClass(table.table);
    fireEvent.click(screen.getByRole("button", { name: "반영" }));
    expect(
      fetcher.mock.calls.some((c) => String(c[0]).endsWith("/apply")),
    ).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "교체 확인" }));
    await screen.findByText("근태를 반영했습니다.");
    const save = fetcher.mock.calls.find(
      (c) => c[0] === "/api/uploads/dispatch-attendance/apply",
    )!;
    expect(JSON.parse((save[1] as RequestInit).body as string)).toEqual({
      upload_id: 101,
      confirm: true,
    });
    expect(applied).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: "반영" })).toBeDisabled();
  });
  it("shows row error and disables whole-file apply", async () => {
    rejected = true;
    mount();
    await choose();
    fireEvent.click(screen.getByRole("button", { name: "검사" }));
    await screen.findByText("월 근무일수를 입력하세요.");
    expect(screen.getByRole("button", { name: "반영" })).toBeDisabled();
    expect(screen.getByTestId("attendance-issue-0")).toHaveTextContent("6");
  });
  it("preserves file and scope on business failure and does not refresh", async () => {
    failed = true;
    mount();
    await inspect();
    fireEvent.click(screen.getByRole("button", { name: "반영" }));
    fireEvent.click(screen.getByRole("button", { name: "교체 확인" }));
    await screen.findByText("마감 월입니다.");
    expect(screen.getByLabelText("연월")).toHaveValue("2026-10");
    expect(screen.getByLabelText("프로젝트")).toHaveValue("3");
    expect(screen.getByText("근태.xlsx")).toBeVisible();
    expect(applied).not.toHaveBeenCalled();
  });
  it("clears inspection after scope changes", async () => {
    mount();
    await inspect();
    fireEvent.change(screen.getByLabelText("연월"), {
      target: { value: "2026-11" },
    });
    expect(
      screen.queryByTestId("attendance-upload-result"),
    ).not.toBeInTheDocument();
  });
  it("rechecks through shared route", async () => {
    mount();
    await inspect();
    fireEvent.click(screen.getByRole("button", { name: "다시 검사" }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some((c) => c[0] === "/api/uploads/101/revalidate"),
      ).toBe(true),
    );
  });
  it("shows empty preview without allowing apply", async () => {
    empty = true;
    mount();
    await choose();
    fireEvent.click(screen.getByRole("button", { name: "검사" }));
    await screen.findByText("검사한 데이터가 없습니다.");
    expect(screen.getByRole("button", { name: "반영" })).toBeDisabled();
  });
  it("rejects renamed files before uploading", async () => {
    mount();
    await choose();
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["csv"], "근태.csv")] },
    });
    expect(screen.getByRole("button", { name: "검사" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("xlsx");
  });
  it("hides past-year mutations", () => {
    render(
      <DispatchAttendanceUploadActions
        year={2025}
        currentYear={2026}
        onApplied={applied}
      />,
    );
    expect(
      screen.queryByRole("button", { name: "엑셀 업로드" }),
    ).not.toBeInTheDocument();
  });
  it("hides mobile mutations", () => {
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      value: 767,
    });
    render(
      <DispatchAttendanceUploadActions
        year={2026}
        currentYear={2026}
        onApplied={applied}
      />,
    );
    expect(
      screen.queryByRole("button", { name: "엑셀 업로드" }),
    ).not.toBeInTheDocument();
  });
  it("reports failed inspection request without losing input", async () => {
    mount();
    await choose();
    fetcher.mockRejectedValueOnce(new Error("offline"));
    fireEvent.click(screen.getByRole("button", { name: "검사" }));
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.getByText("근태.xlsx")).toBeVisible();
  });
});
