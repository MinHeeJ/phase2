import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI15Screen from "../partners/UI15Screen";
import { emptyPartner } from "../partners/PartnerForm";
import type { PartnerDetail, PartnerListItem } from "../partners/client";
import { useSharedPreferences } from "../shared/preferences";

const customer: PartnerDetail = {
  ...emptyPartner,
  ptnr_id: "1",
  ptnr_no: "C-001",
  ptnr_se_cd: "CUST",
  ptnr_nm: "가 거래처",
  biz_reg_no: "123-45-67890",
  txinv_email_addr: "tax@company.co.kr",
  use_yn: "Y",
};
const vendor: PartnerDetail = {
  ...customer,
  ptnr_id: "2",
  ptnr_no: "C-002",
  ptnr_se_cd: "VEND",
  ptnr_nm: "나 거래처",
  biz_reg_no: "222-22-22222",
  txinv_email_addr: null,
  bank_cd: "KB",
  acnt_no: "123-456-7890",
  dpstr_nm: "예금주",
};
function item(row: PartnerDetail): PartnerListItem {
  const {
    bank_cd: _bank,
    acnt_no: account,
    dpstr_nm: _depositor,
    rmrk_desc: _remark,
    ...publicFields
  } = row;
  return {
    ...publicFields,
    kindName:
      row.ptnr_se_cd === "CUST"
        ? "고객사"
        : row.ptnr_se_cd === "VEND"
          ? "협력사"
          : "둘 다",
    industryName: null,
    paymentAccount: account ? "국민은행 …7890" : null,
    projectCount: row.ptnr_se_cd === "VEND" ? null : 2,
    assignmentPeopleCount: row.ptnr_se_cd === "VEND" ? 0 : 5,
  };
}
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let rows: PartnerDetail[];
let total: number | undefined;
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  localStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  rows = [{ ...customer }, { ...vendor }];
  total = undefined;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/codes/"))
      return response(
        url.endsWith("PARTNER_KIND")
          ? [
              { code: "CUST", name: "고객사" },
              { code: "VEND", name: "협력사" },
              { code: "BOTH", name: "둘 다" },
            ]
          : url.endsWith("BANK")
            ? [{ code: "KB", name: "국민은행" }]
            : [],
      );
    if (url === "/api/partners" && init?.method === "POST") {
      const row: PartnerDetail = {
        ...customer,
        ...JSON.parse(init.body as string),
        ptnr_id: "3",
        ptnr_no: "C-003",
      };
      rows.push(row);
      return response(row);
    }
    if (url.endsWith("/assignments") || url.endsWith("/history"))
      return response([]);
    if (url.startsWith("/api/partners?")) {
      const p = new URLSearchParams(url.split("?")[1]);
      const q = p.get("q") ?? "",
        kind = p.get("kind"),
        use = p.get("useYn");
      const filtered = rows.filter(
        (r) =>
          (!kind ||
            r.ptnr_se_cd === kind ||
            (kind !== "BOTH" && r.ptnr_se_cd === "BOTH")) &&
          (!use || r.use_yn === use) &&
          (!q ||
            r.ptnr_nm.includes(q) ||
            r.biz_reg_no.replaceAll("-", "") === q.replaceAll("-", "")),
      );
      return response({
        items: filtered.map(item),
        total: total ?? filtered.length,
        page: Number(p.get("page")),
        size: Number(p.get("size")),
      });
    }
    if (url.startsWith("/api/partners/"))
      return response(rows.find((r) => r.ptnr_id === url.split("/").at(-1)));
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const listCalls = () =>
  fetcher.mock.calls.filter((c) => String(c[0]).startsWith("/api/partners?"));
const latestQuery = () =>
  new URLSearchParams(String(listCalls().at(-1)![0]).split("?")[1]);
async function start() {
  render(<UI15Screen salesParts={<div>영업 파트 업무</div>} />);
  await screen.findByTestId("partner-row-1");
}
async function register() {
  fireEvent.click(screen.getByTestId("partner-create"));
  await screen.findByRole("option", { name: "고객사" });
  change("구분", "CUST");
  change("상호", "신규 거래처");
  change("사업자번호", "3333333333");
  change("세금계산서 이메일", "tax@company.co.kr");
  fireEvent.click(screen.getByRole("button", { name: "저장" }));
  await screen.findByText("저장했습니다.");
}

describe("SCR-UI-15 partner list contract", () => {
  it("keeps the first tab, default query, thirteen columns and title action order", async () => {
    await start();
    expect(screen.getByTestId("partners-screen")).toHaveAttribute(
      "data-screen-id",
      "SCR-UI-15",
    );
    expect(screen.getByRole("tab", { name: "거래처" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(Object.fromEntries(latestQuery())).toEqual({
      kind: "",
      useYn: "Y",
      q: "",
      page: "1",
      size: "50",
      sort: "ptnr_nm,asc",
    });
    expect(
      screen
        .getAllByRole("columnheader")
        .slice(1)
        .map((h) => h.textContent),
    ).toEqual([
      "구분",
      "거래처 코드",
      "상호",
      "사업자번호",
      "대표자",
      "업종",
      "담당자",
      "연락처",
      "세금계산서 이메일",
      "지급 계좌",
      "진행 프로젝트",
      "투입 인력",
      "사용 여부",
    ]);
    const template = screen.getByTestId("partner-template-download"),
      upload = screen.getByTestId("partner-upload"),
      create = screen.getByTestId("partner-create");
    expect(
      template.compareDocumentPosition(upload) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      upload.compareDocumentPosition(create) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });
  it("sends all three AND conditions and renders only the returned vendor/both results", async () => {
    rows.push({
      ...vendor,
      ptnr_id: "4",
      ptnr_nm: "둘 다 거래처",
      ptnr_se_cd: "BOTH",
      use_yn: "N",
    });
    await start();
    change("조회 구분", "VEND");
    change("사용 여부", "N");
    change("상호·사업자번호", "2222222222");
    fireEvent.click(screen.getByTestId("partner-query"));
    await screen.findByTestId("partner-row-4");
    expect(screen.queryByTestId("partner-row-1")).not.toBeInTheDocument();
    expect(screen.queryByTestId("partner-row-2")).not.toBeInTheDocument();
    expect(latestQuery().get("kind")).toBe("VEND");
    expect(latestQuery().get("useYn")).toBe("N");
    expect(latestQuery().get("q")).toBe("2222222222");
  });
  it.each(["가 거", "1234567890"])(
    "submits partial name or normalized business search %s",
    async (q) => {
      await start();
      change("상호·사업자번호", q);
      fireEvent.submit(screen.getByTestId("partner-search-form"));
      await waitFor(() =>
        expect(screen.queryByTestId("partner-row-2")).not.toBeInTheDocument(),
      );
      expect(screen.getByTestId("partner-row-1")).toBeVisible();
      expect(latestQuery().get("q")).toBe(q);
    },
  );
  it("renders code names, missing values, masked payment and aggregate units", async () => {
    await start();
    const c = screen.getByTestId("partner-row-1"),
      v = screen.getByTestId("partner-row-2");
    expect(within(c).getByText("고객사").className).toContain("info");
    expect(within(v).getByText("협력사").className).toContain("neutral");
    expect(within(v).getByText("사용").className).toContain("success");
    expect(within(v).getByText("국민은행 …7890")).toBeVisible();
    expect(within(v).queryByText(vendor.acnt_no!)).not.toBeInTheDocument();
    expect(within(v).queryByText(vendor.dpstr_nm!)).not.toBeInTheDocument();
    expect(
      screen.getByTestId("partner-cell-2-project-count"),
    ).toHaveTextContent("–");
    expect(
      screen.getByTestId("partner-cell-1-industry-name"),
    ).toHaveTextContent("–");
    expect(within(c).getByRole("button", { name: "2건" })).toBeVisible();
    expect(within(c).getByRole("button", { name: "5명" })).toBeVisible();
    expect(screen.queryByTestId("partner-people-2")).not.toBeInTheDocument();
    expect(within(v).getByText("0명").tagName).toBe("TD");
  });
  it("row selection reveals a confirm action without executing disable or opening details", async () => {
    await start();
    fireEvent.click(screen.getByLabelText("가 거래처 선택"));
    expect(screen.getByText("1건 선택")).toBeVisible();
    fireEvent.click(screen.getByTestId("partner-disable-selected"));
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(fetcher.mock.calls.some((c) => c[1]?.method === "POST")).toBe(false);
    expect(screen.queryByTestId("partner-drawer")).not.toBeInTheDocument();
  });
  it("keeps page size and sorting when a new search resets only the page", async () => {
    total = 120;
    await start();
    change("목록 건수", "20");
    fireEvent.click(screen.getByTestId("partner-sort-ptnr-nm"));
    fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));
    await waitFor(() => expect(latestQuery().get("page")).toBe("2"));
    change("상호·사업자번호", "가");
    fireEvent.click(screen.getByTestId("partner-query"));
    await waitFor(() => expect(latestQuery().get("q")).toBe("가"));
    expect(latestQuery().get("size")).toBe("20");
    expect(latestQuery().get("sort")).toBe("ptnr_nm,desc");
    expect(latestQuery().get("page")).toBe("1");
  });
  it.each(["sort", "page", "size", "tab"])(
    "clears selection on %s changes so hidden rows cannot be disabled",
    async (action) => {
      total = 120;
      await start();
      fireEvent.click(screen.getByLabelText("가 거래처 선택"));
      if (action === "sort")
        fireEvent.click(screen.getByTestId("partner-sort-ptnr-nm"));
      if (action === "page")
        fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));
      if (action === "size") change("목록 건수", "100");
      if (action === "tab") {
        fireEvent.click(screen.getByRole("tab", { name: "영업 파트" }));
        fireEvent.click(screen.getByRole("tab", { name: "거래처" }));
      }
      expect(screen.queryByText("1건 선택")).not.toBeInTheDocument();
      expect(screen.getByLabelText("가 거래처 선택")).not.toBeChecked();
    },
  );
  it("empty results offer reset which restores the default query and visible controls", async () => {
    await start();
    change("조회 구분", "VEND");
    change("사용 여부", "N");
    change("상호·사업자번호", "없음");
    fireEvent.click(screen.getByTestId("partner-query"));
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    fireEvent.click(screen.getByRole("button", { name: "조건 초기화" }));
    await screen.findByTestId("partner-row-1");
    expect(screen.getByLabelText("상호·사업자번호")).toHaveValue("");
    expect(screen.getByLabelText("조회 구분")).toHaveValue("");
    expect(screen.getByLabelText("사용 여부")).toHaveValue("Y");
    expect(latestQuery().get("sort")).toBe("ptnr_nm,asc");
    expect(latestQuery().get("size")).toBe("50");
  });
  it.each(["partner-people-1", "partner-projects-1"])(
    "opens assignments from the positive count %s",
    async (id) => {
      await start();
      fireEvent.click(screen.getByTestId(id));
      await screen.findByText("지금 투입 중인 내역이 없습니다.");
      expect(
        fetcher.mock.calls.some((c) => c[0] === "/api/partners/1/assignments"),
      ).toBe(true);
    },
  );
  it("opens basic details from a row", async () => {
    await start();
    fireEvent.click(screen.getByTestId("partner-row-1"));
    const drawer = await screen.findByTestId("partner-drawer");
    expect(await within(drawer).findByText("C-001")).toBeVisible();
    expect(
      within(drawer).getByRole("tab", { name: "기본 정보" }),
    ).toHaveAttribute("aria-selected", "true");
  });
  it("pins the newly re-read row and resets stale search controls and selection after registration", async () => {
    await start();
    change("상호·사업자번호", "가");
    fireEvent.click(screen.getByTestId("partner-query"));
    await waitFor(() => expect(latestQuery().get("q")).toBe("가"));
    fireEvent.click(screen.getByLabelText("가 거래처 선택"));
    await register();
    const row = await screen.findByTestId("partner-row-3");
    expect(row.className).toContain("highlight");
    expect(screen.getAllByTestId(/^partner-row-/)[0]).toBe(row);
    expect(screen.getByLabelText("상호·사업자번호")).toHaveValue("");
    expect(screen.queryByText("1건 선택")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("partner-sort-ptnr-nm"));
    expect(screen.getByTestId("partner-row-3").className).not.toContain(
      "highlight",
    );
  });
  it("ignores a delayed new-row lookup once a different search has been submitted", async () => {
    const original = fetcher.getMockImplementation()!;
    let finish!: (value: Response) => void;
    fetcher.mockImplementation((url: string, init?: RequestInit) => {
      if (
        url.startsWith("/api/partners?") &&
        new URLSearchParams(url.split("?")[1]).get("q") === "333-33-33333"
      )
        return new Promise<Response>((resolve) => {
          finish = resolve;
        });
      return original(url, init);
    });
    await start();
    await register();
    await waitFor(() => expect(finish).toBeTypeOf("function"));
    change("상호·사업자번호", "가");
    fireEvent.click(screen.getByTestId("partner-query"));
    await waitFor(() =>
      expect(screen.queryByTestId("partner-row-3")).not.toBeInTheDocument(),
    );
    await act(async () => {
      finish(
        response({
          items: [item(rows.find((r) => r.ptnr_id === "3")!)],
          total: 1,
          page: 1,
          size: 50,
        }),
      );
    });
    expect(screen.queryByTestId("partner-row-3")).not.toBeInTheDocument();
    expect(screen.getByTestId("partner-row-1")).toBeVisible();
  });
  it("shows a loading state and recovers a failed read without a write", async () => {
    const original = fetcher.getMockImplementation()!;
    let reject!: (reason: Error) => void;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      url.startsWith("/api/partners?")
        ? new Promise<Response>((_resolve, fail) => {
            reject = fail;
          })
        : original(url, init),
    );
    render(<UI15Screen />);
    expect(screen.getByRole("status", { name: "불러오는 중" })).toBeVisible();
    await act(async () => {
      reject(new Error("offline"));
    });
    fetcher.mockImplementation(original);
    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    expect(await screen.findByTestId("partner-row-1")).toBeVisible();
    expect(
      fetcher.mock.calls.some((c) => c[1]?.method && c[1].method !== "GET"),
    ).toBe(false);
  });
});
