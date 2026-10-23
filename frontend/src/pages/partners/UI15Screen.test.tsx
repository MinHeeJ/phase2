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

const both: PartnerDetail = {
  ptnr_id: "9007199254740993",
  ptnr_no: "C-9007199254740993",
  ptnr_se_cd: "BOTH",
  ptnr_nm: "거래처",
  biz_reg_no: "123-45-67890",
  txinv_email_addr: "tax@company.co.kr",
  bank_cd: "KB",
  acnt_no: "1234-5678-9012",
  dpstr_nm: "예금주",
  use_yn: "Y",
};
const row: PartnerListItem = {
  ptnr_id: both.ptnr_id,
  ptnr_no: both.ptnr_no,
  ptnr_se_cd: "BOTH",
  ptnr_nm: "거래처",
  biz_reg_no: both.biz_reg_no,
  use_yn: "Y",
  kindName: "둘 다",
  industryName: "SI",
  paymentAccount: "국민은행 …9012",
  projectCount: 2,
  assignmentPeopleCount: 5,
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const page = (items: unknown[]) =>
  response({ items, total: items.length, page: 1, size: 50 });
const failed = (field: string | null = null) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code: "INVALID_INPUT",
      field,
      message: "입력 오류",
      requestId: "server-request",
    }),
    { status: 400 },
  );
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
            : [{ code: "SI", name: "SI" }],
      );
    if (url.endsWith("/assignments") || url.endsWith("/history"))
      return response([]);
    if (init?.method === "POST" || init?.method === "PUT")
      return response({ ...both, ...JSON.parse(String(init.body)) });
    if (url.startsWith("/api/partners?")) return page([row]);
    return response(both);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.unstubAllGlobals());
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const calls = (method: string) =>
  fetcher.mock.calls.filter(([, init]) => init?.method === method);
async function openNew() {
  render(<UI15Screen />);
  await screen.findByTestId(`partner-row-${both.ptnr_id}`);
  fireEvent.click(screen.getByTestId("partner-create"));
  await screen.findByRole("option", { name: "고객사" });
}

describe("거래처 목록", () => {
  it("loads public default page and renders thirteen business columns without full account", async () => {
    render(<UI15Screen />);
    await screen.findByTestId(`partner-row-${both.ptnr_id}`);
    expect(
      fetcher.mock.calls.some(
        ([url]) =>
          url.includes("useYn=Y") &&
          url.includes("size=50") &&
          url.includes("sort=ptnr_nm%2Casc"),
      ),
    ).toBe(true);
    expect(screen.getAllByRole("columnheader")).toHaveLength(14);
    expect(screen.getByText("국민은행 …9012")).toBeInTheDocument();
    expect(screen.queryByText(both.acnt_no!)).not.toBeInTheDocument();
    expect(screen.getByTestId("partner-template")).toHaveAttribute(
      "href",
      "/api/uploads/templates/B.7",
    );
  });
  it("combines kind usage and search and resets empty conditions", async () => {
    render(<UI15Screen />);
    await screen.findByTestId(`partner-row-${both.ptnr_id}`);
    change("목록 구분", "VEND");
    change("목록 사용 여부", "N");
    change("상호·사업자번호", "1234567890");
    fireEvent.click(screen.getByTestId("partner-query"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) =>
            url.includes("kind=VEND") &&
            url.includes("useYn=N") &&
            url.includes("q=1234567890"),
        ),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId("partner-reset"));
    await waitFor(() =>
      expect(screen.getByLabelText("목록 사용 여부")).toHaveValue("Y"),
    );
    expect(screen.getByLabelText("목록 구분")).toHaveValue("");
  });
  it("uses server sort and page size", async () => {
    render(<UI15Screen />);
    await screen.findByTestId(`partner-row-${both.ptnr_id}`);
    fireEvent.click(screen.getByTestId("partner-sort-ptnr-nm"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => url.includes("sort=ptnr_nm%2Cdesc")),
      ).toBe(true),
    );
    change("목록 건수", "20");
    await waitFor(() =>
      expect(fetcher.mock.calls.some(([url]) => url.includes("size=20"))).toBe(
        true,
      ),
    );
  });
  it("empty results offer reset", async () => {
    fetcher.mockImplementation(async (url: string) =>
      url.startsWith("/api/partners?") ? page([]) : response([]),
    );
    render(<UI15Screen />);
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByText("조건 초기화"));
    expect(screen.getByLabelText("상호·사업자번호")).toHaveValue("");
  });
  it("failure is visible and retry re-queries", async () => {
    fetcher.mockImplementation(async (url: string) =>
      url.startsWith("/api/partners?") ? failed() : response([]),
    );
    render(<UI15Screen />);
    await screen.findByText("입력 오류");
    fetcher.mockImplementation(async (url: string) =>
      url.startsWith("/api/partners?") ? page([row]) : response([]),
    );
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(
      await screen.findByTestId(`partner-row-${both.ptnr_id}`),
    ).toBeInTheDocument();
  });
  it("selection enables bulk action without opening drawer", async () => {
    render(<UI15Screen />);
    await screen.findByTestId(`partner-row-${both.ptnr_id}`);
    fireEvent.click(screen.getByLabelText("거래처 선택"));
    expect(screen.getByText("1건 선택")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("partner-disable-selected"));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "1건의 사용을 중지할까요?",
    );
  });
  it("positive count opens assignment tab and zero does not have a link", async () => {
    render(<UI15Screen />);
    await screen.findByTestId(`partner-row-${both.ptnr_id}`);
    fireEvent.click(screen.getByRole("button", { name: "5명" }));
    expect(
      await screen.findByText("지금 투입 중인 내역이 없습니다."),
    ).toBeInTheDocument();
  });
  it("vendor project count is dash and zero people plain text", async () => {
    fetcher.mockImplementation(async (url: string) =>
      url.startsWith("/api/partners?")
        ? page([{ ...row, projectCount: null, assignmentPeopleCount: 0 }])
        : response([]),
    );
    render(<UI15Screen />);
    await screen.findByText("0명");
    expect(
      screen.queryByRole("button", { name: "0명" }),
    ).not.toBeInTheDocument();
  });
  it("passes upload action to owning feature", async () => {
    const onUpload = vi.fn();
    render(<UI15Screen onUpload={onUpload} />);
    fireEvent.click(screen.getByTestId("partner-upload"));
    expect(onUpload).toHaveBeenCalledOnce();
  });
  it("uses sales part integration slot", async () => {
    render(<UI15Screen salesParts={<div>파트 화면</div>} />);
    fireEvent.click(screen.getByRole("tab", { name: "영업 파트" }));
    expect(screen.getByText("파트 화면")).toBeInTheDocument();
  });
});

describe("등록·수정 폼", () => {
  it("enforces name length and formats business number", async () => {
    await openNew();
    change("상호", "가".repeat(50));
    expect(screen.getByLabelText("상호")).toHaveValue("가".repeat(50));
    change("상호", "가".repeat(51));
    expect(screen.getByLabelText("상호")).toHaveValue("가".repeat(50));
    change("사업자번호", "1234567890");
    expect(screen.getByLabelText("사업자번호")).toHaveValue("123-45-67890");
    expect(screen.getByLabelText("비고")).toHaveAttribute("maxlength", "500");
  });
  it("kind transition hides and clears irrelevant values in saved payload", async () => {
    await openNew();
    change("구분", "CUST");
    change("상호", "신규");
    change("사업자번호", "1234567890");
    change("세금계산서 이메일", "tax@company.co.kr");
    change("구분", "VEND");
    expect(
      screen.queryByLabelText("세금계산서 이메일"),
    ).not.toBeInTheDocument();
    change("은행", "KB");
    change("계좌번호", "12345678");
    change("예금주", "회사");
    fireEvent.click(screen.getByTestId("partner-save"));
    await waitFor(() => expect(calls("POST")).toHaveLength(1));
    expect(JSON.parse(calls("POST")[0][1].body)).toMatchObject({
      txinv_email_addr: null,
      bank_cd: "KB",
    });
    expect(await screen.findByText("저장했습니다.")).toBeInTheDocument();
  });
  it("new record is pinned using reloaded API row", async () => {
    await openNew();
    change("구분", "CUST");
    change("상호", "거래처");
    change("사업자번호", "1234567890");
    change("세금계산서 이메일", "tax@company.co.kr");
    fireEvent.click(screen.getByTestId("partner-save"));
    await waitFor(() =>
      expect(
        screen.getByTestId(`partner-row-${both.ptnr_id}`).className,
      ).toContain("highlight"),
    );
  });
  it("server field error preserves inputs and focuses first error", async () => {
    await openNew();
    change("상호", "입력 유지");
    fetcher.mockImplementation(async () => failed("ptnr_nm"));
    fireEvent.click(screen.getByTestId("partner-save"));
    await waitFor(() => expect(screen.getByLabelText("상호")).toHaveFocus());
    expect(screen.getByLabelText("상호")).toHaveValue("입력 유지");
    expect(screen.getAllByText("입력 오류").length).toBeGreaterThan(0);
  });
  it("dirty cancel reuses unsaved guard and keep writing preserves input", async () => {
    await openNew();
    change("상호", "작성 중");
    fireEvent.click(screen.getByTestId("partner-cancel"));
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(screen.getByLabelText("상호")).toHaveValue("작성 중");
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId("partner-cancel"));
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    await screen.findByTestId(`partner-row-${both.ptnr_id}`);
    expect(calls("POST")).toHaveLength(0);
  });
  it("business number is immutable and omitted from update payload", async () => {
    const saved = vi.fn();
    render(<PartnerForm initial={both} onSaved={saved} onCancel={() => {}} />);
    await screen.findByRole("option", { name: "둘 다" });
    expect(screen.getByLabelText("사업자번호")).toHaveAttribute("readonly");
    expect(screen.getByText(/프로젝트·청구·지급과 연결/)).toBeInTheDocument();
    change("상호", "새 상호");
    fireEvent.click(screen.getByTestId("partner-save"));
    await waitFor(() => expect(calls("PUT")).toHaveLength(1));
    expect(JSON.parse(calls("PUT")[0][1].body)).not.toHaveProperty(
      "biz_reg_no",
    );
  });
  it("no change exits without write", async () => {
    const saved = vi.fn();
    render(<PartnerForm initial={both} onSaved={saved} onCancel={() => {}} />);
    fireEvent.click(screen.getByTestId("partner-save"));
    expect(saved).toHaveBeenCalledWith(both, false);
    expect(calls("PUT")).toHaveLength(0);
  });
  it("existing account change asks confirmation and cancel preserves draft", async () => {
    render(
      <PartnerForm initial={both} onSaved={() => {}} onCancel={() => {}} />,
    );
    await screen.findByRole("option", { name: "국민은행" });
    change("계좌번호", "9876-5432-1098");
    fireEvent.click(screen.getByTestId("partner-save"));
    expect(screen.getByRole("dialog")).toHaveTextContent("…1098");
    expect(screen.getByRole("dialog")).not.toHaveTextContent("9876-5432-1098");
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "취소" }),
    );
    expect(screen.getByLabelText("계좌번호")).toHaveValue("9876-5432-1098");
    expect(calls("PUT")).toHaveLength(0);
    fireEvent.click(screen.getByTestId("partner-save"));
    fireEvent.click(screen.getByRole("button", { name: "바꾸기" }));
    await waitFor(() => expect(calls("PUT")).toHaveLength(1));
  });
  it("first account added to customer does not ask account confirmation", async () => {
    render(
      <PartnerForm
        initial={{
          ...both,
          ptnr_se_cd: "CUST",
          bank_cd: undefined,
          acnt_no: undefined,
          dpstr_nm: undefined,
        }}
        onSaved={() => {}}
        onCancel={() => {}}
      />,
    );
    await screen.findByRole("option", { name: "둘 다" });
    change("구분", "BOTH");
    change("은행", "KB");
    change("계좌번호", "12345678");
    change("예금주", "회사");
    fireEvent.click(screen.getByTestId("partner-save"));
    await waitFor(() => expect(calls("PUT")).toHaveLength(1));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("payload drops immutable identifiers and non-applicable account", () => {
    expect(partnerPayload({ ...both, ptnr_se_cd: "CUST" }, true)).toMatchObject(
      { bank_cd: null, acnt_no: null, dpstr_nm: null },
    );
    expect(partnerPayload(both, true)).not.toHaveProperty("biz_reg_no");
  });
});

describe("상세", () => {
  it.each(["CUST", "VEND", "BOTH"])(
    "%s keeps detail labels and fallback values when optional fields are absent",
    async (kind) => {
      fetcher.mockImplementation(async (url: string) =>
        url.endsWith("/history") ||
        url.endsWith("/assignments") ||
        url.startsWith("/api/codes/")
          ? response([])
          : response({
              ...both,
              ptnr_se_cd: kind,
              ceo_nm: null,
              chrg_nm: undefined,
              txinv_email_addr: null,
              bank_cd: undefined,
              acnt_no: null,
              dpstr_nm: undefined,
            }),
      );
      render(
        <PartnerDrawer
          id={both.ptnr_id}
          onClose={() => {}}
          onSaved={() => {}}
          onStatus={() => {}}
        />,
      );
      const panel = await screen.findByRole("tabpanel");
      expect(
        within(panel).getByTestId("partner-detail-field-5"),
      ).toHaveTextContent("대표자–");
      expect(
        within(panel).getByTestId("partner-detail-field-7"),
      ).toHaveTextContent("담당자–");
      if (kind === "VEND") {
        expect(
          within(panel).queryByText("세금계산서 이메일"),
        ).not.toBeInTheDocument();
      } else {
        expect(
          within(panel).getByTestId("partner-detail-field-10"),
        ).toHaveTextContent("세금계산서 이메일–");
      }
      if (kind === "CUST") {
        expect(within(panel).queryByText("지급 계좌")).not.toBeInTheDocument();
      } else {
        expect(
          within(panel).getByTestId("partner-detail-field-11"),
        ).toHaveTextContent("지급 계좌– – / –");
      }
    },
  );

  it("stopped vendor banner and omitted email section", async () => {
    fetcher.mockImplementation(async (url: string) =>
      url.endsWith("/history") || url.endsWith("/assignments")
        ? response([])
        : url.startsWith("/api/codes/")
          ? response([])
          : response({
              ...both,
              ptnr_se_cd: "VEND",
              use_yn: "N",
              txinv_email_addr: undefined,
            }),
    );
    render(
      <PartnerDrawer
        id={both.ptnr_id}
        onClose={() => {}}
        onSaved={() => {}}
        onStatus={() => {}}
      />,
    );
    await screen.findByText(/사용을 중지한 거래처입니다/);
    expect(screen.queryByText("세금계산서 이메일")).not.toBeInTheDocument();
    expect(screen.getByText(new RegExp(both.acnt_no!))).toBeInTheDocument();
  });
  it("customer has no payment account and more opens same history tab", async () => {
    fetcher.mockImplementation(async (url: string) =>
      url.endsWith("/history") || url.endsWith("/assignments")
        ? response([])
        : url.startsWith("/api/codes/")
          ? response([])
          : response({
              ...both,
              ptnr_se_cd: "CUST",
              bank_cd: undefined,
              acnt_no: undefined,
              dpstr_nm: undefined,
            }),
    );
    render(
      <PartnerDrawer
        id={both.ptnr_id}
        onClose={() => {}}
        onSaved={() => {}}
        onStatus={() => {}}
      />,
    );
    await screen.findByRole("button", { name: "수정" });
    expect(screen.queryByText("지급 계좌")).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("더보기"));
    fireEvent.click(screen.getByText("변경 이력 보기"));
    expect(
      await screen.findByText("아직 바꾼 기록이 없습니다."),
    ).toBeInTheDocument();
  });
  it("dirty drawer close asks specific confirmation", async () => {
    const close = vi.fn();
    render(
      <PartnerDrawer
        id={both.ptnr_id}
        onClose={close}
        onSaved={() => {}}
        onStatus={() => {}}
      />,
    );
    fireEvent.click(await screen.findByRole("button", { name: "수정" }));
    change("상호", "미저장");
    fireEvent.click(screen.getByTestId("partner-close"));
    expect(
      screen.getByText("창을 닫으면 고친 내용이 사라집니다."),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(close).not.toHaveBeenCalled();
    expect(screen.getByLabelText("상호")).toHaveValue("미저장");
  });
  it("detail read failure stays an error and retry works", async () => {
    fetcher.mockImplementation(async () => failed());
    render(
      <PartnerDrawer
        id={both.ptnr_id}
        onClose={() => {}}
        onSaved={() => {}}
        onStatus={() => {}}
      />,
    );
    await screen.findByText("입력 오류");
    expect(
      screen.queryByRole("button", { name: "수정" }),
    ).not.toBeInTheDocument();
  });
});
