import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import UI15Screen from "./UI15Screen";
import { PartnerForm, emptyPartner } from "./PartnerForm";
import { PartnerDrawer } from "./PartnerDrawer";
import {
  type PartnerDetail,
  type PartnerListItem,
  updatePartner,
} from "./client";
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
const item = (row: PartnerDetail): PartnerListItem => ({
  ptnr_id: row.ptnr_id,
  ptnr_no: row.ptnr_no,
  ptnr_se_cd: row.ptnr_se_cd,
  ptnr_nm: row.ptnr_nm,
  biz_reg_no: row.biz_reg_no,
  ceo_nm: row.ceo_nm,
  indty_cd: row.indty_cd,
  chrg_nm: row.chrg_nm,
  chrg_tel_no: row.chrg_tel_no,
  txinv_email_addr: row.txinv_email_addr,
  use_yn: row.use_yn,
  kindName: row.ptnr_se_cd === "CUST" ? "고객사" : "협력사",
  industryName: null,
  paymentAccount: row.acnt_no ? "국민은행 …7890" : null,
  projectCount: row.ptnr_se_cd === "VEND" ? null : 2,
  assignmentPeopleCount: row.ptnr_se_cd === "VEND" ? 0 : 5,
});
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failure = (field: string | null = null) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code: "INVALID_INPUT",
      message: "입력 오류",
      field,
      requestId: "server-request",
    }),
    { status: 400 },
  );
let fetcher: ReturnType<typeof vi.fn>;
let rows: PartnerDetail[];
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
            ? [
                { code: "KB", name: "국민은행" },
                { code: "SHINHAN", name: "신한은행" },
              ]
            : [{ code: "SI", name: "SI" }],
      );
    if (url.endsWith("/assignments") || url.endsWith("/history"))
      return response([]);
    if (init?.method === "POST" && url === "/api/partners") {
      const row = {
        ...customer,
        ...JSON.parse(init.body as string),
        ptnr_id: "3",
        ptnr_no: "C-003",
      };
      rows.push(row);
      return response(row);
    }
    if (init?.method === "PUT") {
      const id = url.split("/").at(-1);
      const body = JSON.parse(init.body as string);
      rows = rows.map((r) => (r.ptnr_id === id ? { ...r, ...body } : r));
      return response(rows.find((r) => r.ptnr_id === id));
    }
    if (url.startsWith("/api/partners?")) {
      const q = new URLSearchParams(url.split("?")[1]).get("q");
      const filtered = q
        ? rows.filter((r) => r.ptnr_nm.includes(q) || r.biz_reg_no === q)
        : rows;
      return response({
        items: filtered.map(item),
        total: filtered.length,
        page: 1,
        size: 50,
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
const writes = () =>
  fetcher.mock.calls.filter((c) => c[1]?.method && c[1].method !== "GET");
async function createForm() {
  fireEvent.click(screen.getByTestId("partner-create"));
  await screen.findByRole("option", { name: "고객사" });
}
async function validCustomer() {
  change("구분", "CUST");
  change("상호", "신규 거래처");
  change("사업자번호", "3333333333");
  change("세금계산서 이메일", "tax@company.co.kr");
}
const saved = vi.fn(),
  dirty = vi.fn(),
  reload = vi.fn().mockResolvedValue(undefined);
function form(initial?: PartnerDetail) {
  return render(
    <PartnerForm
      initial={initial}
      onCancel={() => {}}
      onSaved={saved}
      onDirtyChange={dirty}
      reload={reload}
    />,
  );
}
function drawer(initial = customer) {
  rows = [initial];
  return render(
    <PartnerDrawer
      id={initial.ptnr_id}
      onClose={() => {}}
      onSaved={async () => {}}
      onStateAction={() => {}}
    />,
  );
}

describe("partners list", () => {
  it("opens first tab with default filters, title actions and thirteen domain columns", async () => {
    render(<UI15Screen />);
    await screen.findByTestId("partner-row-1");
    expect(screen.getByRole("tab", { name: "거래처" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByTestId("partner-template-download")).toHaveAttribute(
      "href",
      "/api/uploads/templates/B.7",
    );
    expect(screen.getByTestId("partner-use-filter")).toHaveValue("Y");
    expect(screen.getAllByRole("columnheader")).toHaveLength(14);
    expect(
      screen.getByRole("columnheader", { name: "거래처 코드" }),
    ).toBeVisible();
    expect(
      screen.getByRole("columnheader", { name: "투입 인력" }),
    ).toBeVisible();
    const query = fetcher.mock.calls.find((c) =>
      String(c[0]).startsWith("/api/partners?"),
    )![0];
    expect(query).toContain("size=50");
    expect(query).toContain("sort=ptnr_nm%2Casc");
  });
  it("submits AND filters with numeric business search and resets defaults", async () => {
    render(<UI15Screen />);
    await screen.findByTestId("partner-row-1");
    await screen.findByRole("option", { name: "협력사" });
    change("조회 구분", "VEND");
    change("사용 여부", "N");
    change("상호·사업자번호", "1234567890");
    fireEvent.click(screen.getByTestId("partner-query"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some((c) =>
          String(c[0]).includes("kind=VEND&useYn=N&q=1234567890"),
        ),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId("partner-reset"));
    expect(screen.getByLabelText("사용 여부")).toHaveValue("Y");
    expect(screen.getByLabelText("조회 구분")).toHaveValue("");
    expect(screen.getByLabelText("상호·사업자번호")).toHaveValue("");
  });
  it("shows zero-result reset instead of a fake row", async () => {
    rows = [];
    render(<UI15Screen />);
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeVisible();
    fireEvent.click(screen.getByText("조건 초기화"));
    expect(screen.queryByTestId("partner-row-1")).not.toBeInTheDocument();
  });
  it("uses distinct kind/status tones and masks accounts", async () => {
    render(<UI15Screen />);
    const row = await screen.findByTestId("partner-row-2");
    expect(within(row).getByText("협력사").className).toContain("neutral");
    expect(within(row).getByText("사용").className).toContain("success");
  });
  it("renders account suffix and no zero link or vendor project count", async () => {
    render(<UI15Screen />);
    const row = await screen.findByTestId("partner-row-2");
    expect(within(row).getByText("국민은행 …7890")).toBeVisible();
    expect(within(row).queryByText("123-456-7890")).not.toBeInTheDocument();
    expect(within(row).getByText("0명").tagName).toBe("TD");
    expect(screen.queryByTestId("partner-people-2")).not.toBeInTheDocument();
    expect(
      within(await screen.findByTestId("partner-row-1")).getByText("고객사")
        .className,
    ).toContain("info");
    expect(within(row).getByText("협력사").className).toContain("neutral");
  });
  it("selecting a row reveals the ordered selection action", async () => {
    render(<UI15Screen />);
    fireEvent.click(await screen.findByLabelText("가 거래처 선택"));
    expect(screen.getByText("1건 선택")).toBeVisible();
    expect(screen.getByTestId("partner-disable-selected")).toBeEnabled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("sorts via server query and changes page size", async () => {
    render(<UI15Screen />);
    await screen.findByTestId("partner-row-1");
    fireEvent.click(screen.getByTestId("partner-sort-ptnr-nm"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some((c) =>
          String(c[0]).includes("sort=ptnr_nm%2Cdesc"),
        ),
      ).toBe(true),
    );
    change("목록 건수", "20");
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some((c) => String(c[0]).includes("size=20")),
      ).toBe(true),
    );
  });
  it("positive count opens assignments, row opens basic information", async () => {
    render(<UI15Screen />);
    fireEvent.click(await screen.findByTestId("partner-people-1"));
    expect(
      await screen.findByText("지금 투입 중인 내역이 없습니다."),
    ).toBeVisible();
    expect(
      fetcher.mock.calls.some((c) => c[0] === "/api/partners/1/assignments"),
    ).toBe(true);
  });
  it("read failure shows retry and recovers without a write", async () => {
    const original = fetcher.getMockImplementation()!;
    let failed = true;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith("/api/partners?") && failed) {
        failed = false;
        throw new Error("offline");
      }
      return original(url, init);
    });
    render(<UI15Screen />);
    fireEvent.click(await screen.findByText("다시 시도"));
    expect(await screen.findByTestId("partner-row-1")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("delegates upload and sales tab without importing another feature", async () => {
    const upload = vi.fn();
    render(
      <UI15Screen onUpload={upload} salesParts={<div>영업 파트 업무</div>} />,
    );
    fireEvent.click(screen.getByTestId("partner-upload"));
    expect(upload).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole("tab", { name: "영업 파트" }));
    expect(screen.getByText("영업 파트 업무")).toBeVisible();
  });
  it("uses the lifecycle export client with visible columns and no page restriction", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      url.startsWith("/api/partners/export?")
        ? failure("columns")
        : original(url, init),
    );
    render(<UI15Screen />);
    await screen.findByTestId("partner-row-1");
    fireEvent.click(screen.getByTestId("partners-export"));
    await screen.findByText("입력 오류");
    const call = fetcher.mock.calls.find((c) =>
      String(c[0]).startsWith("/api/partners/export?"),
    )!;
    const query = new URLSearchParams(String(call[0]).split("?")[1]);
    expect(query.getAll("columns")).toEqual([
      "ptnr_se_cd",
      "ptnr_no",
      "ptnr_nm",
      "biz_reg_no",
      "ceo_nm",
      "industryName",
      "chrg_nm",
      "chrg_tel_no",
      "txinv_email_addr",
      "paymentAccount",
      "projectCount",
      "assignmentPeopleCount",
      "use_yn",
    ]);
    expect(query.get("useYn")).toBe("Y");
    expect(query.has("kind")).toBe(false);
    for (const key of ["page", "size", "sort", "ids"])
      expect(query.has(key)).toBe(false);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(
      screen.queryByText("엑셀 파일을 내려받았습니다."),
    ).not.toBeInTheDocument();
  });
  it("offers selected or all export scope without downloading on cancel", async () => {
    render(<UI15Screen />);
    fireEvent.click(await screen.findByLabelText("나 거래처 선택"));
    fireEvent.click(screen.getByTestId("partners-export"));
    expect(screen.getByLabelText("선택 1건")).toBeChecked();
    expect(screen.getByLabelText("조회 전체 2건")).toBeVisible();
    fireEvent.click(screen.getByTestId("export-cancel"));
    expect(
      fetcher.mock.calls.some((c) =>
        String(c[0]).startsWith("/api/partners/export?"),
      ),
    ).toBe(false);
  });
  it("routes bulk disable to the shared result dialog with server exclusion counts", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      url === "/api/partners/disable"
        ? response({
            disabledIds: ["2"],
            blocked: [
              {
                id: "1",
                name: "가 거래처",
                projectCount: 2,
                peopleCount: 3,
                reason: "먼저 연결을 이동해 주세요.",
              },
            ],
          })
        : original(url, init),
    );
    render(<UI15Screen />);
    await screen.findByTestId("partner-row-2");
    fireEvent.click(screen.getByTestId("partner-select-all"));
    fireEvent.click(screen.getByTestId("partner-disable-selected"));
    expect(
      fetcher.mock.calls.some((c) => c[0] === "/api/partners/disable"),
    ).toBe(false);
    fireEvent.click(screen.getByTestId("partner-disable-confirm"));
    expect(await screen.findByText(/소속 인력 3명/)).toBeVisible();
    expect(
      await screen.findByText("1건 사용 중지 완료 · 1건 제외"),
    ).toBeVisible();
    expect(
      screen.queryByTestId("partner-disable-confirm"),
    ).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.find((c) => c[0] === "/api/partners/disable")?.[1],
    ).toMatchObject({
      method: "POST",
      body: JSON.stringify({ ids: ["1", "2"] }),
    });
  });
});

describe("partner create and edit", () => {
  it("create shows sections by kind and prevents the fifty-first name character", async () => {
    render(<UI15Screen />);
    await createForm();
    expect(screen.getByRole("heading", { name: "기본 정보" })).toBeVisible();
    change("구분", "BOTH");
    expect(screen.getByRole("heading", { name: "세금계산서" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "지급 계좌" })).toBeVisible();
    change("상호", "가".repeat(50));
    change("상호", "가".repeat(51));
    expect(screen.getByLabelText("상호")).toHaveValue("가".repeat(50));
    expect(screen.getByLabelText("비고")).toHaveAttribute("maxlength", "500");
  });
  it("formats ten business digits and clears hidden email on kind change", async () => {
    form();
    await screen.findByRole("option", { name: "고객사" });
    await validCustomer();
    expect(screen.getByLabelText("사업자번호")).toHaveValue("333-33-33333");
    change("구분", "VEND");
    expect(
      screen.queryByLabelText("세금계산서 이메일"),
    ).not.toBeInTheDocument();
    change("은행", "KB");
    change("계좌번호", "12345678");
    change("예금주", "예금주");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(writes()).toHaveLength(1));
    expect(
      JSON.parse(writes()[0][1].body as string).txinv_email_addr,
    ).toBeNull();
  });
  it.each([
    ["", "구분을 골라 주세요.", "구분"],
    ["CUST", "상호를 입력해 주세요.", "상호"],
  ])("required %s focuses first field", async (kind, message, label) => {
    form();
    await screen.findByRole("option", { name: "고객사" });
    if (kind) change("구분", kind);
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(await screen.findByText(message)).toBeVisible();
    expect(screen.getByLabelText(label)).toHaveFocus();
    expect(writes()).toHaveLength(0);
  });
  it("requires valid email and keeps draft", async () => {
    form();
    await screen.findByRole("option", { name: "고객사" });
    await validCustomer();
    change("세금계산서 이메일", "invalid");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(
      await screen.findByText(
        "이메일 형식이 맞지 않습니다. (예: tax@company.co.kr)",
      ),
    ).toBeVisible();
    expect(screen.getByLabelText("상호")).toHaveValue("신규 거래처");
    expect(writes()).toHaveLength(0);
  });
  it("vendor account validation is inline and options come from BANK", async () => {
    form();
    await screen.findByRole("option", { name: "협력사" });
    change("구분", "VEND");
    change("상호", "협력");
    change("사업자번호", "3333333333");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(await screen.findByText("은행을 골라 주세요.")).toBeVisible();
    expect(screen.getByLabelText("은행")).toHaveFocus();
    expect(fetcher.mock.calls.some((c) => c[0] === "/api/codes/BANK")).toBe(
      true,
    );
  });
  it("save error maps singular field and preserves input", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      init?.method === "POST" ? failure("ptnr_nm") : original(url, init),
    );
    form();
    await screen.findByRole("option", { name: "고객사" });
    await validCustomer();
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(
      await screen.findByText("입력 오류", { selector: "span" }),
    ).toBeVisible();
    expect(screen.getByLabelText("상호")).toHaveFocus();
    expect(screen.getByLabelText("상호")).toHaveValue("신규 거래처");
  });
  it("successful create requeries and pins the new actual API item", async () => {
    render(<UI15Screen />);
    await createForm();
    await validCustomer();
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(await screen.findByText("저장했습니다.")).toBeVisible();
    const row = await screen.findByTestId("partner-row-3");
    expect(row.className).toContain("highlight");
    expect(screen.getAllByTestId(/^partner-row-/)[0]).toBe(row);
  });
  it("create cancel reuses unsaved protection and continue retains input", async () => {
    render(<UI15Screen />);
    await createForm();
    change("상호", "작성 중");
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.getByRole("dialog")).toBeVisible();
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByLabelText("상호")).toHaveValue("작성 중");
  });
  it("edit readonly business identity is omitted from PUT", async () => {
    form(customer);
    await screen.findByRole("option", { name: "고객사" });
    expect(screen.getByLabelText("사업자번호")).toHaveAttribute("readonly");
    expect(screen.getByText(/프로젝트·청구·지급과 연결되어/)).toBeVisible();
    change("상호", "수정");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(writes()).toHaveLength(1));
    expect(writes()[0][0]).toBe("/api/partners/1");
    expect(JSON.parse(writes()[0][1].body as string)).not.toHaveProperty(
      "biz_reg_no",
    );
  });
  it("client explicit update strips identity and response-only keys", async () => {
    await updatePartner(customer.ptnr_id, customer);
    const body = JSON.parse(writes()[0][1].body as string);
    expect(body).not.toHaveProperty("ptnr_id");
    expect(body).not.toHaveProperty("use_yn");
    expect(body).not.toHaveProperty("biz_reg_no");
  });
  it("no-change edit returns to read mode without a write", async () => {
    const done = vi.fn();
    render(
      <PartnerForm
        initial={customer}
        onCancel={() => {}}
        onSaved={done}
        onDirtyChange={dirty}
        reload={reload}
      />,
    );
    await screen.findByRole("option", { name: "고객사" });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(done).toHaveBeenCalledWith(null);
    expect(writes()).toHaveLength(0);
  });
  it("existing account changes require suffix confirmation; cancel keeps input", async () => {
    form(vendor);
    await screen.findByRole("option", { name: "국민은행" });
    change("예금주", "새 예금주");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(
      screen.getByRole("dialog", { name: "지급 계좌를 바꿀까요?" }),
    ).toBeVisible();
    expect(screen.getByText(/국민은행 …7890/)).toBeVisible();
    fireEvent.click(within(screen.getByRole("dialog")).getByText("취소"));
    expect(screen.getByLabelText("예금주")).toHaveValue("새 예금주");
    expect(writes()).toHaveLength(0);
  });
  it("account confirmation writes only after approval", async () => {
    form(vendor);
    await screen.findByRole("option", { name: "국민은행" });
    change("계좌번호", "987-654-3210");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    fireEvent.click(screen.getByText("바꾸기"));
    await waitFor(() => expect(writes()).toHaveLength(1));
  });
  it("first account addition has no second confirmation", async () => {
    form(customer);
    await screen.findByRole("option", { name: "둘 다" });
    change("구분", "BOTH");
    change("은행", "KB");
    change("계좌번호", "12345678");
    change("예금주", "예금주");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(writes()).toHaveLength(1));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});

describe("partner detail", () => {
  it("inactive vendor shows banner and hides customer email", async () => {
    drawer({ ...vendor, use_yn: "N" });
    expect(await screen.findByText(/사용을 중지한 거래처입니다/)).toBeVisible();
    expect(screen.queryByText("세금계산서 이메일")).not.toBeInTheDocument();
    expect(screen.getByText(/123-456-7890/)).toBeVisible();
  });
  it("customer hides payment and more opens history in same drawer", async () => {
    drawer();
    await screen.findByText("C-001");
    expect(screen.queryByText("지급 계좌")).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("더보기"));
    fireEvent.click(screen.getByText("변경 이력 보기"));
    expect(await screen.findByText("아직 바꾼 기록이 없습니다.")).toBeVisible();
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
  });
  it("dirty close confirms and continuing preserves same edit", async () => {
    const close = vi.fn();
    render(
      <PartnerDrawer
        id="1"
        onClose={close}
        onSaved={async () => {}}
        onStateAction={() => {}}
      />,
    );
    await screen.findByText("C-001");
    fireEvent.click(screen.getByText("수정"));
    await screen.findByLabelText("상호");
    change("상호", "보존");
    fireEvent.click(screen.getByLabelText("상세 닫기"));
    expect(
      screen.getByText("창을 닫으면 고친 내용이 사라집니다."),
    ).toBeVisible();
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByLabelText("상호")).toHaveValue("보존");
    expect(close).not.toHaveBeenCalled();
  });
  it("no-dirty backdrop closes without writes", async () => {
    const close = vi.fn();
    render(
      <PartnerDrawer
        id="1"
        onClose={close}
        onSaved={async () => {}}
        onStateAction={() => {}}
      />,
    );
    await screen.findByText("C-001");
    fireEvent.click(screen.getByTestId("partner-drawer-backdrop"));
    expect(close).toHaveBeenCalledOnce();
    expect(writes()).toHaveLength(0);
  });
});
