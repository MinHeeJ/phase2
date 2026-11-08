import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { setPermissions } from "../../shared/types";
import { AccessHistoryPanel } from "./AccessHistoryPanel";

const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let createObjectURL: ReturnType<typeof vi.fn>;
let revokeObjectURL: ReturnType<typeof vi.fn>;
let clicked: HTMLAnchorElement[];
beforeEach(() => {
  setPermissions({ "UI-23.read": true, "UI-23.download": true });
  createObjectURL = vi.fn(() => "blob:access-history-test");
  revokeObjectURL = vi.fn();
  vi.stubGlobal(
    "URL",
    class extends URL {
      static createObjectURL = createObjectURL;
      static revokeObjectURL = revokeObjectURL;
    },
  );
  clicked = [];
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    clicked.push(this);
  });
  fetcher = vi.fn(async (path: string) => {
    if (path.startsWith("/api/admin/access-history/export?"))
      return new Response(new Blob(["workbook"]));
    if (path.startsWith("/api/admin/access-history?"))
      return ok({
        items: [
          {
            accessHistoryId: 57,
            userId: 71,
            name: "시험 사용자",
            type: "DOWNLOAD",
            result: "SUCCESS",
            screenId: "UI-23",
            target: "내려받기",
            ipAddress: "127.0.0.1",
            createdAt: "2026-11-08T10:00:00",
          },
        ],
        total: 21,
        page: 1,
        size: 20,
      });
    throw new Error(`Unexpected request: ${path}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  setPermissions({});
});

it("renders audit records and exports the applied filters without submitting draft edits or resetting pagination", async () => {
  render(<AccessHistoryPanel />);
  expect(await screen.findByText("시험 사용자")).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("사용자 ID"), {
    target: { value: "71" },
  });
  fireEvent.change(screen.getByLabelText("시작일"), {
    target: { value: "2026-11-01" },
  });
  fireEvent.change(screen.getByLabelText("종료일"), {
    target: { value: "2026-11-08" },
  });
  fireEvent.change(screen.getByLabelText("작업 유형"), {
    target: { value: "DOWNLOAD" },
  });
  fireEvent.click(screen.getByRole("button", { name: "검색" }));
  await waitFor(() =>
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining("type=DOWNLOAD"),
      expect.anything(),
    ),
  );
  fireEvent.click(screen.getByRole("button", { name: "다음" }));
  await waitFor(() =>
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining("page=2"),
      expect.anything(),
    ),
  );
  fireEvent.change(screen.getByLabelText("작업 유형"), {
    target: { value: "LOGIN" },
  });
  const before = fetcher.mock.calls.length;
  fireEvent.click(screen.getByRole("button", { name: "내려받기" }));
  await waitFor(() => expect(clicked).toHaveLength(1));
  const calls = fetcher.mock.calls.slice(before);
  expect(calls).toHaveLength(1);
  const [path, init] = calls[0];
  const params = new URLSearchParams(path.split("?")[1]);
  expect(path).toContain("/api/admin/access-history/export?");
  expect(Object.fromEntries(params)).toMatchObject({
    userId: "71",
    from: "2026-11-01",
    to: "2026-11-08",
    type: "DOWNLOAD",
    page: "2",
  });
  expect(init).toEqual({ credentials: "same-origin" });
  expect(clicked[0]).toHaveAttribute("download", "access-history.xlsx");
  expect(revokeObjectURL).toHaveBeenCalledWith("blob:access-history-test");
});

it("read-only administrator cannot export", async () => {
  setPermissions({ "UI-23.read": true });
  render(<AccessHistoryPanel />);
  await screen.findByText("시험 사용자");
  expect(
    screen.queryByRole("button", { name: "내려받기" }),
  ).not.toBeInTheDocument();
});

it.each([
  [401, "session-expired"],
  [403, "permissions-changed"],
] as const)(
  "propagates download %s and never creates a file",
  async (status, event) => {
    const listener = vi.fn();
    window.addEventListener(event, listener);
    fetcher.mockImplementation(async (path: string) =>
      path.includes("/export?")
        ? new Response(
            JSON.stringify({
              success: false,
              message: "접근할 수 없습니다.",
              requestId: "audit-test",
            }),
            { status, headers: { "Content-Type": "application/json" } },
          )
        : ok({ items: [], total: 0, page: 1, size: 20 }),
    );
    try {
      render(<AccessHistoryPanel />);
      await screen.findByText("접근 기록이 없습니다.");
      fireEvent.click(screen.getByRole("button", { name: "내려받기" }));
      await waitFor(() => expect(listener).toHaveBeenCalledOnce());
      expect(
        await screen.findByText("접근할 수 없습니다."),
      ).toBeInTheDocument();
      expect(createObjectURL).not.toHaveBeenCalled();
      expect(clicked).toHaveLength(0);
    } finally {
      window.removeEventListener(event, listener);
    }
  },
);
