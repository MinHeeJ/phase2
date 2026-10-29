import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import { BillingAttendanceDialog } from "./BillingAttendanceDialog";
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
  });
let fetcher: ReturnType<typeof vi.fn>;
let errors: number;
beforeEach(() => {
  errors = 0;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/projects/options"))
      return response([{ id: 9, name: "파견 시험", type: "DP" }]);
    if (url.startsWith("/api/dispatch-attendance"))
      return response({
        month: "2026-10",
        project_id: 9,
        items: [
          {
            asgn_id: 3,
            dev_id: 5,
            work_day_qty: 10,
            rglr_tm_qty: 80,
            ovtm_tm_qty: 0,
            mth_work_day_qty: 20,
            calculated_mm: 0.5,
            assignment_mm: 1,
            price_available: false,
            warnings: [],
          },
        ],
        warnings: [],
      });
    if (url === "/api/uploads/dispatch-attendance/validate")
      return response({
        upload_id: 27,
        row_count: 2,
        error_count: errors,
        warning_count: 0,
        can_apply: errors === 0,
        overwrite_notice: null,
      });
    if (url.startsWith("/api/uploads/27/issues"))
      return response({
        items: errors
          ? [
              {
                row: 2,
                column: "관리번호",
                message: "존재하지 않는 인력",
                severity: "ERROR",
              },
            ]
          : [],
        total: errors,
      });
    if (url === "/api/uploads/dispatch-attendance/apply")
      return response({ upload_id: 27, form_type: "B.3", applied_count: 2 });
    throw new Error(`Unexpected request ${url} ${init?.method}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function panel() {
  render(
    <BillingAttendanceDialog
      initial={{ projectId: 9, month: "2026-10" }}
      onClose={vi.fn()}
      onChanged={vi.fn()}
    />,
  );
}
it("reads attendance with MM comparison and allows saving without a price", async () => {
  panel();
  fireEvent.click(screen.getByTestId("attendance-query"));
  await screen.findByTestId("attendance-row-3");
  expect(
    screen.getByText("단가 부재: 근태 저장 가능, 청구 생성 불가"),
  ).toBeVisible();
  expect(screen.getByTestId("attendance-row-3")).toHaveTextContent("0.5");
  fireEvent.click(screen.getByTestId("attendance-save"));
  await screen.findByText(
    "근태를 저장했습니다. 청구 상세는 다시 계산할 때만 바뀝니다.",
  );
  const write = fetcher.mock.calls.find((c) => c[1]?.method === "PUT")!;
  expect(write[0]).toContain("projectId=9");
  expect(JSON.parse(write[1].body as string).items[0].asgn_id).toBe(3);
});
it("refuses zero denominator without sending PUT", async () => {
  panel();
  fireEvent.click(screen.getByTestId("attendance-query"));
  await screen.findByTestId("attendance-row-3");
  fireEvent.change(screen.getByTestId("attendance-3-mth_work_day_qty"), {
    target: { value: "0" },
  });
  fireEvent.click(screen.getByTestId("attendance-save"));
  await screen.findByText("해당 월 근무일수는 0보다 커야 합니다.");
  expect(fetcher.mock.calls.some((c) => c[1]?.method === "PUT")).toBe(false);
});
it("validation is multipart and apply uses numeric ID and confirm true", async () => {
  panel();
  fireEvent.change(screen.getByTestId("attendance-file"), {
    target: { files: [new File(["test input"], "attendance.xlsx")] },
  });
  fireEvent.click(screen.getByTestId("attendance-validate"));
  await screen.findByTestId("attendance-inspection");
  const validate = fetcher.mock.calls.find((c) => c[0].endsWith("/validate"))!;
  expect(validate[1].body).toBeInstanceOf(FormData);
  expect((validate[1].body as FormData).get("projectId")).toBe("9");
  expect(fetcher.mock.calls.some((c) => c[0].endsWith("/apply"))).toBe(false);
  fireEvent.click(screen.getByTestId("attendance-apply"));
  await screen.findByText("선택 범위의 파견 근태를 반영했습니다.");
  await waitFor(() =>
    expect(fetcher.mock.calls.some((c) => c[0].endsWith("/apply"))).toBe(true),
  );
  const apply = fetcher.mock.calls.find((c) => c[0].endsWith("/apply"))!;
  expect(JSON.parse(apply[1].body as string)).toEqual({
    upload_id: 27,
    confirm: true,
  });
});
it("errors show actual row reasons and disable whole-file application", async () => {
  errors = 1;
  panel();
  fireEvent.change(screen.getByTestId("attendance-file"), {
    target: { files: [new File(["test input"], "attendance.xlsx")] },
  });
  fireEvent.click(screen.getByTestId("attendance-validate"));
  await screen.findByText("2행 · 관리번호 · 존재하지 않는 인력");
  expect(screen.getByTestId("attendance-apply")).toBeDisabled();
});
it("wrong extension never sends validation", async () => {
  panel();
  fireEvent.change(screen.getByTestId("attendance-file"), {
    target: { files: [new File(["not a workbook"], "attendance.csv")] },
  });
  fireEvent.click(screen.getByTestId("attendance-validate"));
  await screen.findByText("10MB 이하의 xlsx 파일을 선택하세요.");
  expect(fetcher.mock.calls.some((c) => c[0].endsWith("/validate"))).toBe(
    false,
  );
});
