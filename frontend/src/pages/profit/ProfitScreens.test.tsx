import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import UI07Screen from "./UI07Screen";
import UI08Screen from "./UI08Screen";
import UI09Screen from "./UI09Screen";

const file = {
  file_name: "손익.xlsx",
  content_type:
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  content_base64: "UEs=",
};
let fetcher: ReturnType<typeof vi.fn>;
let failed: boolean;
let imported: boolean;
let empty: boolean;
const totals = {
  revenue: 1000,
  cost: 200,
  gross_profit: 800,
  sga: 100,
  operating_profit: 700,
  net_profit: 600,
  operating_margin: 70,
  net_margin: 60,
  warnings: [],
  warning: "",
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
beforeEach(() => {
  sessionStorage.clear();
  failed = false;
  imported = false;
  empty = false;
  sessionStorage.setItem("profit-ui-07-period", "2026-02");
  sessionStorage.setItem("profit-ui-08-period", "2026-02");
  sessionStorage.setItem("profit-ui-09-period", "2026-02");
  fetcher = vi.fn(async (url: string) => {
    if (failed) throw new Error("offline");
    const months = empty
      ? []
      : Array.from({ length: 12 }, (_, i) => ({
          ...totals,
          month: `2026-${String(i + 1).padStart(2, "0")}`,
          closed: i === 0,
          source: imported ? "IMPORT" : "LIVE",
          warning: i === 10 ? "FUTURE_ACTUAL" : "",
          warnings: i === 10 ? ["FUTURE_ACTUAL"] : [],
          revenue: i > 9 ? null : 1000,
          operating_export_file: file,
          net_export_file: file,
          cumulative: totals,
          previous: { ...totals, revenue: 900 },
        }));
    return response(
      url.includes("structure")
        ? {
            ...totals,
            previous: { ...totals, revenue: 900 },
            difference: { revenue: 100 },
            growth_rate: { revenue: 11.1 },
            months,
            composition_available: !imported,
            revenue_by_contract: imported
              ? []
              : [
                  {
                    code: "TK",
                    name: "턴키",
                    revenue: 1000,
                    cost: 200,
                    gross_profit: 800,
                    share: 100,
                    margin: 80,
                  },
                ],
            revenue_by_customer: [],
            revenue_by_part: [],
            cost_breakdown: [],
            export_file: file,
            contribution_difference: 0,
            contribution_note: "프로젝트 귀속 매출총이익",
          }
        : { year: 2026, months, previous_months: [] },
    );
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
it.each([
  ["UI-08", UI08Screen],
  ["UI-09", UI09Screen],
] as const)(
  "%s renders twelve months then cumulative, previous and difference with closed/future states",
  async (_, Component) => {
    render(<Component />);
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    const table = await screen.findByRole("table", {
      name: /영업이익표|순이익표/,
    });
    const headers = within(within(table).getAllByRole("row")[0]).getAllByRole(
      "columnheader",
    );
    expect(headers.slice(-3).map((h) => h.textContent)).toEqual([
      "누계",
      "전년 동기간",
      "차이",
    ]);
    expect(within(table).getByText("마감")).toBeVisible();
    expect(screen.getByText("미래 실적 미산정")).toBeVisible();
    expect(screen.getByTestId("profit-row-revenue")).toHaveTextContent("1,000");
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  },
);
it("structure period changes use only approved query parameters and share the selected scope", async () => {
  render(<UI07Screen />);
  await screen.findByRole("heading", {
    name: "수익 구조 — 매출에서 순이익까지",
  });
  fireEvent.click(screen.getByRole("tab", { name: "분기" }));
  await waitFor(() =>
    expect(
      fetcher.mock.calls.some((c) => c[0].includes("period=QUARTER")),
    ).toBe(true),
  );
  fireEvent.change(screen.getByLabelText("누계 기준월"), {
    target: { value: "3" },
  });
  await waitFor(() =>
    expect(
      fetcher.mock.calls.some(
        (c) => c[0].includes("month=3") && c[0].includes("period=QUARTER"),
      ),
    ).toBe(true),
  );
  expect(
    screen.getByRole("img", { name: "수익 구조 폭포 차트" }),
  ).toBeVisible();
});
it.each([UI07Screen, UI08Screen, UI09Screen])(
  "read failure offers retry rather than empty success",
  async (Component) => {
    failed = true;
    render(<Component />);
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(
      screen.queryByText("조회할 손익 자료가 없습니다."),
    ).not.toBeInTheDocument();
    failed = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await screen.findByTestId("profit-summary");
  },
);
it.each([UI07Screen, UI09Screen])(
  "IMPORT hides fabricated composition",
  async (Component) => {
    imported = true;
    render(<Component />);
    expect(
      await screen.findByText(
        "자료 없음 · 이관 연도는 구성 분석을 제공하지 않습니다.",
      ),
    ).toBeVisible();
    expect(screen.queryByText("턴키")).not.toBeInTheDocument();
  },
);
it.each([UI07Screen, UI08Screen, UI09Screen])(
  "empty success is distinct from failure",
  async (Component) => {
    empty = true;
    render(<Component />);
    expect(
      await screen.findByText("조회할 손익 자료가 없습니다."),
    ).toBeVisible();
  },
);
it("screen periods remain independent", async () => {
  const first = render(<UI08Screen />);
  await screen.findByRole("table", { name: "영업이익표" });
  fireEvent.change(screen.getByLabelText("누계 기준월"), {
    target: { value: "4" },
  });
  first.unmount();
  render(<UI09Screen />);
  await screen.findByRole("table", { name: "순이익표" });
  expect(screen.getByLabelText("누계 기준월")).toHaveValue("2");
});
it("download decodes the selected response payload without an unapproved API", async () => {
  const create = vi.fn(() => "blob:profit"),
    revoke = vi.fn();
  URL.createObjectURL = create;
  URL.revokeObjectURL = revoke;
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  render(<UI08Screen />);
  await screen.findByRole("table");
  fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
  expect(create).toHaveBeenCalledOnce();
  expect(revoke).toHaveBeenCalledWith("blob:profit");
  expect(
    fetcher.mock.calls.every((c) => c[0].includes("/profit/monthly?")),
  ).toBe(true);
});
