import {
  act,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getCodeOptions,
  getEffectiveParam,
  getMenus,
  getMonthClose,
  monthStatus,
} from "./api";
import { useCodes } from "./useCodes";
import { ErrorBanner } from "../../shared/ErrorBanner";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
afterEach(() => vi.unstubAllGlobals());
describe("shared HTTP clients", () => {
  it("reads menu arrays without double envelopes", async () => {
    const fetcher = vi.fn().mockResolvedValue(response([]));
    vi.stubGlobal("fetch", fetcher);
    expect(await getMenus()).toEqual([]);
    expect(fetcher.mock.calls[0][0]).toBe("/api/menus");
  });
  it("encodes selected group and retains attributes and zero strings", async () => {
    const rows = [
      { code: "BE", name: "백엔드", attr1: "0", attr2: null, attr3: null },
    ];
    const fetcher = vi.fn().mockResolvedValue(response(rows));
    vi.stubGlobal("fetch", fetcher);
    expect(await getCodeOptions("JOB /")).toEqual(rows);
    expect(fetcher.mock.calls[0][0]).toBe("/api/codes/JOB%20%2F");
  });
  it("sends sparse complete month boundaries and infers only absent months open", async () => {
    const rows = [{ month: "2026-09", status: "CLOSED" as const }];
    const fetcher = vi.fn().mockResolvedValue(response(rows));
    vi.stubGlobal("fetch", fetcher);
    expect(await getMonthClose("0001-01", "9999-12")).toEqual(rows);
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/month-close?from=0001-01&to=9999-12",
    );
    expect(monthStatus(rows, "2026-09")).toBe("CLOSED");
    expect(monthStatus(rows, "2026-10")).toBe("OPEN");
    expect(rows).toHaveLength(1);
  });
  it("requests effective row using selected identity and month", async () => {
    const row = {
      oprt_std_id: "VAT_RATE",
      aply_bgn_ym: "2000-01",
      num_val: 0,
      txt_val: null,
    };
    const fetcher = vi.fn().mockResolvedValue(response(row));
    vi.stubGlobal("fetch", fetcher);
    expect(await getEffectiveParam("VAT_RATE", "2026-10")).toEqual(row);
    expect(fetcher.mock.calls[0][0]).toBe("/api/params/VAT_RATE?month=2026-10");
  });
  it("preserves effective-row not-found and inquiry identity", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "NOT_FOUND",
            message: "기준값 없음",
            requestId: "server-id",
          }),
          { status: 404 },
        ),
      ),
    );
    await expect(getEffectiveParam("UNKNOWN", "2026-10")).rejects.toMatchObject(
      { status: 404, requestId: "server-id" },
    );
  });
});
describe("useCodes is the single active-option loader", () => {
  it("does not request absent groups, loads selection changes, and exposes empty state", async () => {
    const fetcher = vi.fn().mockResolvedValue(response([]));
    vi.stubGlobal("fetch", fetcher);
    const { result, rerender } = renderHook(
      ({ group }: { group: string | null }) => useCodes(group),
      { initialProps: { group: null as string | null } },
    );
    expect(result.current.codes).toEqual([]);
    expect(fetcher).not.toHaveBeenCalled();
    rerender({ group: "JOB" });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(fetcher.mock.calls[0][0]).toBe("/api/codes/JOB");
    expect(result.current.codes).toEqual([]);
  });
  it("does not allow stale previous group results to populate the new group", async () => {
    let finish: (response: Response) => void = () => {};
    const fetcher = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve;
          }),
      )
      .mockResolvedValueOnce(
        response([
          {
            code: "TL",
            name: "팀장",
            attr1: "200000",
            attr2: null,
            attr3: null,
          },
        ]),
      );
    vi.stubGlobal("fetch", fetcher);
    const { result, rerender } = renderHook(({ group }) => useCodes(group), {
      initialProps: { group: "JOB" },
    });
    rerender({ group: "POSITION" });
    await waitFor(() => expect(result.current.codes[0]?.code).toBe("TL"));
    await act(async () => finish(response([{ code: "BE" }])));
    expect(result.current.codes[0].code).toBe("TL");
  });
  it("shows read failure, retries only the selected read and retains unrelated draft", async () => {
    const fetcher = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("offline"))
      .mockResolvedValueOnce(response([]));
    vi.stubGlobal("fetch", fetcher);
    function Options() {
      const { error, retry, loading } = useCodes("JOB");
      return (
        <>
          <input aria-label="메모" defaultValue="보존" />
          <ErrorBanner error={error} onRetry={retry} pending={loading} />
        </>
      );
    }
    render(<Options />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "문의 번호 없음 (서버 응답 없음)",
    );
    fireEvent.change(screen.getByLabelText("메모"), {
      target: { value: "편집 유지" },
    });
    fireEvent.click(screen.getByText("다시 시도"));
    await waitFor(() =>
      expect(screen.queryByRole("alert")).not.toBeInTheDocument(),
    );
    expect(screen.getByLabelText("메모")).toHaveValue("편집 유지");
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
