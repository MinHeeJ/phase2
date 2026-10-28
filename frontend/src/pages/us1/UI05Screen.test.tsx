import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, useLocation } from "react-router-dom";
import UI05Screen from "./UI05Screen";
import {
  withdrawalDefaults,
  withdrawalQuery,
  type WithdrawalRow,
} from "./client";
import styles from "./Withdrawals.module.css";
import controls from "../../shared/Controls.module.css";

const response = (data: unknown) =>
  new Response(
    JSON.stringify({ success: true, meta: { today: "2026-10-14" }, data }),
    { status: 200, headers: { "Content-Type": "application/json" } },
  );
let fetcher: ReturnType<typeof vi.fn>,
  row: WithdrawalRow,
  empty: boolean,
  failRead: boolean,
  total: number;
function Location() {
  const location = useLocation();
  return (
    <output data-testid="location">
      {location.pathname}
      {location.search} {JSON.stringify(location.state)}
    </output>
  );
}
function mount(props: Parameters<typeof UI05Screen>[0] = {}) {
  return render(
    <MemoryRouter>
      <UI05Screen {...props} />
      <Location />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-14T00:00:00Z"));
  empty = false;
  failRead = false;
  total = 1;
  row = {
    asgn_id: 7,
    asgn_no: "A-0007",
    dev_id: 2,
    dev_nm: "개발자",
    mng_no: "K-0002",
    prj_id: 3,
    prj_nm: "업무 프로젝트",
    job_cd: "BE",
    job_nm: "백엔드 개발",
    bgn_dt: "2026-10-01",
    end_dt: "2026-11-20",
    plan_end_dt: "2026-10-31",
    ext_psblty_cd: null,
    ext_psblty_nm: "미정",
    asgn_stts_cd: "ACTIVE",
    updated_at: "2026-10-14T00:00:00.123456",
    extend_count: 1,
    early_end: true,
    replacement_name: null,
    remaining_days: 37,
    dispatch_first_dt: null,
    dispatch_expiry_dt: null,
    dispatch_display: "–",
    dispatch_tone: "neutral",
    dispatch_alert_dates: [],
    next_asgn_id: null,
    next_prj_nm: null,
    next_bgn_dt: null,
    next_display: "없음",
    chance_locked: false,
    chance_lock_reason: null,
    cntrct_ty_cd: "MO",
    prj_stts_cd: "RUN",
    use_yn: "Y",
  };
  fetcher = vi.fn(async (url: string) => {
    if (url.startsWith("/api/codes/"))
      return response(
        url.endsWith("JOB")
          ? [{ code: "BE", name: "백엔드 개발" }]
          : [
              { code: "H", name: "높음" },
              { code: "M", name: "보통" },
              { code: "L", name: "낮음" },
            ],
      );
    if (failRead) throw new Error("offline");
    return response({
      rows: empty ? [] : [row],
      total: empty ? 0 : total,
      page: 0,
      size: 50,
      summary: { soonPeople: 3, noNextPeople: 1 },
    });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
describe("SCR-UI-05 철수 조회", () => {
  it("renders reference columns, global summary, effective history and warning day badge", async () => {
    mount();
    const item = await screen.findByTestId("withdrawal-row-7");
    expect(
      screen.getByRole("heading", { name: "철수 리스트" }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("withdrawal-summary")).toHaveTextContent(
      "3개월 안 철수 3명 중 연장 가능성이 높지 않고 다음 배정도 없는 사람 1명입니다.",
    );
    expect(screen.getByTestId("withdrawal-summary")).toHaveClass(
      styles.warning,
    );
    expect(within(item).getByText("D-37")).toHaveClass(controls.warning);
    expect(within(item).getByText("처음 계획 2026-10-31")).toBeInTheDocument();
    expect(within(item).getByText("연장 1회")).toBeInTheDocument();
    expect(within(item).getByText("조기 철수 이력")).toBeInTheDocument();
    expect(screen.getByText("50건씩", { exact: false })).toBeInTheDocument();
    expect(screen.queryByText(/추천|가배정|바꾸기/)).not.toBeInTheDocument();
  });
  it.each([
    [0, "D-0", "danger"],
    [61, "D-61", "neutral"],
    [-1, "철수 완료", "locked"],
  ])(
    "renders remaining %s with its distinct tone",
    async (days, text, tone) => {
      row.remaining_days = Number(days);
      mount();
      expect(await screen.findByText(String(text))).toHaveClass(
        controls[String(tone)],
      );
    },
  );
  it("shows replacement and next assignment display without inventing a person destination", async () => {
    row.asgn_stts_cd = "REPLACED";
    row.replacement_name = "후임자";
    row.next_display = "다음 프로젝트 · 진행 중";
    mount();
    expect(await screen.findByText("교체 → 후임자")).toHaveClass(
      controls.locked,
    );
    expect(screen.getByText("다음 프로젝트 · 진행 중")).toBeInTheDocument();
    fireEvent.click(screen.getByText("개발자", { exact: true }));
    expect(screen.getByTestId("location")).toHaveTextContent("/ null");
  });
  it("applies draft filters only on query and keeps all predicates in server request", async () => {
    mount();
    await screen.findByTestId("withdrawal-row-7");
    const before = fetcher.mock.calls.filter(([url]) =>
      url.startsWith("/api/withdrawals?"),
    ).length;
    fireEvent.change(screen.getByTestId("withdrawal-period"), {
      target: { value: "SOON" },
    });
    fireEvent.change(screen.getByTestId("withdrawal-ext"), {
      target: { value: "NONE" },
    });
    fireEvent.change(screen.getByTestId("withdrawal-job"), {
      target: { value: "BE" },
    });
    fireEvent.change(screen.getByTestId("withdrawal-search"), {
      target: { value: "K-" },
    });
    fireEvent.click(screen.getByTestId("withdrawal-dispatch-filter"));
    expect(
      fetcher.mock.calls.filter(([url]) => url.startsWith("/api/withdrawals?"))
        .length,
    ).toBe(before);
    fireEvent.click(screen.getByTestId("withdrawal-query"));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("period=SOON"),
        expect.anything(),
      ),
    );
    const url = fetcher.mock.calls
      .filter(([url]) => url.startsWith("/api/withdrawals?"))
      .at(-1)![0];
    const query = new URL(url, "https://test.invalid").searchParams;
    expect(Object.fromEntries(query)).toMatchObject({
      year: "2026",
      period: "SOON",
      ext: "NONE",
      job: "BE",
      q: "K-",
      dispatchWithin60: "true",
      size: "50",
      page: "0",
    });
  });
  it("shows empty result and resets conditions through its action", async () => {
    empty = true;
    mount();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeInTheDocument();
    fireEvent.change(screen.getByTestId("withdrawal-search"), {
      target: { value: "찾기" },
    });
    fireEvent.click(screen.getByTestId("withdrawal-empty-reset"));
    expect(screen.getByTestId("withdrawal-search")).toHaveValue("");
  });
  it("shows failed request and retry recovers real read state", async () => {
    failRead = true;
    mount();
    expect(
      await screen.findByText("서버에 연결할 수 없습니다."),
    ).toBeInTheDocument();
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByTestId("withdrawal-row-7")).toBeInTheDocument();
  });
  it("opens existing assignment detail with selected path identity in location state", async () => {
    mount();
    fireEvent.click(await screen.findByTestId("withdrawal-row-7"));
    expect(screen.getByTestId("location")).toHaveTextContent(
      '/UI-12 {"assignmentId":7}',
    );
  });
  it("project link stops row propagation and uses existing detail query", async () => {
    mount();
    fireEvent.click(await screen.findByTestId("withdrawal-project-7"));
    expect(screen.getByTestId("location")).toHaveTextContent(
      "/UI-01?open=3 null",
    );
  });
  it("historical year hides global summary and exposes historical lock to chance adapter", async () => {
    sessionStorage.setItem("UI-05.year", "2025");
    mount({
      renderChance: (r, context) => (
        <select
          data-testid="chance-adapter"
          disabled={
            context.year < Number(context.today.slice(0, 4)) || r.chance_locked
          }
        >
          <option>미정</option>
        </select>
      ),
    });
    expect(await screen.findByTestId("chance-adapter")).toBeDisabled();
    expect(screen.getByText("지난 해 · 읽기 전용")).toBeInTheDocument();
    expect(screen.queryByTestId("withdrawal-summary")).not.toBeInTheDocument();
  });
  it("sort and pagination send zero-based page and fixed fifty", async () => {
    total = 70;
    mount();
    await screen.findByTestId("withdrawal-row-7");
    fireEvent.click(screen.getByTestId("withdrawal-page-next"));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("page=1"),
        expect.anything(),
      ),
    );
    await screen.findByTestId("withdrawal-row-7");
    fireEvent.click(screen.getByTestId("withdrawal-sort-end-dt"));
    await waitFor(() => {
      const url = fetcher.mock.calls
        .filter(([url]) => url.startsWith("/api/withdrawals?"))
        .at(-1)![0];
      expect(
        new URL(url, "https://test.invalid").searchParams.get("page"),
      ).toBe("0");
    });
  });
  it("export query excludes pagination but preserves filters and sorting", () => {
    const query = withdrawalQuery(
      {
        ...withdrawalDefaults(2026),
        page: 4,
        ext: "NONE",
        period: "SOON",
        q: "이름",
        dispatchWithin60: true,
      },
      true,
    );
    expect(query.has("page")).toBe(false);
    expect(query.has("size")).toBe(false);
    expect(query.get("ext")).toBe("NONE");
    expect(query.get("sort")).toBe("end_dt,asc;dev_nm,asc");
    expect(query.get("period")).toBe("SOON");
    expect(query.get("q")).toBe("이름");
    expect(query.get("dispatchWithin60")).toBe("true");
  });
});
