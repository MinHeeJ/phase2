import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getCodeOptions,
  getEffectiveParam,
  getHealth,
  getMenus,
  getMonthClose,
  useCodes,
} from "./sharedApi";
import { ErrorBanner } from "../../shared/ErrorBanner";

const envelope = (data: unknown) =>
  new Response(
    JSON.stringify({
      success: true,
      meta: {},
      data: { success: true, meta: {}, data },
    }),
  );
afterEach(() => vi.unstubAllGlobals());
describe("공유 API 소비 경계", () => {
  it("메뉴와 월마감 배열은 정확히 두 봉투에서 가져온다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(envelope([]))
      .mockResolvedValueOnce(envelope([{ month: "2026-10", status: "OPEN" }]));
    vi.stubGlobal("fetch", fetcher);
    expect(await getMenus()).toEqual([]);
    expect(await getMonthClose("2026-10", "2026-10")).toEqual([
      { month: "2026-10", status: "OPEN" },
    ]);
    expect(fetcher.mock.calls[0][0]).toBe("/api/menus");
    expect(fetcher.mock.calls[1][0]).toBe(
      "/api/month-close?from=2026-10&to=2026-10",
    );
  });
  it("선택한 그룹과 실제 key/month로 상대 URL을 만든다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        envelope([
          { code: "BE", name: "백엔드", attr1: null, attr2: null, attr3: null },
        ]),
      )
      .mockResolvedValueOnce(
        envelope({
          oprt_std_id: "VAT_RATE",
          aply_bgn_ym: "2000-01",
          num_val: 0,
        }),
      );
    vi.stubGlobal("fetch", fetcher);
    expect(await getCodeOptions("JOB")).toHaveLength(1);
    expect(await getEffectiveParam("VAT_RATE", "2026-10")).toMatchObject({
      num_val: 0,
    });
    expect(fetcher.mock.calls[0][0]).toBe("/api/codes/JOB");
    expect(fetcher.mock.calls[1][0]).toBe("/api/params/VAT_RATE?month=2026-10");
  });
  it("health는 단일 봉투를 유지한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify({ success: true, meta: {} })),
        ),
    );
    expect(await getHealth()).toEqual({ success: true, meta: {} });
  });
  it("useCodes는 변경 그룹의 늦은 응답을 무시하고 새 코드만 보여 준다", async () => {
    let resolveFirst!: (response: Response) => void;
    const fetcher = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveFirst = resolve;
          }),
      )
      .mockResolvedValueOnce(
        envelope([
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
    function Options({ group }: { group: string }) {
      const state = useCodes(group);
      return (
        <select aria-label="코드">
          {state.data?.map((item) => (
            <option key={item.code}>{item.name}</option>
          ))}
        </select>
      );
    }
    const view = render(<Options group="JOB" />);
    view.rerender(<Options group="POSITION" />);
    expect(
      await screen.findByRole("option", { name: "팀장" }),
    ).toBeInTheDocument();
    resolveFirst(
      envelope([
        { code: "BE", name: "백엔드", attr1: null, attr2: null, attr3: null },
      ]),
    );
    await waitFor(() =>
      expect(
        screen.queryByRole("option", { name: "백엔드" }),
      ).not.toBeInTheDocument(),
    );
  });
  it("실패한 코드 요청은 헤더 문의 번호와 재시도로 복구한다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "INTERNAL_ERROR",
            message: "처리 중 오류가 발생했습니다.",
          }),
          { status: 500, headers: { "X-Request-Id": "code-1" } },
        ),
      )
      .mockResolvedValueOnce(envelope([]));
    vi.stubGlobal("fetch", fetcher);
    function Options() {
      const state = useCodes("JOB");
      return (
        <>
          <ErrorBanner error={state.error} onRetry={state.retry} />
          {state.data && <p>옵션 {state.data.length}개</p>}
        </>
      );
    }
    render(<Options />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "문의 번호: code-1",
    );
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("옵션 0개")).toBeInTheDocument();
  });
});
