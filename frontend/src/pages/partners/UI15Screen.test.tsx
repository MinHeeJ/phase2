import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import UI15Screen from "./UI15Screen";
import { PartnerForm } from "./PartnerForm";
import { PartnerDrawer } from "./PartnerDrawer";
import {
  partnerPayload,
  type PartnerDetail,
  type PartnerListItem,
} from "./client";
import { useSharedPreferences } from "../shared/preferences";

const detail: PartnerDetail = {
  ptnr_id: "1",
  ptnr_no: "C-001",
  ptnr_se_cd: "BOTH",
  ptnr_nm: "가 거래처",
  biz_reg_no: "123-45-67890",
  ceo_nm: "대표",
  indty_cd: "SI",
  chrg_nm: "담당",
  chrg_tel_no: "010-1234-5678",
  rmrk_desc: "메모",
  txinv_email_addr: "tax@company.co.kr",
  bank_cd: "KB",
  acnt_no: "123-456-7890",
  dpstr_nm: "회사",
  use_yn: "Y",
};
const row: PartnerListItem = {
  ptnr_id: "1",
  ptnr_no: "C-001",
  ptnr_se_cd: "BOTH",
  ptnr_nm: "가 거래처",
  biz_reg_no: "123-45-67890",
  ceo_nm: "대표",
  indty_cd: "SI",
  chrg_nm: "담당",
  chrg_tel_no: "010-1234-5678",
  txinv_email_addr: "tax@company.co.kr",
  use_yn: "Y",
  kindName: "둘 다",
  industryName: "SI업",
  paymentAccount: "국민은행 …7890",
  projectCount: 2,
  assignmentPeopleCount: 5,
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failure = (
  field: string | null = null,
  message = "입력 오류",
  status = 400,
) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code: "INVALID_INPUT",
      message,
      field,
      requestId: "server-request",
    }),
    { status },
  );
let fetcher: ReturnType<typeof vi.fn>;
let current: PartnerDetail;
let list: PartnerListItem[];
beforeEach(() => {
  localStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  current = { ...detail };
  list = [{ ...row }];
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/codes/")) {
      const group = url.split("/").pop();
      return response(
        group === "PARTNER_KIND"
          ? [
              { code: "CUST", name: "고객사" },
              { code: "VEND", name: "협력사" },
              { code: "BOTH", name: "둘 다" },
            ]
          : group === "BANK"
            ? [
                { code: "KB", name: "국민은행" },
                { code: "SHINHAN", name: "신한은행" },
              ]
            : [{ code: "SI", name: "SI업" }],
      );
    }
    if (url.startsWith("/api/partners?") && !init?.method) {
      const query = new URLSearchParams(url.split("?")[1]);
      return response({
        items: list,
        total: list.length,
        page: Number(query.get("page")),
        size: Number(query.get("size")),
      });
    }
    if (url.endsWith("/assignments"))
      return response([
        {
          projectName: "진행 프로젝트",
          developerName: "인력",
          affiliation: "자사",
          jobCode: "BE",
          jobName: "백엔드",
          assignmentRate: 1,
          endDate: "2026-10-31",
        },
      ]);
    if (url.endsWith("/history"))
      return response([
        {
          changedAt: "2026-10-22 09:00",
          column: "ptnr_nm",
          itemName: "상호",
          before: "이전",
          after: "가 거래처",
          changeType: "UPDATE",
          actorName: "기본 사용자",
        },
      ]);
    if (init?.method === "PUT" || init?.method === "POST") {
      const payload = JSON.parse(String(init.body));
      current = { ...current, ...payload };
      return response(current);
    }
    if (url === "/api/partners/1") return response(current);
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const writes = () =>
  fetcher.mock.calls.filter(
    ([, init]) => init?.method === "POST" || init?.method === "PUT",
  );
async function readyForm() {
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
  );
}
async function customerForm() {
  render(<PartnerForm onCancel={vi.fn()} onSaved={vi.fn()} />);
  await readyForm();
  change("구분", "CUST");
  change("상호", "새 거래처");
  change("사업자번호", "2234567890");
  change("세금계산서 이메일", "new@company.co.kr");
}

describe("거래처 목록", () => {
  it("opens the first tab and ordered toolbar with 13 safe columns", async () => {
    const upload = vi.fn();
    render(
      <UI15Screen
        groupName="기준정보"
        onUpload={upload}
        salesParts={<p>파트 목록</p>}
      />,
    );
    await screen.findByRole("button", { name: "가 거래처" });
    expect(screen.getByRole("tab", { name: "거래처" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("link", { name: "양식 내려받기" })).toHaveAttribute(
      "href",
      "/api/uploads/templates/B.7",
    );
    fireEvent.click(screen.getByRole("button", { name: "엑셀 업로드" }));
    expect(upload).toHaveBeenCalledOnce();
    expect(screen.getAllByRole("columnheader")).toHaveLength(14);
    expect(screen.getByText("국민은행 …7890")).toBeInTheDocument();
    expect(screen.queryByText("123-456-7890")).not.toBeInTheDocument();
    expect(screen.getByLabelText("구분")).toHaveValue("");
    expect(screen.getByLabelText("사용 여부")).toHaveValue("Y");
    expect(screen.getByLabelText("목록 건수")).toHaveValue("50");
    fireEvent.click(screen.getByRole("tab", { name: "영업 파트" }));
    expect(screen.getByText("파트 목록")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "등록" }),
    ).not.toBeInTheDocument();
  });
  it("applies filters together and server sorts rather than sorting the page", async () => {
    render(<UI15Screen />);
    await screen.findByRole("button", { name: "가 거래처" });
    change("구분", "VEND");
    change("사용 여부", "N");
    change("상호·사업자번호", "1234567890");
    fireEvent.click(screen.getByRole("button", { name: "조회" }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => {
          const q = new URLSearchParams(String(url).split("?")[1]);
          return (
            q.get("kind") === "VEND" &&
            q.get("useYn") === "N" &&
            q.get("q") === "1234567890"
          );
        }),
      ).toBe(true),
    );
    await screen.findByRole("button", { name: "가 거래처" });
    fireEvent.click(screen.getByRole("button", { name: /^상호$/ }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) =>
            new URLSearchParams(String(url).split("?")[1]).get("sort") ===
            "ptnr_nm,desc",
        ),
      ).toBe(true),
    );
    expect(writes()).toHaveLength(0);
  });
  it("shows zero results and reset restores defaults", async () => {
    list = [];
    render(<UI15Screen />);
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    change("구분", "VEND");
    change("상호·사업자번호", "없음");
    fireEvent.click(screen.getByRole("button", { name: "조건 초기화" }));
    expect(screen.getByLabelText("구분")).toHaveValue("");
    expect(screen.getByLabelText("사용 여부")).toHaveValue("Y");
    expect(screen.getByLabelText("상호·사업자번호")).toHaveValue("");
  });
  it("renders unavailable project count and zero people without drilldown links", async () => {
    list = [
      {
        ...row,
        ptnr_se_cd: "VEND",
        kindName: "협력사",
        projectCount: null,
        assignmentPeopleCount: 0,
        paymentAccount: null,
        txinv_email_addr: null,
      },
    ];
    render(<UI15Screen />);
    await screen.findByRole("button", { name: "가 거래처" });
    expect(screen.queryByTestId("partner-projects-1")).not.toBeInTheDocument();
    expect(screen.queryByTestId("partner-people-1")).not.toBeInTheDocument();
    expect(screen.getByText("0명")).toBeInTheDocument();
  });
  it("row name opens basic drawer and positive count opens assignments with 100 percent", async () => {
    render(<UI15Screen />);
    await screen.findByRole("button", { name: "가 거래처" });
    fireEvent.click(screen.getByRole("button", { name: "5명" }));
    await screen.findByText("100%");
    expect(screen.getByRole("tab", { name: "투입 내역" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });
  it("loads and exposes failure with read-only retry", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      url.startsWith("/api/partners?")
        ? failure(null, "조회 실패", 500)
        : original(url, init),
    );
    render(<UI15Screen />);
    await screen.findByText("조회 실패");
    expect(screen.getByText("문의 번호 server-request")).toBeInTheDocument();
    fetcher.mockImplementation(original);
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByRole("button", { name: "가 거래처" });
    expect(writes()).toHaveLength(0);
  });
  it("selection updates count and cancel disable sends no mutation", async () => {
    render(<UI15Screen />);
    await screen.findByRole("button", { name: "가 거래처" });
    fireEvent.click(screen.getByLabelText("가 거래처 선택"));
    expect(screen.getByText("선택: 1건")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "취소" }),
    );
    expect(writes()).toHaveLength(0);
  });
  it("registers a real row then requeries and pins it before the default sort", async () => {
    render(<UI15Screen />);
    await screen.findByRole("button", { name: "가 거래처" });
    fireEvent.click(screen.getByRole("button", { name: "등록" }));
    await readyForm();
    change("구분", "CUST");
    change("상호", "하 신규");
    change("사업자번호", "2234567890");
    change("세금계산서 이메일", "new@company.co.kr");
    const original = fetcher.getMockImplementation()!;
    const added = {
      ...row,
      ptnr_id: "2",
      ptnr_no: "C-002",
      ptnr_se_cd: "CUST" as const,
      ptnr_nm: "하 신규",
      biz_reg_no: "223-45-67890",
    };
    fetcher.mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === "POST") {
        list = [row, added];
        return response({ ...detail, ...added });
      }
      if (url.includes("q=223-45-67890"))
        return response({ items: [added], total: 1, page: 1, size: 50 });
      return original(url, init);
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByText("저장했습니다.");
    await screen.findByRole("button", { name: "하 신규 신규" });
    expect(screen.getAllByTestId(/^partner-row-/)[0]).toHaveAttribute(
      "data-testid",
      "partner-row-2",
    );
    expect(screen.getByTestId("partner-row-2").className).toContain(
      "highlight",
    );
    expect(writes()).toHaveLength(1);
    expect(
      fetcher.mock.calls.some(([url]) =>
        String(url).includes("q=223-45-67890"),
      ),
    ).toBe(true);
  });
});

describe("거래처 등록·수정", () => {
  it("uses API code lists, exact lengths, format and kind specific sections", async () => {
    await customerForm();
    expect(screen.getByLabelText("사업자번호")).toHaveValue("223-45-67890");
    expect(screen.getByLabelText("상호")).toHaveAttribute("maxlength", "50");
    change("상호", "가".repeat(50));
    change("상호", "가".repeat(51));
    expect(screen.getByLabelText("상호")).toHaveValue("가".repeat(50));
    expect(screen.getByLabelText("비고")).toHaveAttribute("maxlength", "500");
    expect(screen.queryByLabelText("은행")).not.toBeInTheDocument();
    change("구분", "VEND");
    expect(
      screen.queryByLabelText("세금계산서 이메일"),
    ).not.toBeInTheDocument();
    change("은행", "KB");
    change("계좌번호", "123-456-7890");
    change("예금주", "회사");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(writes()).toHaveLength(1));
    const payload = JSON.parse(writes()[0][1].body);
    expect(payload.txinv_email_addr).toBeNull();
    expect(payload).not.toHaveProperty("ptnr_id");
    expect(payload).not.toHaveProperty("ptnr_no");
  });
  it.each([
    ["구분", "", "구분을 골라 주세요."],
    ["상호", "", "상호를 입력해 주세요."],
    [
      "사업자번호",
      "123456789",
      "사업자번호 10자리를 입력해 주세요. (예: 123-45-67890)",
    ],
    ["세금계산서 이메일", "", "고객사는 세금계산서 이메일이 꼭 필요합니다."],
    [
      "세금계산서 이메일",
      "invalid",
      "이메일 형식이 맞지 않습니다. (예: tax@company.co.kr)",
    ],
  ])(
    "rejects %s and focuses its first error",
    async (label, value, message) => {
      await customerForm();
      change(label, value);
      fireEvent.click(screen.getByRole("button", { name: "저장" }));
      await waitFor(() => expect(screen.getByLabelText(label)).toHaveFocus());
      expect(screen.getAllByText(message).length).toBeGreaterThan(0);
      expect(writes()).toHaveLength(0);
    },
  );
  it.each(["은행", "계좌번호", "예금주"])(
    "requires vendor %s",
    async (label) => {
      render(
        <PartnerForm initial={detail} onCancel={vi.fn()} onSaved={vi.fn()} />,
      );
      await readyForm();
      change(label, "");
      fireEvent.click(screen.getByRole("button", { name: "저장" }));
      await waitFor(() => expect(screen.getByLabelText(label)).toHaveFocus());
      expect(writes()).toHaveLength(0);
    },
  );
  it.each(["1234567", "123456789012345678901", "abcd-1234"])(
    "rejects malformed account %s",
    async (account) => {
      render(
        <PartnerForm initial={detail} onCancel={vi.fn()} onSaved={vi.fn()} />,
      );
      await readyForm();
      change("계좌번호", account);
      fireEvent.click(screen.getByRole("button", { name: "저장" }));
      if (account.length > 20) {
        expect(screen.getByLabelText("계좌번호")).toHaveValue(detail.acnt_no);
        return;
      }
      await waitFor(() =>
        expect(screen.getByLabelText("계좌번호")).toHaveFocus(),
      );
      expect(writes()).toHaveLength(0);
    },
  );
  it("preserves server rejected inputs, shows field error and focuses it", async () => {
    await customerForm();
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      init?.method === "POST"
        ? failure(
            "biz_reg_no",
            "이미 등록된 사업자번호입니다. (기존 상호)",
            409,
          )
        : original(url, init),
    );
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(screen.getByLabelText("사업자번호")).toHaveFocus(),
    );
    expect(screen.getByLabelText("상호")).toHaveValue("새 거래처");
    expect(
      screen.getAllByText("이미 등록된 사업자번호입니다. (기존 상호)"),
    ).toHaveLength(2);
  });
  it("reuses unsaved protection and browser unload cancellation", async () => {
    const cancel = vi.fn();
    render(<PartnerForm onCancel={cancel} onSaved={vi.fn()} />);
    await readyForm();
    change("상호", "미저장");
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "저장하지 않은 내용이 있습니다.",
    );
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(cancel).not.toHaveBeenCalled();
    expect(screen.getByLabelText("상호")).toHaveValue("미저장");
    const event = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    expect(cancel).toHaveBeenCalledOnce();
    expect(writes()).toHaveLength(0);
  });
  it("readonly business number is not present in PUT payload", async () => {
    const saved = vi.fn();
    render(<PartnerForm initial={detail} onCancel={vi.fn()} onSaved={saved} />);
    await readyForm();
    expect(screen.getByLabelText("사업자번호")).toHaveAttribute("readonly");
    change("상호", "새 상호");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(writes()[0][0]).toBe("/api/partners/1");
    const payload = JSON.parse(writes()[0][1].body);
    expect(payload).not.toHaveProperty("biz_reg_no");
    expect(payload).not.toHaveProperty("ptnr_id");
    expect(payload.ptnr_nm).toBe("새 상호");
  });
  it.each(["은행", "계좌번호", "예금주"])(
    "confirms existing %s change, cancel preserves draft and sends nothing",
    async (label) => {
      render(
        <PartnerForm initial={detail} onCancel={vi.fn()} onSaved={vi.fn()} />,
      );
      await readyForm();
      change(
        label,
        label === "은행"
          ? "SHINHAN"
          : label === "계좌번호"
            ? "987-654-3210"
            : "새 예금주",
      );
      fireEvent.click(screen.getByRole("button", { name: "저장" }));
      expect(screen.getByRole("dialog")).toHaveTextContent(
        "지급 계좌를 바꿀까요?",
      );
      expect(screen.getByRole("dialog")).toHaveTextContent(
        label === "계좌번호" ? "3210" : "7890",
      );
      fireEvent.click(
        within(screen.getByRole("dialog")).getByRole("button", {
          name: "취소",
        }),
      );
      expect(writes()).toHaveLength(0);
      fireEvent.click(screen.getByRole("button", { name: "저장" }));
      fireEvent.click(screen.getByRole("button", { name: "바꾸기" }));
      await waitFor(() => expect(writes()).toHaveLength(1));
    },
  );
  it("first payment account addition does not ask for replacement confirmation", async () => {
    render(
      <PartnerForm
        initial={{
          ...detail,
          ptnr_se_cd: "CUST",
          bank_cd: undefined,
          acnt_no: undefined,
          dpstr_nm: undefined,
        }}
        onCancel={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    await readyForm();
    change("구분", "BOTH");
    change("은행", "KB");
    change("계좌번호", "123-456-7890");
    change("예금주", "회사");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(writes()).toHaveLength(1));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("does not double submit while a write is in flight", async () => {
    await customerForm();
    let finish: (value: Response) => void = () => {};
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      init?.method === "POST"
        ? new Promise<Response>((resolve) => {
            finish = resolve;
          })
        : original(url, init),
    );
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    fireEvent.click(screen.getByTestId("partner-save-button"));
    expect(writes()).toHaveLength(1);
    finish(response(detail));
    await waitFor(() =>
      expect(screen.getByTestId("partner-save-button")).toBeEnabled(),
    );
  });
  it("omits forbidden identity and normalizes non applicable account fields", () => {
    const payload = partnerPayload({ ...detail, ptnr_se_cd: "CUST" }, true);
    expect(payload.bank_cd).toBeNull();
    expect(payload.acnt_no).toBeNull();
    expect(payload.dpstr_nm).toBeNull();
    expect(payload).not.toHaveProperty("biz_reg_no");
    expect(payload).not.toHaveProperty("ptnr_id");
  });
});

describe("거래처 상세", () => {
  it("stopped vendor displays banner and omits tax fields", async () => {
    current = {
      ...detail,
      ptnr_se_cd: "VEND",
      use_yn: "N",
      txinv_email_addr: undefined,
    };
    render(<PartnerDrawer id="1" onClose={vi.fn()} onSaved={vi.fn()} />);
    await screen.findByText(
      "사용을 중지한 거래처입니다. 새 프로젝트·지급에 고를 수 없습니다.",
    );
    expect(screen.queryByText("세금계산서 이메일")).not.toBeInTheDocument();
    expect(screen.getByText(/123-456-7890/)).toBeInTheDocument();
  });
  it("customer displays no payment account and unchanged save returns viewing mode", async () => {
    current = {
      ...detail,
      ptnr_se_cd: "CUST",
      bank_cd: undefined,
      acnt_no: undefined,
      dpstr_nm: undefined,
    };
    render(<PartnerDrawer id="1" onClose={vi.fn()} onSaved={vi.fn()} />);
    await screen.findByText("C-001");
    expect(screen.queryByText("지급 계좌")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /^수정$/ }));
    await readyForm();
    expect(screen.getByText(/수정 중/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByText("바뀐 내용이 없습니다.");
    expect(screen.getByRole("button", { name: /^수정$/ })).toBeInTheDocument();
  });
  it("more menu opens history tab without modifying data", async () => {
    render(<PartnerDrawer id="1" onClose={vi.fn()} onSaved={vi.fn()} />);
    await screen.findByText("C-001");
    fireEvent.click(screen.getByRole("button", { name: "더보기" }));
    fireEvent.click(screen.getByRole("button", { name: "변경 이력 보기" }));
    await screen.findByText("기본 사용자");
    expect(screen.getByRole("tab", { name: "변경 이력" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(writes()).toHaveLength(0);
  });
  it.each(["Escape", "outside", "button"])(
    "protects dirty edits on %s close",
    async (how) => {
      const close = vi.fn();
      render(<PartnerDrawer id="1" onClose={close} onSaved={vi.fn()} />);
      await screen.findByText("C-001");
      fireEvent.click(screen.getByRole("button", { name: /^수정$/ }));
      await readyForm();
      change("상호", "고친 상호");
      if (how === "Escape")
        fireEvent.keyDown(screen.getByLabelText("상호"), { key: "Escape" });
      else if (how === "outside")
        fireEvent.mouseDown(
          screen.getByTestId("partner-detail-drawer").parentElement!,
        );
      else fireEvent.click(screen.getByRole("button", { name: /^닫기$/ }));
      const confirmation = screen
        .getAllByRole("dialog")
        .find((d) =>
          d.textContent?.includes("창을 닫으면 고친 내용이 사라집니다."),
        )!;
      expect(confirmation).toBeInTheDocument();
      fireEvent.click(
        within(confirmation).getByRole("button", { name: "계속 작성" }),
      );
      expect(close).not.toHaveBeenCalled();
      expect(screen.getByLabelText("상호")).toHaveValue("고친 상호");
      expect(writes()).toHaveLength(0);
    },
  );
  it.each(["assignments", "history"])(
    "shows empty %s and request failure independently",
    async (tab) => {
      const original = fetcher.getMockImplementation()!;
      fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
        url.endsWith(`/${tab}`) ? response([]) : original(url, init),
      );
      render(
        <PartnerDrawer
          id="1"
          initialTab={tab}
          onClose={vi.fn()}
          onSaved={vi.fn()}
        />,
      );
      await screen.findByText(
        tab === "history"
          ? "변경 이력이 없습니다."
          : "현재 투입 중인 인력이 없습니다.",
      );
      expect(writes()).toHaveLength(0);
    },
  );
});
