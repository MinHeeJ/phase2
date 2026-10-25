import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { UploadDialogs } from "./UploadDialogs";
const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let months: { month: string; status: string }[];
let rejected: boolean;
const close = vi.fn(),
  applied = vi.fn();
beforeEach(() => {
  months = [
    { month: "2026-11", status: "OPEN" },
    { month: "2026-09", status: "CLOSED" },
    { month: "2026-10", status: "OPEN" },
  ];
  rejected = false;
  close.mockReset();
  applied.mockReset();
  fetcher = vi.fn(async (url: string) => {
    if (url.startsWith("/api/month-close")) return ok(months);
    if (url === "/api/uploads/101")
      return ok({
        upload_id: 101,
        form_type: "B.1",
        month: "2026-10",
        row_count: 1,
        error_count: rejected ? 1 : 0,
        warning_count: 0,
        can_apply: !rejected,
        existing_salary_count: 3,
        overwrite_notice:
          "2026-10 급여 3명분이 이미 있습니다. 반영하면 새 파일로 모두 바뀝니다.",
      });
    if (url.endsWith("/validate") || url.endsWith("/revalidate"))
      return ok({
        upload_id: 101,
        form_type: "B.1",
        month: "2026-10",
        row_count: 1,
        error_count: rejected ? 1 : 0,
        warning_count: 0,
        can_apply: !rejected,
        overwrite_notice:
          "2026-10 급여 3명분이 이미 있습니다. 반영하면 새 파일로 모두 바뀝니다.",
      });
    if (url.includes("/issues?"))
      return ok({
        items: rejected
          ? [
              {
                row: 6,
                column: "주 직무",
                severity: "ERROR",
                message: "주 직무가 비어 있습니다.",
                raw_value: "",
                mapping_allowed: false,
              },
            ]
          : [],
        total: rejected ? 1 : 0,
        page: 0,
        size: 100,
      });
    if (url.includes("/rows?"))
      return ok({ items: [], total: 0, page: 0, size: 50 });
    if (url.endsWith("/apply")) return ok({ upload_id: 101, applied_count: 1 });
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = (form = "B.1") =>
  render(<UploadDialogs form={form} onClose={close} onApplied={applied} />);
const choose = () =>
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [new File(["xlsx"], "급여.xlsx")] },
  });
describe("personnel upload dialogs", () => {
  it("uses earliest OPEN and requires file and month", async () => {
    mount();
    await waitFor(() =>
      expect(screen.getByLabelText("연월")).toHaveValue("2026-10"),
    );
    expect(
      screen.getByText(
        "같은 연월을 다시 올리면 그 달 급여를 모두 새 파일로 바꿉니다. 마감한 달은 올릴 수 없습니다.",
      ),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "올리기" })).toBeDisabled();
    choose();
    expect(screen.getByRole("button", { name: "올리기" })).toBeEnabled();
    fireEvent.change(screen.getByLabelText("연월"), { target: { value: "" } });
    expect(screen.getByRole("button", { name: "올리기" })).toBeDisabled();
  });
  it("does not invent a month when no OPEN month exists", async () => {
    months = [];
    mount();
    await waitFor(() => expect(screen.getByLabelText("연월")).toBeEnabled());
    choose();
    expect(screen.getByLabelText("연월")).toHaveValue("");
    expect(screen.getByRole("button", { name: "올리기" })).toBeDisabled();
  });
  it("blocks closed month with exact notice", async () => {
    mount();
    await waitFor(() => expect(screen.getByLabelText("연월")).toBeEnabled());
    choose();
    fireEvent.change(screen.getByLabelText("연월"), {
      target: { value: "2026-09" },
    });
    expect(
      screen.getByText("2026-09은 마감된 달이라 올릴 수 없습니다."),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "올리기" })).toBeDisabled();
  });
  it("cancel closes without sending upload", async () => {
    mount();
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(close).toHaveBeenCalledOnce();
    expect(
      fetcher.mock.calls.some(([url]) => String(url).endsWith("/validate")),
    ).toBe(false);
  });
  it("sends multipart month, shows replacement warning and applies numeric review", async () => {
    mount();
    await waitFor(() =>
      expect(screen.getByLabelText("연월")).toHaveValue("2026-10"),
    );
    choose();
    fireEvent.click(screen.getByRole("button", { name: "올리기" }));
    expect(
      await screen.findByText("엑셀 업로드 결과 · 급여 2026-10"),
    ).toBeVisible();
    expect(screen.getByText(/급여 3명분이 이미/)).toBeVisible();
    const call = fetcher.mock.calls.find(
      ([url]) => url === "/api/uploads/validate",
    )!;
    const body = (call[1] as RequestInit).body as FormData;
    expect(body.get("month")).toBe("2026-10");
    expect(body.get("form_type")).toBe("B.1");
    fireEvent.click(screen.getByRole("button", { name: "반영" }));
    await waitFor(() =>
      expect(applied).toHaveBeenCalledWith({ upldId: "101", appliedRows: 1 }),
    );
  });
  it("developer errors preserve row column reason and block apply", async () => {
    rejected = true;
    mount("B.8");
    choose();
    fireEvent.click(screen.getByRole("button", { name: "올리기" }));
    expect(await screen.findByText("엑셀 업로드 결과 · 개발자")).toBeVisible();
    expect(screen.getByText("주 직무가 비어 있습니다.")).toBeVisible();
    expect(screen.getByRole("button", { name: "반영" })).toBeDisabled();
    expect(screen.getByRole("tab", { name: "정상" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "다시 검사" }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) => url === "/api/uploads/101/revalidate",
        ),
      ).toBe(true),
    );
  });
  it("failed month read leaves upload disabled and displays error", async () => {
    fetcher.mockRejectedValue(new Error("offline"));
    mount();
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    choose();
    expect(screen.getByRole("button", { name: "올리기" })).toBeDisabled();
  });
  it("mobile public phase does not expose mutation dialog", () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => ({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    );
    mount("B.8");
    expect(screen.queryByLabelText("파일 선택")).not.toBeInTheDocument();
  });
});
