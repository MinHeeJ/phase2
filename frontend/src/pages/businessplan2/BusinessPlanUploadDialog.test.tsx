import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BusinessPlanUploadDialog } from "./BusinessPlanUploadDialog";
const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let rejected: boolean, empty: boolean, conflict: boolean;
const applied = vi.fn(),
  close = vi.fn();
const result = () => ({
  upload_id: "9007199254740993",
  error_count: rejected ? 1 : 0,
  row_count: empty ? 0 : 1,
  can_apply: !rejected && !empty,
  applied_count: 0,
  overwrite_notice: "마감 월은 유지하고 미마감 월 전체를 교체합니다.",
  items: rejected
    ? [
        {
          field: "파트",
          row: 5,
          severity: "ERROR",
          message: "사용 중인 파트를 입력하세요.",
        },
      ]
    : [],
  rows: empty
    ? []
    : [
        {
          row: 5,
          severity: rejected ? "ERROR" : "NORMAL",
          plan: rejected
            ? null
            : {
                month: "2027-01",
                partId: 7,
                revenue: 1000000,
                profit: -100,
                utilization: 75.25,
              },
        },
      ],
});
beforeEach(() => {
  rejected = false;
  empty = false;
  conflict = false;
  applied.mockReset();
  close.mockReset();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/apply")) {
      if (conflict)
        return new Response(
          JSON.stringify({
            success: false,
            code: "CLOSED_MONTH",
            message: "마감된 월의 목표와 다릅니다.",
            field: "month",
          }),
          { status: 409 },
        );
      const confirm = JSON.parse(String(init?.body)).confirm;
      return ok({
        ...result(),
        applied_count: confirm ? 1 : 0,
        can_apply: !confirm,
      });
    }
    return ok(result());
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = (props = {}) =>
  render(
    <BusinessPlanUploadDialog onApplied={applied} onClose={close} {...props} />,
  );
async function validate() {
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [new File(["xlsx"], "계획.xlsx")] },
  });
  fireEvent.click(screen.getByTestId("business-plan-validate"));
  await screen.findByText("엑셀 업로드 결과 · 사업계획");
}
describe("B.10 business plan upload", () => {
  it("posts only file, preserves decimal long ID and requires explicit replacement confirmation", async () => {
    mount();
    expect(screen.getByTestId("business-plan-validate")).toBeDisabled();
    await validate();
    const body = (fetcher.mock.calls[0][1] as RequestInit).body as FormData;
    expect(Array.from(body.keys())).toEqual(["file"]);
    expect(screen.getByTestId("business-plan-row-5")).toHaveTextContent(
      "2027-01",
    );
    expect(screen.getByTestId("business-plan-apply")).toBeDisabled();
    fireEvent.click(screen.getByLabelText("미마감 월 전체 교체에 동의합니다"));
    fireEvent.click(screen.getByTestId("business-plan-apply"));
    await waitFor(() => expect(applied).toHaveBeenCalledOnce());
    expect(
      JSON.parse(String((fetcher.mock.calls[1][1] as RequestInit).body)),
    ).toEqual({ upload_id: "9007199254740993", confirm: true });
    expect(screen.getByTestId("business-plan-apply")).toBeDisabled();
    expect(screen.getByTestId("business-plan-success")).toHaveTextContent(
      "1건을 반영했습니다.",
    );
  });
  it("preview sends confirm false and never refreshes business data", async () => {
    mount();
    await validate();
    fireEvent.click(screen.getByTestId("business-plan-preview"));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(
      JSON.parse(String((fetcher.mock.calls[1][1] as RequestInit).body))
        .confirm,
    ).toBe(false);
    expect(applied).not.toHaveBeenCalled();
  });
  it("error shows row column and reason and refuses entire apply", async () => {
    rejected = true;
    mount();
    await validate();
    expect(screen.getByTestId("business-plan-issue-0")).toHaveTextContent(
      "5파트사용 중인 파트를 입력하세요.",
    );
    expect(screen.getByTestId("business-plan-apply")).toBeDisabled();
  });
  it("past year read-only hides writes but keeps template download", () => {
    mount({ readOnly: true });
    expect(screen.queryByLabelText("파일 선택")).not.toBeInTheDocument();
    expect(screen.getByTestId("business-plan-template")).toBeVisible();
    expect(
      screen.getByText("과거 연도는 조회·다운로드만 가능합니다."),
    ).toBeVisible();
  });
  it("failed validation keeps chosen file and permits retry", async () => {
    fetcher.mockRejectedValue(new Error("offline"));
    mount();
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["xlsx"], "계획.xlsx")] },
    });
    fireEvent.click(screen.getByTestId("business-plan-validate"));
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(screen.getByText("계획.xlsx")).toBeVisible();
    expect(applied).not.toHaveBeenCalled();
  });
  it("late close preserves review but disables apply until reinspection", async () => {
    mount();
    await validate();
    conflict = true;
    fireEvent.click(screen.getByLabelText("미마감 월 전체 교체에 동의합니다"));
    fireEvent.click(screen.getByTestId("business-plan-apply"));
    await screen.findByText("마감된 월의 목표와 다릅니다.");
    expect(screen.getByTestId("business-plan-apply")).toBeDisabled();
    expect(applied).not.toHaveBeenCalled();
  });
  it("empty list is explicit and cannot apply", async () => {
    empty = true;
    mount();
    await validate();
    expect(screen.getByText("해당 행이 없습니다.")).toBeVisible();
    expect(screen.getByTestId("business-plan-apply")).toBeDisabled();
  });
  it("invalid extension is blocked before calling API", () => {
    mount();
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["text"], "계획.csv")] },
    });
    expect(screen.getByTestId("business-plan-validate")).toBeDisabled();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("closing never applies", () => {
    mount();
    fireEvent.click(screen.getByTestId("business-plan-close"));
    expect(close).toHaveBeenCalledOnce();
    expect(fetcher).not.toHaveBeenCalled();
  });
});
