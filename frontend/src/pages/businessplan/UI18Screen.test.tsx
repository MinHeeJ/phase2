import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI18Screen from "./UI18Screen";
import tables from "../developers/Developers.module.css";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let closed: string[],
  failRead: boolean,
  failWrite: boolean,
  items: Record<string, unknown>[];
const metric = (target: number | null, actual: number | null) => ({
  target,
  actual,
  difference: target == null || actual == null ? null : actual - target,
  achievement_rate:
    target == null || actual == null || target === 0 ? null : 65,
});
beforeEach(() => {
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-30T00:00:00Z"));
  closed = [];
  failRead = false;
  failWrite = false;
  items = [
    {
      year: 2026,
      month: 10,
      partId: 7,
      target_revenue: 100,
      target_operating_profit: 10,
      target_utilization_rate: 80,
    },
  ];
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (failRead)
      return new Response(
        JSON.stringify({
          success: false,
          message: "조회 실패",
          requestId: "read-business",
        }),
        { status: 500 },
      );
    if (init?.method === "PUT") {
      if (failWrite)
        return new Response(
          JSON.stringify({
            success: false,
            message: "마감 월은 저장할 수 없습니다.",
            requestId: "write-business",
            code: "CLOSED_MONTH",
          }),
          { status: 409 },
        );
      const input = JSON.parse(String(init.body));
      items = input.items;
      return response({
        items,
        parts: [{ id: 7, name: "영업파트", active: true }],
        closed_months: closed,
        year: 2026,
      });
    }
    if (url.includes("/comparison?"))
      return response({
        items,
        actuals: [],
        warning: null,
        warnings: ["파트 영업이익 원천 자료 없음"],
        comparison: {
          months: Array.from({ length: 12 }, (_, i) => ({
            month: i + 1,
            target_present: i === 9,
            revenue: metric(i === 9 ? 100 : null, i === 9 ? 65 : null),
            operating_profit: metric(i === 9 ? 10 : null, null),
            utilization_rate: metric(i === 9 ? 80 : null, i === 9 ? 70 : null),
          })),
          cumulative: {
            revenue: metric(100, 65),
            operating_profit: metric(10, null),
            utilization_rate: metric(80, 70),
          },
        },
        export_file: {
          file_name: "사업계획.xlsx",
          content_type:
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          content_base64: "UEs=",
        },
      });
    return response({
      items,
      parts: [{ id: 7, name: "영업파트", active: true }],
      closed_months: closed,
      year: 2026,
    });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
const open = async () => {
  render(
    <MemoryRouter>
      <UI18Screen />
    </MemoryRouter>,
  );
  await screen.findByRole("table", { name: "사업계획 월별 비교" });
};
it("renders comparison with UI-02 table styles and source warning", async () => {
  await open();
  expect(screen.getByRole("table", { name: "사업계획 월별 비교" })).toHaveClass(
    tables.table,
  );
  expect(screen.getByText("파트 영업이익 원천 자료 없음")).toBeInTheDocument();
  expect(screen.getByTestId("business-plan-monthly-chart")).toBeInTheDocument();
  expect(fetcher).toHaveBeenCalledWith(
    "/api/finance/business-plan/comparison?year=2026&month=10",
    expect.anything(),
  );
});
it("saves explicit month and part targets then refreshes", async () => {
  await open();
  fireEvent.click(screen.getByRole("button", { name: "목표 입력" }));
  fireEvent.change(screen.getByLabelText("목표매출"), {
    target: { value: "200" },
  });
  fireEvent.click(screen.getByRole("button", { name: "저장" }));
  await screen.findByText("사업계획 목표를 저장했습니다.");
  expect(fetcher).toHaveBeenCalledWith(
    "/api/finance/business-plan?year=2026",
    expect.objectContaining({
      method: "PUT",
      body: JSON.stringify({
        items: [
          {
            year: 2026,
            month: 10,
            partId: 7,
            target_revenue: 200,
            target_operating_profit: 10,
            target_utilization_rate: 80,
          },
        ],
      }),
    }),
  );
});
it("closed target disables save without changing it", async () => {
  closed = ["2026-10"];
  await open();
  fireEvent.click(screen.getByRole("button", { name: "목표 입력" }));
  expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
  expect(screen.getByLabelText("목표매출")).toBeDisabled();
});
it("past year is read-only", async () => {
  sessionStorage.setItem("business-plan-period", "2025-10");
  await open();
  expect(
    screen.queryByRole("button", { name: "목표 입력" }),
  ).not.toBeInTheDocument();
  expect(screen.getByText(/지난 연도는 조회/)).toBeInTheDocument();
});
it("invalid input is rejected locally", async () => {
  await open();
  fireEvent.click(screen.getByText("목표 입력"));
  fireEvent.change(screen.getByLabelText("목표매출"), {
    target: { value: "1.5" },
  });
  fireEvent.click(screen.getByRole("button", { name: "저장" }));
  await screen.findByText("목표매출은 0 이상의 원 단위 정수로 입력하세요.");
  expect(fetcher.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(
    false,
  );
});
it("write error keeps entered target", async () => {
  failWrite = true;
  await open();
  fireEvent.click(screen.getByText("목표 입력"));
  fireEvent.change(screen.getByLabelText("목표매출"), {
    target: { value: "250" },
  });
  fireEvent.click(screen.getByRole("button", { name: "저장" }));
  await screen.findByText("문의 번호 write-business");
  expect(screen.getByLabelText("목표매출")).toHaveValue("250");
});
it("failed read can retry without losing the selected period", async () => {
  failRead = true;
  render(
    <MemoryRouter>
      <UI18Screen />
    </MemoryRouter>,
  );
  await screen.findByText("문의 번호 read-business");
  failRead = false;
  fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
  await screen.findByRole("table", { name: "사업계획 월별 비교" });
  expect(screen.getByLabelText("누계 기준월")).toHaveValue("10");
});
it("empty goals distinguish missing input from zero", async () => {
  items = [];
  await open();
  expect(
    screen.getByText("등록된 목표가 없습니다. 목표를 입력해 주세요."),
  ).toBeInTheDocument();
  expect(screen.getAllByText("미입력").length).toBeGreaterThan(0);
});
it("part filter uses the approved query only", async () => {
  await open();
  fireEvent.change(screen.getByLabelText("파트"), { target: { value: "7" } });
  await waitFor(() =>
    expect(fetcher).toHaveBeenCalledWith(
      "/api/finance/business-plan/comparison?year=2026&month=10&partId=7",
      expect.anything(),
    ),
  );
});
it("upload integration receives the screen year and refresh callback", async () => {
  const upload = vi.fn();
  render(
    <MemoryRouter>
      <UI18Screen onUpload={upload} />
    </MemoryRouter>,
  );
  await screen.findByRole("table", { name: "사업계획 월별 비교" });
  fireEvent.click(screen.getByText("목표 업로드"));
  expect(upload).toHaveBeenCalledWith(2026, expect.any(Function));
});
it("loading prevents save and download before source data arrives", () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(() => new Promise(() => {})),
  );
  render(
    <MemoryRouter>
      <UI18Screen />
    </MemoryRouter>,
  );
  expect(
    screen.getByRole("status", { name: "불러오는 중" }),
  ).toBeInTheDocument();
  expect(screen.getByText("목표 입력")).toBeDisabled();
  expect(screen.getByText("엑셀 다운로드")).toBeDisabled();
});
it("future target month stays editable independently of future actuals", async () => {
  await open();
  fireEvent.click(screen.getByText("목표 입력"));
  fireEvent.change(screen.getByLabelText("목표 월"), {
    target: { value: "12" },
  });
  expect(screen.getByLabelText("목표매출")).not.toBeDisabled();
  fireEvent.change(screen.getByLabelText("목표매출"), {
    target: { value: "0" },
  });
  fireEvent.change(screen.getByLabelText("목표영업이익"), {
    target: { value: "-100" },
  });
  fireEvent.change(screen.getByLabelText("목표가동률"), {
    target: { value: "80" },
  });
  fireEvent.click(screen.getByText("저장"));
  await screen.findByText("사업계획 목표를 저장했습니다.");
  expect(fetcher).toHaveBeenCalledWith(
    "/api/finance/business-plan?year=2026",
    expect.objectContaining({
      method: "PUT",
      body: JSON.stringify({
        items: [
          {
            year: 2026,
            month: 12,
            partId: 7,
            target_revenue: 0,
            target_operating_profit: -100,
            target_utilization_rate: 80,
          },
        ],
      }),
    }),
  );
});
