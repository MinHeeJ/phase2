import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { IssueOwnerSelect } from "./IssueOwnerSelect";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetcher = vi.fn(async () =>
    response([{ name: "김담당" }, { name: "박피엠" }]),
  );
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.unstubAllGlobals());
function Field({
  projectId = 37,
  initial = null,
  disabled = false,
}: {
  projectId?: number | null;
  initial?: string | null;
  disabled?: boolean;
}) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <IssueOwnerSelect
        projectId={projectId}
        value={value}
        onChange={setValue}
        disabled={disabled}
      />
      <output aria-label="선택값">{value ?? "없음"}</output>
    </>
  );
}
describe("issue owner selection", () => {
  it("requires project selection without making an API call", () => {
    render(<Field projectId={null} />);
    expect(screen.getByLabelText("담당자")).toBeDisabled();
    expect(screen.getAllByText("프로젝트를 먼저 고르세요")).not.toHaveLength(0);
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("loads the selected project's names and reports the exact snapshot", async () => {
    render(<Field />);
    await screen.findByRole("option", { name: "김담당" });
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/issues/owner-options?projectId=37",
    );
    fireEvent.change(screen.getByLabelText("담당자"), {
      target: { value: "name:김담당" },
    });
    expect(screen.getByLabelText("선택값")).toHaveTextContent("김담당");
  });
  it("supports optional direct names with a 50 character limit", async () => {
    render(<Field />);
    await screen.findByRole("option", { name: "김담당" });
    fireEvent.change(screen.getByLabelText("담당자"), {
      target: { value: "direct" },
    });
    const input = screen.getByLabelText("담당자 직접 입력");
    expect(input).toHaveAttribute("maxlength", "50");
    fireEvent.change(input, { target: { value: "가".repeat(50) } });
    expect(screen.getByLabelText("선택값")).toHaveTextContent("가".repeat(50));
    fireEvent.change(input, { target: { value: "나".repeat(51) } });
    expect(input).toHaveValue("가".repeat(50));
    fireEvent.change(input, { target: { value: "" } });
    expect(screen.getByLabelText("선택값")).toHaveTextContent("없음");
  });
  it("preserves an existing name no longer in current workforce", async () => {
    render(<Field initial="이전 담당자" />);
    await screen.findByRole("option", { name: "김담당" });
    expect(screen.getByLabelText("담당자 직접 입력")).toHaveValue(
      "이전 담당자",
    );
  });
  it("offers direct input for an empty result", async () => {
    fetcher.mockResolvedValue(response([]));
    render(<Field />);
    await screen.findByText(
      "선택할 담당자가 없습니다. 직접 입력할 수 있습니다.",
    );
    fireEvent.change(screen.getByLabelText("담당자"), {
      target: { value: "direct" },
    });
    expect(screen.getByLabelText("담당자 직접 입력")).toBeEnabled();
  });
  it("retains direct input on failure and retries the options query", async () => {
    fetcher.mockRejectedValueOnce(new Error("offline"));
    render(<Field initial="직접 담당" />);
    await screen.findByRole("alert");
    expect(screen.getByLabelText("담당자 직접 입력")).toHaveValue("직접 담당");
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByRole("option", { name: "김담당" });
    expect(screen.getByLabelText("담당자 직접 입력")).toHaveValue("직접 담당");
  });
  it("respects the project's read-only state", async () => {
    render(<Field initial="직접 담당" disabled />);
    await screen.findByRole("option", { name: "김담당" });
    expect(screen.getByLabelText("담당자")).toBeDisabled();
    expect(screen.getByLabelText("담당자 직접 입력")).toBeDisabled();
  });
  it("remains writable on mobile independently of the legacy viewport lock", async () => {
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      value: 375,
    });
    render(<Field />);
    await screen.findByRole("option", { name: "김담당" });
    expect(screen.getByLabelText("담당자")).toBeEnabled();
    fireEvent.change(screen.getByLabelText("담당자"), {
      target: { value: "direct" },
    });
    expect(screen.getByLabelText("담당자 직접 입력")).toBeEnabled();
  });
  it("clears stale owner and reloads on project change", async () => {
    const { rerender } = render(<Field projectId={37} initial="김담당" />);
    await screen.findByRole("option", { name: "김담당" });
    fetcher.mockResolvedValue(response([{ name: "새 담당" }]));
    rerender(<Field projectId={38} initial="김담당" />);
    await screen.findByRole("option", { name: "새 담당" });
    expect(screen.getByLabelText("선택값")).toHaveTextContent("없음");
    expect(
      screen.queryByRole("option", { name: "김담당" }),
    ).not.toBeInTheDocument();
  });
  it("ignores a late response for a previously selected project", async () => {
    let resolve!: (response: Response) => void;
    fetcher.mockImplementationOnce(
      () =>
        new Promise<Response>((done) => {
          resolve = done;
        }),
    );
    const { rerender } = render(<Field projectId={37} />);
    fetcher.mockResolvedValue(response([{ name: "새 담당" }]));
    rerender(<Field projectId={38} />);
    await screen.findByRole("option", { name: "새 담당" });
    resolve(response([{ name: "이전 담당" }]));
    await waitFor(() =>
      expect(
        screen.queryByRole("option", { name: "이전 담당" }),
      ).not.toBeInTheDocument(),
    );
  });
  it("shows the contract's field error without dropping the name", async () => {
    render(
      <IssueOwnerSelect
        projectId={37}
        value="직접 담당"
        onChange={vi.fn()}
        error="담당자는 50자 이하로 입력하세요."
      />,
    );
    await screen.findByRole("option", { name: "김담당" });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "담당자는 50자 이하로 입력하세요.",
    );
    expect(screen.getByLabelText("담당자 직접 입력")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });
});
