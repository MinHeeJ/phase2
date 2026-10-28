import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ShortagesPanel } from "./ShortagesPanel";

const row = {
  projectId: 3,
  projectName: "프로젝트",
  job: "BE",
  jobName: "백엔드",
  month: "2026-03",
  planMm: 3,
  actualMm: 2.5,
  shortageMm: 0.5,
};
const reply = (rows: unknown[], total = rows.length) =>
  new Response(
    JSON.stringify({ success: true, data: { rows, total, page: 1, size: 50 } }),
    { status: 200, headers: { "Content-Type": "application/json" } },
  );
const open = vi.fn(),
  reset = vi.fn();
beforeEach(() => {
  open.mockClear();
  reset.mockClear();
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
it("shows monthly shortage and opens its selected project", async () => {
  const fetcher = vi.fn().mockResolvedValue(reply([row]));
  vi.stubGlobal("fetch", fetcher);
  render(
    <ShortagesPanel
      year={2026}
      projectId={3}
      job="BE"
      onReset={reset}
      onOpenProject={open}
    />,
  );
  expect(await screen.findByText("부족 0.50")).toBeTruthy();
  expect(screen.getByText("2026-03")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "프로젝트" }));
  expect(open).toHaveBeenCalledWith(3);
  expect(fetcher.mock.calls[0][0]).toContain("projectId=3&job=BE");
});
it("distinguishes filtered and unfiltered empty results", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation(async () => reply([])),
  );
  const { rerender } = render(
    <ShortagesPanel year={2026} onReset={reset} onOpenProject={open} />,
  );
  expect(await screen.findByText("미충원 직무가 없습니다.")).toBeTruthy();
  rerender(
    <ShortagesPanel
      year={2026}
      job="BE"
      onReset={reset}
      onOpenProject={open}
    />,
  );
  expect(await screen.findByText(/조건에 맞는 결과가 없습니다/)).toBeTruthy();
  fireEvent.click(screen.getByText("조건 초기화"));
  expect(reset).toHaveBeenCalled();
});
it("offers retry after a failed request", async () => {
  const fetcher = vi
    .fn()
    .mockRejectedValueOnce(new Error("offline"))
    .mockResolvedValue(reply([row]));
  vi.stubGlobal("fetch", fetcher);
  render(<ShortagesPanel year={2026} onReset={reset} onOpenProject={open} />);
  expect(await screen.findByRole("alert")).toBeTruthy();
  fireEvent.click(screen.getByText("다시 시도"));
  await waitFor(() => expect(screen.getByText("부족 0.50")).toBeTruthy());
});
