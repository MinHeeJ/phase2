import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI13M2 from "./UI13M2";
import type { UploadResult } from "../apiops/client";
import table from "../developers/Developers.module.css";

const year = new Intl.DateTimeFormat("en", {
  timeZone: "Asia/Seoul",
  year: "numeric",
}).format(new Date());
const month = `${year}-10`;
const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let result: UploadResult;
let fetcher: ReturnType<typeof vi.fn>;
let failApply = false;
let failRows = false;
let emptyRows = false;
const applied = vi.fn(),
  closed = vi.fn();
beforeEach(() => {
  failApply = false;
  failRows = false;
  emptyRows = false;
  applied.mockReset();
  closed.mockReset();
  result = {
    upload_id: 51,
    form_type: "B.3",
    month,
    row_count: 1,
    error_count: 0,
    warning_count: 0,
    can_apply: true,
    existing_salary_count: 0,
    overwrite_notice: "선택 월·프로젝트의 근태 전체 교체",
    issues_url: "/api/uploads/51/issues",
    normalized_rows_available: true,
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/validate")) return ok(result);
    if (url.endsWith("/revalidate")) {
      result = { ...result, upload_id: 52, can_apply: true, error_count: 0 };
      return ok(result);
    }
    if (url.endsWith("/apply")) {
      if (failApply)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "CLOSED_MONTH",
            field: "month",
            requestId: "r-1",
            message: "마감된 달입니다.",
          }),
          { status: 409 },
        );
      result = { ...result, can_apply: false };
      return ok({
        upload_id: 51,
        form_type: "B.3",
        applied_count: 1,
        message: "근태를 반영했습니다.",
      });
    }
    if (/\/api\/uploads\/\d+$/.test(url)) return ok(result);
    if (url.includes("/rows?")) {
      if (failRows)
        return new Response(
          JSON.stringify({
            success: false,
            message: "조회 실패",
            requestId: "r-2",
          }),
          { status: 500 },
        );
      return ok({
        items: emptyRows
          ? []
          : [
              {
                row: 6,
                severity: result.error_count ? "ERROR" : "NORMAL",
                attendance: {
                  mng_no: "K-0021",
                  dev_nm: "시험 인력",
                  asgn_id: 11,
                  work_day_qty: 20,
                  rglr_tm_qty: 160,
                  ovtm_tm_qty: 0,
                  mth_work_day_qty: 20,
                  calculated_mm: 1,
                  assignment_mm: 0.8,
                  price_available: true,
                },
              },
            ],
        total: emptyRows ? 0 : 1,
        page: 0,
        size: 50,
      });
    }
    if (url.includes("/issues?"))
      return ok({
        items: result.error_count
          ? [
              {
                row: 6,
                column: "해당 월 근무일수",
                severity: "ERROR",
                raw_value: "",
                normalized_value: null,
                mapping_allowed: false,
                message: "월 근무일수는 필수입니다.",
              },
            ]
          : [],
        total: result.error_count,
        page: 0,
        size: 50,
      });
    if (url.includes("/templates/") || url.endsWith("/errors"))
      return new Response(new Uint8Array([80, 75]), { status: 200 });
    throw new Error(`Unexpected ${url} ${init?.method}`);
  });
  vi.stubGlobal("fetch", fetcher);
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: vi.fn(() => "blob:test"),
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    configurable: true,
    value: vi.fn(),
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = (props: Partial<React.ComponentProps<typeof UI13M2>> = {}) =>
  render(
    <UI13M2
      projectId={7}
      month={month}
      settlement="DAY"
      onApplied={applied}
      onClose={closed}
      {...props}
    />,
  );
function choose(file = new File(["test bytes"], "근태.xlsx")) {
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [file] },
  });
}
async function inspect() {
  choose();
  fireEvent.click(screen.getByTestId("attendance-upload-validate"));
  await screen.findByTestId("attendance-upload-row-6");
}

describe("UI-13 M2 B.3 upload", () => {
  it("keeps selected scope read-only and never sends JSON content type for multipart", async () => {
    mount();
    expect(screen.getByTestId("attendance-upload-project")).toHaveAttribute(
      "readonly",
    );
    expect(screen.getByTestId("attendance-upload-month")).toHaveValue(month);
    await inspect();
    const request = fetcher.mock.calls.find(([path]) =>
      path.endsWith("/validate"),
    )![1] as RequestInit;
    expect(request.headers).toBeUndefined();
    const body = request.body as FormData;
    expect(body.get("projectId")).toBe("7");
    expect(body.get("month")).toBe(month);
    expect(body.get("file")).toBeInstanceOf(File);
    expect(
      screen.getByText(`엑셀 업로드 결과 · 파견 근태 ${month}`),
    ).toBeVisible();
    expect(
      screen.getByTestId("attendance-upload-preview").querySelector("table"),
    ).toHaveClass(table.table);
    expect(screen.getByTestId("attendance-upload-preview")).toHaveClass(
      table.tableScroll,
    );
    expect(
      within(screen.getByTestId("attendance-upload-row-6")).getByText(
        "시험 인력",
      ),
    ).toBeVisible();
  });
  it("applies only inspected numeric identity with explicit confirmation then refreshes parent", async () => {
    mount();
    await inspect();
    await waitFor(() =>
      expect(screen.getByTestId("attendance-upload-apply")).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId("attendance-upload-apply"));
    await screen.findByTestId("attendance-upload-success");
    const request = fetcher.mock.calls.find(([path]) =>
      path.endsWith("/apply"),
    )![1] as RequestInit;
    expect(JSON.parse(request.body as string)).toEqual({
      upload_id: 51,
      confirm: true,
    });
    expect(applied).toHaveBeenCalledOnce();
    expect(screen.getByTestId("attendance-upload-apply")).toBeDisabled();
  });
  it("shows row error and refuses all application rather than applying valid rows", async () => {
    result = { ...result, error_count: 1, can_apply: false };
    mount();
    await inspect();
    expect(await screen.findByText("월 근무일수는 필수입니다.")).toBeVisible();
    expect(screen.getByTestId("attendance-upload-apply")).toBeDisabled();
    expect(fetcher.mock.calls.some(([path]) => path.endsWith("/apply"))).toBe(
      false,
    );
  });
  it("retains selected file and reports late close conflict including request id and field", async () => {
    failApply = true;
    mount();
    await inspect();
    await waitFor(() =>
      expect(screen.getByTestId("attendance-upload-apply")).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId("attendance-upload-apply"));
    expect(await screen.findByText("문의 번호 r-1")).toBeVisible();
    expect(
      screen.getByTestId("attendance-upload-field-error"),
    ).toHaveTextContent("month");
    expect(screen.getByText("근태.xlsx")).toBeVisible();
    expect(applied).not.toHaveBeenCalled();
    expect(screen.getByTestId("attendance-upload-apply")).toBeDisabled();
  });
  it("requires a fresh inspection after recheck and uses the new identity", async () => {
    mount();
    await inspect();
    fireEvent.click(screen.getByTestId("attendance-upload-recheck"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([path]) => path.includes("/52/rows")),
      ).toBe(true),
    );
    await waitFor(() =>
      expect(screen.getByTestId("attendance-upload-apply")).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId("attendance-upload-apply"));
    await waitFor(() => expect(applied).toHaveBeenCalled());
    const call = fetcher.mock.calls.find(([path]) =>
      path.endsWith("/apply"),
    )![1] as RequestInit;
    expect(JSON.parse(call.body as string).upload_id).toBe(52);
  });
  it("renders empty classified rows without fabricated personnel", async () => {
    emptyRows = true;
    mount();
    choose();
    fireEvent.click(screen.getByTestId("attendance-upload-validate"));
    expect(await screen.findByText("해당 행이 없습니다.")).toBeVisible();
    expect(screen.queryByText("시험 인력")).not.toBeInTheDocument();
  });
  it("shows failed row request and keeps apply disabled until recovery", async () => {
    failRows = true;
    mount();
    choose();
    fireEvent.click(screen.getByTestId("attendance-upload-validate"));
    expect(await screen.findByText("조회 실패")).toBeVisible();
    expect(screen.getByTestId("attendance-upload-apply")).toBeDisabled();
    failRows = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await screen.findByTestId("attendance-upload-row-6");
  });
  it("shows loading while validation is unresolved and prevents repeated submission", async () => {
    const original = fetcher.getMockImplementation()!;
    let resolve!: (value: Response) => void;
    fetcher.mockImplementation((path: string, init?: RequestInit) =>
      path.endsWith("/validate")
        ? new Promise<Response>((done) => {
            resolve = done;
          })
        : original(path, init),
    );
    mount();
    choose();
    fireEvent.click(screen.getByTestId("attendance-upload-validate"));
    expect(screen.getByText("처리 중")).toBeVisible();
    expect(screen.getByTestId("attendance-upload-validate")).toBeDisabled();
    resolve(ok(result));
    await screen.findByTestId("attendance-upload-row-6");
  });
  it("rejects renamed files before sending a request", () => {
    mount();
    choose(new File(["test"], "attendance.csv"));
    expect(
      screen.getAllByText("xlsx 파일만 올릴 수 있습니다.").length,
    ).toBeGreaterThan(0);
    expect(screen.getByTestId("attendance-upload-validate")).toBeDisabled();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("rejects oversized files before sending a request", () => {
    mount();
    const file = new File(["test"], "attendance.xlsx");
    Object.defineProperty(file, "size", { value: 10 * 1024 * 1024 + 1 });
    choose(file);
    expect(
      screen.getAllByText("파일은 10MB 이하만 올릴 수 있습니다.").length,
    ).toBeGreaterThan(0);
    expect(fetcher).not.toHaveBeenCalled();
  });
  it.each([{ month: "2000-10" }, { editable: false }, { settlement: "MONTH" }])(
    "hides write controls in ineligible state %j",
    (props) => {
      mount(props);
      expect(
        screen.queryByTestId("attendance-upload-file"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByTestId("attendance-upload-validate"),
      ).not.toBeInTheDocument();
      expect(fetcher).not.toHaveBeenCalled();
    },
  );
  it("hides upload on mobile without a browser-layout test", () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => ({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    );
    mount();
    expect(
      screen.queryByTestId("attendance-upload-file"),
    ).not.toBeInTheDocument();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("cancel closes without any request", () => {
    mount();
    fireEvent.click(screen.getByTestId("attendance-upload-close"));
    expect(closed).toHaveBeenCalledOnce();
    expect(fetcher).not.toHaveBeenCalled();
  });
});
