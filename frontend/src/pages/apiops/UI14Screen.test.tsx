import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, useLocation } from "react-router-dom";
import UI14Screen from "../closing/UI14Screen";
import { PeriodBar } from "../shared/PeriodBar";
import type { Closing } from "./closingClient";
import styles from "./Closing.module.css";

const codes = [
  ["SALARY_UPLOAD", "급여 업로드"],
  ["MM_OVER", "MM 1.0 초과자"],
  ["DISPATCH_ATT", "파견 근태"],
  ["BILL_UNCONFIRMED", "청구 미확정"],
  ["TAX_INVOICE_NO", "세금계산서 번호 누락"],
  ["FIN_ENTRY", "재무 항목"],
  ["PART_SETTLE", "파트 정산"],
  ["PAYMENT", "지급 미처리"],
  ["BILL_MISSING", "청구 누락"],
].map(([code, name]) => ({
  code,
  name,
  attr1: null,
  attr2: null,
  attr3: null,
}));
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let rows: Record<string, Closing>;
let fetcher: ReturnType<typeof vi.fn>;
let failRead = false;
function current(): Closing {
  return {
    month: "2026-10",
    status: "OPEN",
    canReopen: false,
    checkedAt: "2026-10-28T09:30:00+09:00",
    checks: codes.map((code, index) => ({
      code: code.code,
      status: index === 0 ? "FAIL" : index === 1 ? "WARN" : "OK",
      count: index === 1 ? 2 : 0,
      description: "저장 설명 " + code.name,
    })),
  };
}
beforeEach(() => {
  sessionStorage.clear();
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-29T00:00:00Z"));
  failRead = false;
  rows = {
    "2026-10": current(),
    "2026-11": {
      month: "2026-11",
      status: "OPEN",
      checks: [],
      canReopen: false,
    },
  };
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/codes/CLOSE_CHECK") return response(codes);
    if (url.startsWith("/api/month-close?")) return response([]);
    if (url.startsWith("/api/closing?")) {
      if (failRead)
        return new Response(
          JSON.stringify({
            success: false,
            code: "MISSING_CLOSED_SNAPSHOT",
            message: "마감 스냅샷 누락",
            requestId: "closing-read",
          }),
          { status: 409 },
        );
      const month = new URLSearchParams(url.split("?")[1]).get("month")!;
      return response(
        rows[month] ?? { month, status: "OPEN", checks: [], canReopen: false },
      );
    }
    throw new Error(`Unexpected URL ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
function Location() {
  const location = useLocation();
  return (
    <output data-testid="destination">
      {JSON.stringify({ path: location.pathname, state: location.state })}
    </output>
  );
}
async function open() {
  render(
    <MemoryRouter initialEntries={["/UI-14"]}>
      <UI14Screen />
      <Location />
    </MemoryRouter>,
  );
  await screen.findByText("저장 설명 급여 업로드", { exact: false });
}
describe("UI-14 stored monthly closing", () => {
  it("renders all nine database code labels, stored statuses, counts and timestamp without running checks", async () => {
    await open();
    expect(
      screen.getByRole("heading", { name: "월 마감" }),
    ).toBeInTheDocument();
    for (const code of codes)
      expect(screen.getByText(code.name)).toBeInTheDocument();
    expect(
      screen.getByText("최근 점검: 2026-10-28T09:30:00+09:00"),
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId("closing-check-salary-upload")).getByText(
        "미완료",
      ),
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId("closing-check-mm-over")).getByText(
        "확인 필요",
      ),
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId("closing-check-dispatch-att")).getByText(
        "정상",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("WARN 1")).toBeInTheDocument();
    expect(screen.getByText("FAIL 1")).toBeInTheDocument();
    expect(screen.getByText(/2건 · 저장 설명 MM/)).toBeInTheDocument();
    expect(
      screen.getByTestId("closing-check-mm-over").parentElement,
    ).toHaveClass(styles.checks);
    expect(
      fetcher.mock.calls.every(
        ([, init]) => !init?.method || init.method === "GET",
      ),
    ).toBe(true);
  });
  it("switches month and shows unchecked rather than fabricated OK, remembering selection", async () => {
    await open();
    fireEvent.change(screen.getByLabelText("조회월"), {
      target: { value: "2026-11" },
    });
    await screen.findByText(
      "아직 점검하지 않았습니다. 다시 점검을 실행해 주세요.",
    );
    expect(screen.getAllByText("미점검")).toHaveLength(9);
    expect(screen.getByText("최근 점검: 점검 미실행")).toBeInTheDocument();
    expect(screen.queryByText(/2026-10-28T/)).not.toBeInTheDocument();
    expect(sessionStorage.getItem("monthly-closing-month")).toBe("2026-11");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/closing?month=2026-11",
      expect.anything(),
    );
  });
  it("clears stale stored results while the changed month is loading", async () => {
    await open();
    const original = fetcher.getMockImplementation()!;
    let complete: (value: Response) => void = () => {};
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      url === "/api/closing?month=2026-11"
        ? new Promise<Response>((resolve) => {
            complete = resolve;
          })
        : original(url, init),
    );
    fireEvent.change(screen.getByLabelText("조회월"), {
      target: { value: "2026-11" },
    });
    await screen.findByLabelText("마감 점검 불러오는 중");
    expect(screen.queryByText(/저장 설명 급여/)).not.toBeInTheDocument();
    complete(response(rows["2026-11"]));
    await screen.findByText("최근 점검: 점검 미실행");
  });
  it("shows business error and retry without treating failed read as OPEN or empty success", async () => {
    failRead = true;
    render(
      <MemoryRouter>
        <UI14Screen />
      </MemoryRouter>,
    );
    await screen.findByText("마감 스냅샷 누락");
    expect(screen.queryByText("미점검")).not.toBeInTheDocument();
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText(/최근 점검: 2026-10/);
  });
  it("uses nullable actor fallback and closed read-only banner", async () => {
    rows["2026-10"] = {
      ...current(),
      status: "CLOSED",
      processedAt: "2026-11-01T09:00:00+09:00",
    };
    await open();
    expect(screen.getByText(/시스템 처리가 마감했습니다/)).toBeInTheDocument();
    expect(screen.getByText(/가장 최근에 마감한 달만/)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "마감하기" }),
    ).not.toBeInTheDocument();
  });
  it("allows mobile read access and keeps this read component free of write controls", async () => {
    window.innerWidth = 375;
    await open();
    expect(
      screen.getByRole("heading", { name: "마감 점검" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("PC에서 이용해 주세요")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "마감하기" }),
    ).not.toBeInTheDocument();
  });
  it("navigates to the existing screen with selected closingMonth state and no API parameter invention", async () => {
    await open();
    fireEvent.click(screen.getByRole("button", { name: "재무 항목 확인하기" }));
    await waitFor(() =>
      expect(screen.getByTestId("destination")).toHaveTextContent(
        '"path":"/UI-10"',
      ),
    );
    expect(screen.getByTestId("destination")).toHaveTextContent(
      '"closingMonth":"2026-10"',
    );
  });
  it("refreshes saved results without issuing a mutation", async () => {
    await open();
    rows["2026-10"] = { ...current(), checkedAt: "2026-10-29T10:00:00+09:00" };
    fireEvent.click(screen.getByRole("button", { name: "새로고침" }));
    await screen.findByText("최근 점검: 2026-10-29T10:00:00+09:00");
  });
  it("supports validated incoming selected month", async () => {
    render(
      <MemoryRouter
        initialEntries={[
          { pathname: "/UI-14", state: { closingMonth: "2026-11" } },
        ]}
      >
        <UI14Screen />
      </MemoryRouter>,
    );
    await screen.findByText("최근 점검: 점검 미실행");
    expect(screen.getByLabelText("조회월")).toHaveValue("2026-11");
  });
  it("preserves uncontrolled period callers", async () => {
    render(<PeriodBar screenId="UI-19" />);
    expect(screen.getByLabelText("조회월")).toHaveValue("2026-10");
    fireEvent.click(screen.getByRole("button", { name: "다음 월" }));
    await waitFor(() =>
      expect(screen.getByLabelText("조회월")).toHaveValue("2026-11"),
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/month-close?from=2026-11&to=2026-11",
      expect.anything(),
    );
  });
});
