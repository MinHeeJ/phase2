import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PartnerDrawer } from "../partners/PartnerDrawer";
import { emptyPartner } from "../partners/PartnerForm";
import type {
  PartnerDetail,
  PartnerAssignment,
  PartnerChange,
} from "../partners/client";

const customer: PartnerDetail = {
  ...emptyPartner,
  ptnr_id: "41",
  ptnr_no: "C-041",
  ptnr_se_cd: "CUST",
  ptnr_nm: "고객사 A",
  biz_reg_no: "123-45-67890",
  ceo_nm: "대표자",
  indty_cd: "SI",
  chrg_nm: "담당자",
  chrg_tel_no: "010-1234-5678",
  txinv_email_addr: "tax@company.co.kr",
  rmrk_desc: "계약 비고",
  use_yn: "Y",
};
const vendor: PartnerDetail = {
  ...customer,
  ptnr_se_cd: "VEND",
  ptnr_nm: "협력사 B",
  txinv_email_addr: null,
  bank_cd: "004",
  acnt_no: "001-23456789",
  dpstr_nm: "협력사 B",
};
const assignments: PartnerAssignment[] = [
  {
    projectName: "가 프로젝트",
    developerName: "가 개발자",
    affiliation: "협력사 B",
    jobCode: "BE",
    jobName: "백엔드",
    assignmentRate: 1,
    endDate: "2026-12-31",
  },
  {
    projectName: "나 프로젝트",
    developerName: "나 개발자",
    affiliation: "자사",
    jobCode: "BE",
    jobName: "백엔드",
    assignmentRate: 0.5,
    endDate: "2026-11-30",
  },
];
const changes: PartnerChange[] = [
  {
    changedAt: "2026-10-23T10:30:00",
    column: "acnt_no",
    itemName: "계좌번호",
    before: "7890",
    after: "3210",
    changeType: "UPDATE",
    actorName: "기본 사용자",
  },
  {
    changedAt: "2026-10-22T09:00:00",
    column: "ptnr_nm",
    itemName: "상호",
    before: "이전 상호",
    after: "협력사 B",
    changeType: "UPDATE",
    actorName: "기본 사용자",
  },
];
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failed = () =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code: "SERVER_ERROR",
      message: "조회에 실패했습니다.",
      requestId: "detail-request",
    }),
    { status: 500 },
  );
let row: PartnerDetail;
let assignmentRows: PartnerAssignment[];
let historyRows: PartnerChange[];
let fetcher: ReturnType<typeof vi.fn>;
const stateAction = vi.fn();
beforeEach(() => {
  row = { ...customer };
  assignmentRows = [];
  historyRows = [];
  stateAction.mockReset();
  fetcher = vi.fn(async (url: string) => {
    if (url.startsWith("/api/codes/"))
      return response(
        url.endsWith("PARTNER_KIND")
          ? [
              { code: "CUST", name: "고객사" },
              { code: "VEND", name: "협력사" },
              { code: "BOTH", name: "둘 다" },
            ]
          : url.endsWith("BANK")
            ? [{ code: "004", name: "국민은행" }]
            : [{ code: "SI", name: "시스템 통합" }],
      );
    if (url === `/api/partners/${row.ptnr_id}/history`)
      return response(historyRows);
    if (url === `/api/partners/${row.ptnr_id}/assignments`)
      return response(assignmentRows);
    if (url === `/api/partners/${row.ptnr_id}`) return response(row);
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function drawer(initialTab = "basic") {
  return render(
    <PartnerDrawer
      id={row.ptnr_id}
      initialTab={initialTab}
      onClose={() => {}}
      onSaved={async () => {}}
      onStateAction={stateAction}
    />,
  );
}
async function loaded() {
  await screen.findByText("C-041");
}
function tab(name: string | RegExp) {
  fireEvent.click(screen.getByRole("tab", { name }));
}
function failOnce(path: string) {
  const original = fetcher.getMockImplementation()!;
  let fail = true;
  fetcher.mockImplementation(async (url: string) => {
    if (url === path && fail) {
      fail = false;
      return failed();
    }
    return original(url);
  });
}

describe("SCR-UI-15 partner detail", () => {
  it("renders the header, three tabs and every applicable registration field", async () => {
    drawer();
    await loaded();
    expect(screen.getByRole("dialog", { name: "거래처 상세" })).toHaveAttribute(
      "data-screen-id",
      "SCR-UI-15",
    );
    expect(screen.getByRole("heading", { name: "고객사 A" })).toBeVisible();
    expect(screen.getByText("고객사 · 123-45-67890")).toBeVisible();
    expect(screen.getByRole("button", { name: "수정" })).toBeVisible();
    expect(screen.getAllByRole("tab")).toHaveLength(3);
    const panel = screen.getByTestId("partner-basic-panel");
    for (const value of [
      "C-041",
      "대표자",
      "시스템 통합",
      "담당자",
      "010-1234-5678",
      "tax@company.co.kr",
      "계약 비고",
      "사용",
    ])
      expect(within(panel).getAllByText(value).length).toBeGreaterThan(0);
    expect(within(panel).queryByText("지급 계좌")).not.toBeInTheDocument();
    expect(
      screen.queryByText(/사용을 중지한 거래처입니다/),
    ).not.toBeInTheDocument();
  });
  it("places the stopped warning first and omits vendor email", async () => {
    row = { ...vendor, use_yn: "N" };
    drawer();
    await loaded();
    const dialog = screen.getByRole("dialog");
    expect(dialog.firstElementChild).toHaveTextContent(
      "사용을 중지한 거래처입니다. 새 프로젝트·지급에 고를 수 없습니다.",
    );
    expect(screen.queryByText("세금계산서 이메일")).not.toBeInTheDocument();
    expect(
      screen.getByText("국민은행 001-23456789 / 협력사 B"),
    ).toHaveAttribute("data-sensitive", "true");
    expect(screen.getByText("중지")).toBeVisible();
  });
  it("shows both email and complete payment for BOTH", async () => {
    row = {
      ...vendor,
      ptnr_se_cd: "BOTH",
      txinv_email_addr: customer.txinv_email_addr,
    };
    drawer();
    await loaded();
    expect(screen.getByText("tax@company.co.kr")).toBeVisible();
    expect(screen.getByText("국민은행 001-23456789 / 협력사 B")).toBeVisible();
    expect(screen.getByText("둘 다").className).toContain("neutral");
  });
  it("shows missing optional values without invented data", async () => {
    row = {
      ...customer,
      ceo_nm: null,
      indty_cd: null,
      chrg_nm: null,
      chrg_tel_no: null,
      rmrk_desc: null,
    };
    drawer();
    await loaded();
    expect(
      within(screen.getByTestId("partner-basic-panel")).getAllByText("–"),
    ).toHaveLength(5);
  });
  it("opens history from more in the same drawer and displays latest rows", async () => {
    historyRows = changes;
    drawer();
    await loaded();
    fireEvent.click(screen.getByRole("button", { name: "더보기" }));
    expect(screen.getByRole("button", { name: "더보기" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    fireEvent.click(screen.getByRole("menuitem", { name: "변경 이력 보기" }));
    expect(screen.getByRole("tab", { name: "변경 이력 (2)" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
    const panel = screen.getByTestId("partner-history-panel");
    expect(
      within(panel)
        .getAllByRole("columnheader")
        .map((c) => c.textContent),
    ).toEqual(["일시", "항목", "바꾸기 전", "바꾼 뒤", "처리자"]);
    expect(within(panel).getAllByRole("row")[1]).toHaveTextContent(
      "2026-10-23 10:30계좌번호78903210기본 사용자",
    );
    expect(within(panel).getAllByRole("row")[2]).toHaveTextContent("이전 상호");
  });
  it.each(["Y", "N"] as const)(
    "delegates %s state action only after menu selection",
    async (use) => {
      row = { ...customer, use_yn: use };
      drawer();
      await loaded();
      fireEvent.click(screen.getByRole("button", { name: "더보기" }));
      expect(stateAction).not.toHaveBeenCalled();
      fireEvent.click(
        screen.getByRole("menuitem", {
          name: use === "Y" ? "사용 중지" : "다시 사용",
        }),
      );
      expect(stateAction).toHaveBeenCalledWith(row);
      expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    },
  );
  it("retains edit mode in the same dialog with immutable business number", async () => {
    drawer();
    await loaded();
    fireEvent.click(screen.getByRole("button", { name: "수정" }));
    expect(await screen.findByLabelText("사업자번호")).toHaveAttribute(
      "readonly",
    );
    expect(screen.getByText("수정 중 · 123-45-67890")).toBeVisible();
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
  });
  it("renders API assignment columns, affiliation and rates", async () => {
    assignmentRows = assignments;
    drawer();
    await loaded();
    tab("투입 내역");
    const first = await screen.findByText("가 프로젝트");
    const panel = screen.getByTestId("partner-assignments-panel");
    expect(first).toBeVisible();
    expect(
      within(panel)
        .getAllByRole("columnheader")
        .map((c) => c.textContent),
    ).toEqual(["프로젝트", "이름", "소속", "직무", "투입률", "철수일"]);
    expect(within(panel).getAllByRole("row")[1]).toHaveTextContent(
      "가 프로젝트가 개발자협력사 B백엔드100.0%2026-12-31",
    );
    expect(within(panel).getAllByRole("row")[2]).toHaveTextContent(
      "나 프로젝트나 개발자자사백엔드50.0%2026-11-30",
    );
    expect(fetcher).toHaveBeenCalledWith(
      `/api/partners/${row.ptnr_id}/assignments`,
      expect.anything(),
    );
  });
  it("supports opening the assignments tab from a list count", async () => {
    drawer("assignments");
    expect(
      await screen.findByText("지금 투입 중인 내역이 없습니다."),
    ).toBeVisible();
    expect(screen.getByRole("tab", { name: "투입 내역" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });
  it("uses the specified empty history message", async () => {
    drawer();
    await loaded();
    tab(/변경 이력/);
    expect(await screen.findByText("아직 바꾼 기록이 없습니다.")).toBeVisible();
    expect(screen.getByRole("tab", { name: "변경 이력 (0)" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });
  it.each(["assignments", "history"])(
    "shows %s failure without an empty success and retries",
    async (path) => {
      failOnce(`/api/partners/${row.ptnr_id}/${path}`);
      if (path === "assignments") assignmentRows = assignments;
      else historyRows = changes;
      drawer();
      await loaded();
      tab(path === "assignments" ? "투입 내역" : /변경 이력/);
      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("문의 번호 detail-request");
      expect(
        screen.queryByText("지금 투입 중인 내역이 없습니다."),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByText("아직 바꾼 기록이 없습니다."),
      ).not.toBeInTheDocument();
      fireEvent.click(within(alert).getByText("다시 시도"));
      expect(
        await screen.findByText(
          path === "assignments" ? "가 프로젝트" : "계좌번호",
        ),
      ).toBeVisible();
    },
  );
  it("recovers detail errors without writing", async () => {
    failOnce(`/api/partners/${row.ptnr_id}`);
    drawer();
    fireEvent.click(
      within(await screen.findByRole("alert")).getByText("다시 시도"),
    );
    await loaded();
    expect(
      fetcher.mock.calls.every((c) => !c[1]?.method || c[1].method === "GET"),
    ).toBe(true);
  });
  it("shows loading rather than a false empty result", async () => {
    const original = fetcher.getMockImplementation()!;
    let resolve!: (value: Response) => void;
    fetcher.mockImplementation((url: string) =>
      url.endsWith("/assignments")
        ? new Promise<Response>((done) => {
            resolve = done;
          })
        : original(url),
    );
    drawer("assignments");
    const panel = await screen.findByTestId("partner-assignments-panel");
    expect(
      within(panel).getByRole("status", { name: "불러오는 중" }),
    ).toBeVisible();
    expect(
      screen.queryByText("지금 투입 중인 내역이 없습니다."),
    ).not.toBeInTheDocument();
    resolve(response([]));
    expect(
      await screen.findByText("지금 투입 중인 내역이 없습니다."),
    ).toBeVisible();
  });
  it("reports a failed code lookup and retries its own group", async () => {
    row = vendor;
    failOnce("/api/codes/BANK");
    drawer();
    await loaded();
    const alert = await screen.findByRole("alert");
    fireEvent.click(within(alert).getByText("다시 시도"));
    expect(
      await screen.findByText("국민은행 001-23456789 / 협력사 B"),
    ).toBeVisible();
    await waitFor(() =>
      expect(screen.queryByRole("alert")).not.toBeInTheDocument(),
    );
  });
});
