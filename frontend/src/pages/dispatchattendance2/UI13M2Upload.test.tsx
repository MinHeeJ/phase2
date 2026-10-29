import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { UI13M2Upload } from "./UI13M2Upload";
const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let rejected: boolean, failure: string | null, empty: boolean;
const applied = vi.fn(),
  close = vi.fn();
const review = () => ({
  upload_id: 101,
  form_type: "B.3",
  month: "2026-10",
  row_count: 1,
  error_count: rejected ? 1 : 0,
  warning_count: 0,
  can_apply: !rejected,
  existing_salary_count: 0,
  overwrite_notice: "선택 월·프로젝트 근태 전체를 교체합니다.",
  issues_url: "/api/uploads/101/issues",
  normalized_rows_available: true,
});
beforeEach(() => {
  rejected = false;
  failure = null;
  empty = false;
  applied.mockReset();
  close.mockReset();
  fetcher = vi.fn(async (url: string) => {
    if (url.endsWith("/apply")) {
      if (failure)
        return new Response(
          JSON.stringify({
            success: false,
            code: failure,
            field: "month",
            message: "마감으로 반영할 수 없습니다.",
          }),
          { status: 409, headers: { "Content-Type": "application/json" } },
        );
      return ok({
        upload_id: 101,
        form_type: "B.3",
        applied_count: 1,
        message: "근태를 반영했습니다.",
      });
    }
    if (
      url === "/api/uploads/101" ||
      url.endsWith("/validate") ||
      url.endsWith("/revalidate")
    )
      return ok(review());
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
                      asgn_id: 11,
                      dev_id: 21,
                      work_day_qty: 10,
                      rglr_tm_qty: 80,
                      ovtm_tm_qty: 0,
                      mth_work_day_qty: 20,
                      calculated_mm: 0.5,
                      assignment_mm: 0.5,
                      price_available: true,
                      warnings: [],
                    },
              },
            ],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
      });
    if (url.includes("/issues?"))
      return ok({
        items: rejected
          ? [
              {
                row: 6,
                column: "해당 월 근무일수",
                severity: "ERROR",
                message: "필수 값입니다.",
                mapping_allowed: false,
              },
            ]
          : [],
        total: rejected ? 1 : 0,
        page: 0,
        size: 50,
      });
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = (props = {}) =>
  render(
    <UI13M2Upload
      month="2026-10"
      projectId={7}
      currentYear={2026}
      onApplied={applied}
      onClose={close}
      {...props}
    />,
  );
const choose = () =>
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [new File(["xlsx"], "근태.xlsx")] },
  });
async function validate() {
  choose();
  fireEvent.click(screen.getByTestId("attendance-validate"));
  await screen.findByText("엑셀 업로드 결과 · 파견 근태 2026-10");
  await waitFor(() =>
    expect(screen.queryByText("검사 행 조회 중")).not.toBeInTheDocument(),
  );
}
describe("B.3 attendance upload", () => {
  it("requires a file and preserves selected scope in multipart without generic billing imports", async () => {
    mount();
    expect(screen.getByTestId("attendance-validate")).toBeDisabled();
    await validate();
    const call = fetcher.mock.calls.find(
      ([url]) => url === "/api/uploads/dispatch-attendance/validate",
    )!;
    const body = (call[1] as RequestInit).body as FormData;
    expect(body.get("month")).toBe("2026-10");
    expect(body.get("projectId")).toBe("7");
    expect(body.get("file")).toBeInstanceOf(File);
    expect(
      screen.getByText("선택 월·프로젝트 근태 전체를 교체합니다."),
    ).toBeVisible();
    expect(screen.getByTestId("attendance-row-6")).toHaveTextContent("0.5");
    fireEvent.click(screen.getByTestId("attendance-apply"));
    await waitFor(() =>
      expect(applied).toHaveBeenCalledWith(
        expect.objectContaining({ applied_count: 1 }),
      ),
    );
    const applyCall = fetcher.mock.calls.find(
      ([url]) => url === "/api/uploads/dispatch-attendance/apply",
    )!;
    expect(JSON.parse(String((applyCall[1] as RequestInit).body))).toEqual({
      upload_id: 101,
      confirm: true,
    });
    expect(screen.getByTestId("attendance-apply")).toBeDisabled();
    expect(screen.getByTestId("attendance-success")).toHaveTextContent(
      "근태를 반영했습니다.",
    );
  });
  it("error row displays actual row column reason and blocks all application", async () => {
    rejected = true;
    mount();
    await validate();
    expect(await screen.findByText("필수 값입니다.")).toBeVisible();
    expect(screen.getByTestId("attendance-issue-0")).toHaveTextContent(
      "6해당 월 근무일수필수 값입니다.",
    );
    expect(screen.getByTestId("attendance-apply")).toBeDisabled();
    fireEvent.click(screen.getByTestId("attendance-revalidate"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) => url === "/api/uploads/101/revalidate",
        ),
      ).toBe(true),
    );
  });
  it("closed month disables inspection before any write", () => {
    mount({ closed: true });
    expect(screen.getByTestId("attendance-file")).toBeDisabled();
    expect(screen.getByTestId("attendance-validate")).toBeDisabled();
    expect(
      screen.getByText("마감된 달은 근태를 반영할 수 없습니다."),
    ).toBeVisible();
  });
  it("confirmed attendance disables inspection and explains its lock", () => {
    mount({ confirmed: true });
    expect(screen.getByTestId("attendance-validate")).toBeDisabled();
    expect(
      screen.getByText("확정 청구에 연결된 근태는 교체할 수 없습니다."),
    ).toBeVisible();
  });
  it("past year exposes read and download but no writes", () => {
    mount({ currentYear: 2027 });
    expect(screen.queryByLabelText("파일 선택")).not.toBeInTheDocument();
    expect(screen.getByTestId("attendance-template")).toBeVisible();
  });
  it("mobile hides write controls", () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => ({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    );
    mount();
    expect(screen.queryByTestId("attendance-validate")).not.toBeInTheDocument();
  });
  it("failed validation preserves file and displays error", async () => {
    fetcher.mockRejectedValue(new Error("offline"));
    mount();
    choose();
    fireEvent.click(screen.getByTestId("attendance-validate"));
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(screen.getByText("근태.xlsx")).toBeVisible();
    expect(applied).not.toHaveBeenCalled();
  });
  it("late close error preserves review and field feedback and disables apply", async () => {
    mount();
    await validate();
    failure = "CLOSED_MONTH";
    fireEvent.click(screen.getByTestId("attendance-apply"));
    await screen.findByTestId("attendance-field-error");
    expect(screen.getByTestId("attendance-field-error")).toHaveTextContent(
      "month",
    );
    expect(screen.getByTestId("attendance-apply")).toBeDisabled();
    expect(applied).not.toHaveBeenCalled();
  });
  it("empty preview is explicit rather than fabricated", async () => {
    empty = true;
    mount();
    await validate();
    expect(screen.getByText("해당 행이 없습니다.")).toBeVisible();
  });
  it("rejects renamed file before request", () => {
    mount();
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["text"], "file.csv")] },
    });
    expect(screen.getByTestId("attendance-validate")).toBeDisabled();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("scope change resets reviewed applicability", async () => {
    const view = mount();
    await validate();
    view.rerender(
      <UI13M2Upload
        month="2026-11"
        projectId={7}
        currentYear={2026}
        onApplied={applied}
      />,
    );
    expect(screen.queryByTestId("attendance-apply")).not.toBeInTheDocument();
    expect(screen.getByTestId("attendance-validate")).toBeDisabled();
  });
  it("close is local and never applies", () => {
    mount();
    fireEvent.click(screen.getByTestId("attendance-close"));
    expect(close).toHaveBeenCalledOnce();
    expect(fetcher).not.toHaveBeenCalled();
  });
});
