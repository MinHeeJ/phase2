import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import UI32RenegotiationsScreen from "./UI32RenegotiationsScreen";
import { type RenegotiationRow } from "./client";
import { developerFixture, response } from "../developers/DeveloperTestSupport";
import tables from "../developers/Developers.module.css";

let fetcher: ReturnType<typeof vi.fn>;
let rows: RenegotiationRow[];
let failed: number | null;
let closed: boolean;
let warnings: string[];
let total: number;
const row: RenegotiationRow = {
  asgn_id: 17,
  dev_id: 7,
  dev_nm: "협상 개발자",
  emp_se_cd: "FT",
  prj_id: 31,
  prj_nm: "유지보수",
  end_dt: "2026-11-17",
  days_until_end: 19,
  mm_qty: 1,
  bill_untprc_amt: 100,
  lbr_cst_amt: 90,
  drct_expns_amt: 0,
  monthly_cost_amt: 90,
  margin_rt: 10,
  target_price_amt: 10000,
  required_raise_rt: 12.5,
  annual_profit_increase_amt: 60,
  target_annual_profit_increase_amt: 150,
};
const requests = () =>
  fetcher.mock.calls.filter(([url]) =>
    String(url).startsWith("/api/costs/renegotiations?"),
  );
const queries = () =>
  requests().map(
    ([url]) => new URL(String(url), "https://test.invalid").searchParams,
  );
function Location() {
  const location = useLocation();
  return (
    <output aria-label="이동 주소">
      {location.pathname} {JSON.stringify(location.state)}
    </output>
  );
}
const mount = () =>
  render(
    <MemoryRouter initialEntries={["/unregistered"]}>
      <Location />
      <UI32RenegotiationsScreen />
    </MemoryRouter>,
  );
beforeEach(() => {
  rows = [{ ...row }];
  failed = null;
  closed = false;
  warnings = [];
  total = 1;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/costs/renegotiations?")) {
      if (failed)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: failed === 409 ? "CLOSED_MONTH" : "VALIDATION_ERROR",
            message:
              failed === 409 ? "마감 배분자료 없음" : "조회 조건을 확인하세요.",
            field: "raiseRate",
            requestId: "renegotiation-request",
          }),
          { status: failed },
        );
      const query = new URL(url, "https://test.invalid").searchParams;
      const rate = Number(query.get("raiseRate"));
      if (
        init?.headers &&
        new Headers(init.headers).get("Accept")?.includes("spreadsheetml")
      )
        return new Response(new Blob([new Uint8Array([80, 75])]), {
          headers: {
            "Content-Type":
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "Content-Disposition":
              "attachment; filename*=UTF-8''renegotiations.xlsx",
          },
        });
      return response({
        month: "2026-10",
        referenceMonth: "2026-10",
        unit: "KRW",
        status: closed ? "CLOSED" : "OPEN",
        warnings,
        items: rows.map((r) => ({
          ...r,
          annual_profit_increase_amt: r.mm_qty ? rate * 12 : null,
        })),
        total,
        page: Number(query.get("page")),
        size: Number(query.get("size")),
        summary: {
          people_count: rows.length ? 1 : 0,
          margin_rt: rows.length ? 10 : null,
          annual_profit_increase_amt: rate * 12,
          target_annual_profit_increase_amt: 150,
        },
      });
    }
    if (url === "/api/projects/options")
      return response([
        { id: 31, no: "P-2631", name: "유지보수", type: "MO" },
        { id: 32, name: "턴키", type: "TK" },
      ]);
    if (url === "/api/codes/EMP_TYPE")
      return response([
        { code: "FT", name: "정규직" },
        { code: "FL", name: "프리랜서" },
      ]);
    if (url === "/api/developers/7")
      return response({
        ...developerFixture,
        dev_id: 7,
        dev_nm: "협상 개발자",
      });
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("unregistered renegotiation business screen", () => {
  it("renders the reference regions with API data and exact shared table classes without changing the reserved route", async () => {
    mount();
    const result = await screen.findByRole("table", {
      name: "단가 재협상 대상",
    });
    expect(result).toHaveClass(tables.table);
    expect(result.parentElement).toHaveClass(tables.tableScroll);
    expect(
      screen.getByRole("table", { name: "재협상 계산 방법", hidden: true }),
    ).toHaveClass(tables.table);
    expect(
      screen.getByRole("heading", { name: "단가 재협상 대상" }),
    ).toBeInTheDocument();
    expect(screen.getByText("화면 경로 승인 대기")).toBeInTheDocument();
    expect(screen.getByText(/실제 계약 보장 아님/)).toBeInTheDocument();
    expect(
      screen.getByTestId("renegotiations-summary-people"),
    ).toHaveTextContent("1명");
    expect(
      screen.getByTestId("renegotiations-summary-margin"),
    ).toHaveTextContent("10.0%");
    expect(screen.getByRole("button", { name: "엑셀 다운로드" })).toBeEnabled();
    expect(
      screen.queryByRole("button", { name: "열 설정" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("이동 주소")).toHaveTextContent(
      "/unregistered",
    );
    const query = queries()[0];
    expect(query.get("windowMonths")).toBe("6");
    expect(query.get("marginBelow")).toBe("15");
    expect(query.get("raiseRate")).toBe("5");
    expect(query.get("page")).toBe("0");
    expect(query.get("size")).toBe("50");
  });
  it("recalculates rows and all summaries when the assumption changes, with GET only", async () => {
    mount();
    await screen.findByTestId("renegotiations-row-17");
    fireEvent.change(screen.getByLabelText("인상률 %"), {
      target: { value: "10" },
    });
    await waitFor(() =>
      expect(
        screen.getByTestId("renegotiations-summary-assumed"),
      ).toHaveTextContent("120"),
    );
    expect(
      within(screen.getByTestId("renegotiations-row-17")).getByText("120"),
    ).toBeInTheDocument();
    expect(queries().at(-1)?.get("raiseRate")).toBe("10");
    expect(
      fetcher.mock.calls.every(
        ([, init]) => !init?.method || init.method === "GET",
      ),
    ).toBe(true);
  });
  it.each(["-1", "51", ""])(
    "rejects invalid assumption %s without sending a request or losing the value",
    async (value) => {
      mount();
      await screen.findByTestId("renegotiations-row-17");
      const before = requests().length;
      fireEvent.change(screen.getByLabelText("인상률 %"), {
        target: { value },
      });
      expect(
        screen.getByText("인상률은 0~50%로 입력하세요."),
      ).toBeInTheDocument();
      expect(screen.getByTestId("renegotiations-search")).toBeDisabled();
      expect(screen.getByTestId("renegotiations-download")).toBeDisabled();
      expect(requests()).toHaveLength(before);
      expect(screen.getByLabelText("인상률 %")).toHaveValue(
        value === "" ? null : Number(value),
      );
    },
  );
  it.each(["0", "50"])("accepts assumption boundary %s", async (value) => {
    mount();
    await screen.findByTestId("renegotiations-row-17");
    fireEvent.change(screen.getByLabelText("인상률 %"), { target: { value } });
    await waitFor(() => expect(queries().at(-1)?.get("raiseRate")).toBe(value));
    expect(screen.getByLabelText("인상률 %")).not.toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });
  it("sends applied population filters, excludes unsupported project options and resets all filters", async () => {
    mount();
    await screen.findByRole("option", { name: "유지보수" });
    expect(
      screen.queryByRole("option", { name: "턴키" }),
    ).not.toBeInTheDocument();
    fireEvent.change(screen.getByTestId("renegotiations-window"), {
      target: { value: "3" },
    });
    fireEvent.change(screen.getByTestId("renegotiations-margin"), {
      target: { value: "20" },
    });
    fireEvent.change(screen.getByTestId("renegotiations-employee"), {
      target: { value: "FT" },
    });
    fireEvent.change(screen.getByTestId("renegotiations-project"), {
      target: { value: "31" },
    });
    fireEvent.click(screen.getByTestId("renegotiations-search"));
    await waitFor(() => expect(queries().at(-1)?.get("projectId")).toBe("31"));
    expect(queries().at(-1)?.get("windowMonths")).toBe("3");
    expect(queries().at(-1)?.get("marginBelow")).toBe("20");
    expect(queries().at(-1)?.get("empType")).toBe("FT");
    fireEvent.click(screen.getByTestId("renegotiations-reset"));
    await waitFor(() => expect(queries().at(-1)?.has("projectId")).toBe(false));
    expect(screen.getByTestId("renegotiations-window")).toHaveValue("6");
  });
  it("sorts and translates the shared one-based pagination to public zero-based page", async () => {
    total = 101;
    mount();
    await screen.findByTestId("renegotiations-row-17");
    fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));
    await waitFor(() => expect(queries().at(-1)?.get("page")).toBe("1"));
    fireEvent.click(screen.getByTestId("renegotiations-sort-margin-rt"));
    await waitFor(() =>
      expect(queries().at(-1)?.get("sort")).toBe("margin_rt,asc"),
    );
    expect(queries().at(-1)?.get("page")).toBe("0");
    fireEvent.change(screen.getByLabelText("목록 건수"), {
      target: { value: "20" },
    });
    await waitFor(() => expect(queries().at(-1)?.get("size")).toBe("20"));
  });
  it("shows missing values and warnings, never fabricating a ratio", async () => {
    rows = [
      {
        ...row,
        mm_qty: null,
        monthly_cost_amt: null,
        margin_rt: null,
        target_price_amt: null,
        required_raise_rt: null,
      },
    ];
    warnings = ["월 배분원가 없음"];
    mount();
    const item = await screen.findByTestId("renegotiations-row-17");
    expect(within(item).getAllByText("–").length).toBeGreaterThanOrEqual(4);
    expect(screen.getByText("월 배분원가 없음")).toBeInTheDocument();
  });
  it("uses distinct deadline warning and danger badges", async () => {
    rows.push({ ...row, asgn_id: 18, days_until_end: 49 });
    mount();
    await screen.findByTestId("renegotiations-row-18");
    expect(screen.getByText("D-19").className).not.toBe(
      screen.getByText("D-49").className,
    );
  });
  it("shows an empty state and unavailable weighted average", async () => {
    rows = [];
    total = 0;
    mount();
    await screen.findByText("조회 결과가 없습니다.");
    expect(
      screen.getByTestId("renegotiations-summary-margin"),
    ).toHaveTextContent("–");
  });
  it("shows a loading state while the query is pending", () => {
    fetcher.mockImplementation(() => new Promise(() => {}));
    mount();
    expect(
      screen.queryByRole("table", { name: "단가 재협상 대상" }),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("renegotiations-download")).toBeDisabled();
  });
  it.each([400, 409])(
    "retains assumption and permits retry after HTTP %s without stale result rows",
    async (status) => {
      failed = status;
      mount();
      await screen.findByText(
        status === 409 ? "마감 배분자료 없음" : "조회 조건을 확인하세요.",
      );
      expect(
        screen.getByText("문의 번호 renegotiation-request"),
      ).toBeInTheDocument();
      expect(
        screen.queryByTestId("renegotiations-row-17"),
      ).not.toBeInTheDocument();
      expect(screen.getByLabelText("인상률 %")).toHaveValue(5);
      failed = null;
      fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
      await screen.findByTestId("renegotiations-row-17");
    },
  );
  it("keeps closed data read-only while still allowing nonpersistent assumption queries", async () => {
    closed = true;
    mount();
    await screen.findByTestId("renegotiations-row-17");
    expect(screen.getByText("마감", { exact: true })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("인상률 %"), {
      target: { value: "20" },
    });
    await waitFor(() => expect(queries().at(-1)?.get("raiseRate")).toBe("20"));
    expect(
      fetcher.mock.calls.every(
        ([, init]) => !init?.method || init.method === "GET",
      ),
    ).toBe(true);
  });
  it("opens the existing developer detail drawer using the selected real developer id", async () => {
    mount();
    await screen.findByTestId("renegotiations-row-17");
    fireEvent.click(screen.getByTestId("renegotiations-developer-17"));
    const dialog = await screen.findByRole("dialog", { name: "개발자 상세" });
    await waitFor(() =>
      expect(within(dialog).getByRole("heading")).toHaveTextContent(
        "협상 개발자",
      ),
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/developers/7",
      expect.anything(),
    );
    fireEvent.click(within(dialog).getByLabelText("상세 닫기"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("hands the selected assignment to the existing FR-049 change workflow, not developer registration", async () => {
    mount();
    await screen.findByTestId("renegotiations-row-17");
    fireEvent.click(screen.getByTestId("renegotiations-price-change-17"));
    expect(screen.getByLabelText("이동 주소")).toHaveTextContent(
      '/UI-12 {"assignmentId":17}',
    );
  });
  it("downloads through the negotiated API using all applied filters but no paging", async () => {
    const create = vi.fn().mockReturnValue("blob:test");
    const revoke = vi.fn();
    vi.stubGlobal(
      "URL",
      class extends URL {
        static createObjectURL = create;
        static revokeObjectURL = revoke;
      },
    );
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    mount();
    await screen.findByTestId("renegotiations-row-17");
    fireEvent.click(screen.getByTestId("renegotiations-download"));
    await waitFor(() => expect(create).toHaveBeenCalled());
    const request = requests().at(-1)!;
    expect(new Headers(request[1]?.headers).get("Accept")).toContain(
      "spreadsheetml",
    );
    expect(queries().at(-1)?.has("page")).toBe(false);
    expect(queries().at(-1)?.has("size")).toBe(false);
    expect(queries().at(-1)?.get("windowMonths")).toBe("6");
    expect(revoke).toHaveBeenCalledWith("blob:test");
  });
});
