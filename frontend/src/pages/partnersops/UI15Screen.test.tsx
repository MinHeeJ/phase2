import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import UI15Screen, { PartnerDetailDrawer } from "./UI15Screen";
import {
  PartnerActivityTabs,
  PartnerDisableDialog,
  PartnerExportButton,
  PartnerMappingDialog,
  PartnerMappingResolution,
} from "./PartnerActions";
import type { PartnerRow } from "./client";

const row: PartnerRow = {
  ptnr_id: "2",
  ptnr_no: "C-002",
  ptnr_nm: "협력사 B",
  ptnr_se_cd: "VEND",
  biz_reg_no: "100-00-00002",
  ceo_nm: null,
  indty_cd: null,
  chrg_nm: null,
  chrg_tel_no: null,
  txinv_email_addr: null,
  use_yn: "Y",
  paymentAccount: "국민은행 …8901",
  projectCount: null,
  assignmentPeopleCount: 2,
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failure = (
  message = "실패했습니다.",
  code = "INVALID_INPUT",
  status = 400,
) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      message,
      code,
      field: null,
      requestId: "request-id",
    }),
    { status },
  );
let fetcher: ReturnType<typeof vi.fn>;
let mobile = false;
beforeEach(() => {
  mobile = false;
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({
      matches: mobile,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/codes/"))
      return response([
        { code: "VEND", name: "협력사", attr1: null, attr2: null, attr3: null },
      ]);
    if (url.startsWith("/api/partners?"))
      return response({ items: [row], total: 105, page: 1, size: 50 });
    if (url === "/api/partners/2")
      return response({
        ...row,
        bank_cd: "KB",
        acnt_no: "012-345-678901",
        dpstr_nm: "예금주",
      });
    if (url.endsWith("/assignments"))
      return response([
        {
          projectName: "프로젝트 A",
          developerName: "이름 A",
          affiliation: "협력사 B",
          jobCode: "BE",
          jobName: "백엔드",
          assignmentRate: 1,
          endDate: "2026-10-31",
        },
      ]);
    if (url.endsWith("/history"))
      return response([
        {
          changedAt: "2026-10-22T09:00:00",
          column: "acnt_no",
          itemName: "계좌번호",
          before: "8901",
          after: "5544",
          changeType: "UPDATE",
          actorName: "기본 사용자",
        },
      ]);
    if (url === "/api/partners/disable")
      return response({
        disabledIds: JSON.parse(String(init?.body)).ids,
        blocked: [],
      });
    if (url.endsWith("/enable")) return response({ ...row, use_yn: "Y" });
    if (url.startsWith("/api/sales-parts?"))
      return response({ items: [], total: 0, page: 1, size: 50 });
    if (url === "/api/admin/excel-maps") return response(undefined);
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("거래처 현재 투입·변경 이력", () => {
  it("renders API assignments at 100 percent and reads history without writes", async () => {
    render(<PartnerActivityTabs id="2" />);
    expect(await screen.findByText("100%")).toBeInTheDocument();
    expect(screen.getByText("협력사 B")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "변경 이력" }));
    expect(await screen.findByText("기본 사용자")).toBeInTheDocument();
    expect(screen.getByText("8901")).toBeInTheDocument();
    expect(fetcher.mock.calls.every(([, init]) => !init?.method)).toBe(true);
  });
  it("shows exact assignment empty state", async () => {
    fetcher.mockResolvedValue(response([]));
    render(<PartnerActivityTabs id="2" />);
    expect(
      await screen.findByText("지금 투입 중인 내역이 없습니다."),
    ).toBeInTheDocument();
  });
  it("shows exact history empty state", async () => {
    fetcher.mockResolvedValue(response([]));
    render(<PartnerActivityTabs id="2" initial="history" />);
    expect(
      await screen.findByText("아직 바꾼 기록이 없습니다."),
    ).toBeInTheDocument();
  });
  it("keeps failed read visible and retries", async () => {
    fetcher.mockResolvedValueOnce(failure());
    render(<PartnerActivityTabs id="2" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("실패했습니다.");
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("100%")).toBeInTheDocument();
  });
});
describe("중지 확인", () => {
  it("cancels without a mutation or invented available count", () => {
    const cancel = vi.fn();
    render(
      <PartnerDisableDialog
        rows={[row]}
        onCancel={cancel}
        onChanged={vi.fn()}
      />,
    );
    expect(screen.getByText(/기록은 그대로 남습니다/)).toBeInTheDocument();
    expect(fetcher).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(cancel).toHaveBeenCalledOnce();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("confirms once and returns successful count", async () => {
    const changed = vi.fn();
    render(
      <PartnerDisableDialog
        rows={[row]}
        onCancel={vi.fn()}
        onChanged={changed}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    await waitFor(() => expect(changed).toHaveBeenCalledWith("1건 완료"));
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({ ids: ["2"] });
  });
  it("reports partial completion and server exclusion counts", async () => {
    fetcher.mockResolvedValue(
      response({
        disabledIds: ["1", "3"],
        blocked: [
          {
            id: "2",
            name: "협력사 B",
            projectCount: 0,
            peopleCount: 3,
            reason: "소속 인력 3명",
          },
        ],
      }),
    );
    const changed = vi.fn();
    render(
      <PartnerDisableDialog
        rows={[{ ...row, ptnr_id: "1" }, row, { ...row, ptnr_id: "3" }]}
        onCancel={vi.fn()}
        onChanged={changed}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    expect(
      await screen.findByText(/진행 프로젝트 0건 \/ 소속 인력 3명/),
    ).toBeInTheDocument();
    expect(changed).toHaveBeenCalledWith("2건 완료, 1건 제외");
  });
  it("shows all blocked without claiming success", async () => {
    fetcher.mockResolvedValue(
      response({
        disabledIds: [],
        blocked: [
          {
            id: "2",
            name: "협력사 B",
            projectCount: 2,
            peopleCount: 3,
            reason: "먼저 이동해 주세요.",
          },
        ],
      }),
    );
    const changed = vi.fn();
    render(
      <PartnerDisableDialog
        rows={[row]}
        onCancel={vi.fn()}
        onChanged={changed}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    expect(
      await screen.findByRole("heading", { name: "사용을 중지할 수 없습니다" }),
    ).toBeInTheDocument();
    expect(changed).not.toHaveBeenCalled();
  });
  it("shows already disabled without success", async () => {
    fetcher.mockResolvedValue(response({ disabledIds: [], blocked: [] }));
    const changed = vi.fn();
    render(
      <PartnerDisableDialog
        rows={[{ ...row, use_yn: "N" }]}
        onCancel={vi.fn()}
        onChanged={changed}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    expect(
      await screen.findByText("이미 중지된 거래처입니다."),
    ).toBeInTheDocument();
    expect(changed).not.toHaveBeenCalled();
  });
  it("shows single conflict counts from server", async () => {
    fetcher.mockResolvedValue(
      failure("진행 프로젝트 1건 / 소속 인력 3명", "PARTNER_IN_USE", 409),
    );
    render(
      <PartnerDisableDialog
        rows={[row]}
        onCancel={vi.fn()}
        onChanged={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("소속 인력 3명");
    expect(
      screen.queryByRole("button", { name: "사용 중지" }),
    ).not.toBeInTheDocument();
  });
});
describe("거래처 목록과 상세", () => {
  it("reads sales part values from the shared currentStandard response", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      url.startsWith("/api/sales-parts?")
        ? response({
            items: [
              {
                bsn_part_id: "42",
                bsn_part_nm: "제품 영업",
                hq_yn: "N",
                use_yn: "Y",
                sort_seq: 1,
                referenceMonth: "2026-10",
                currentStandard: {
                  part_std_hist_id: "7",
                  aply_bgn_ym: "2026-10",
                  aply_end_ym: null,
                  ldr_nm: "파트장 이름",
                  prft_dstrb_rt: 42,
                  bsn_fee_amt: 1700000,
                  status: "적용 중",
                },
                scheduledChanges: [],
                peopleCount: 2,
                projectCount: 1,
              },
            ],
            total: 1,
            page: 1,
            size: 50,
          })
        : original(url, init),
    );
    render(<UI15Screen />);
    await screen.findByText("국민은행 …8901");
    fireEvent.click(screen.getByRole("tab", { name: "영업 파트" }));
    const part = await screen.findByTestId("sales-part-row-42");
    expect(within(part).getByText("파트장 이름")).toBeInTheDocument();
    expect(within(part).getByText("42")).toBeInTheDocument();
    expect(within(part).getByText("1700000")).toBeInTheDocument();
    expect(within(part).getByText("2026-10")).toBeInTheDocument();
  });
  it("uses list API and same disable dialog from selection and detail", async () => {
    render(<UI15Screen />);
    fireEvent.click(await screen.findByLabelText("협력사 B 선택"));
    fireEvent.click(screen.getByTestId("partner-disable-open"));
    expect(screen.getByTestId("partner-disable-dialog")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    fireEvent.click(screen.getByTestId("partner-detail-2"));
    await screen.findByText("012-345-678901");
    fireEvent.click(screen.getByTestId("partner-detail-disable"));
    expect(screen.getByTestId("partner-disable-dialog")).toBeInTheDocument();
    expect(
      fetcher.mock.calls.filter(([url]) => url === "/api/partners/disable"),
    ).toHaveLength(0);
  });
  it("read-only mobile hides all mutation and export controls", async () => {
    mobile = true;
    render(<UI15Screen />);
    await screen.findByText("국민은행 …8901");
    expect(
      screen.queryByTestId("partner-disable-open"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("엑셀 다운로드")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("협력사 B 선택")).not.toBeInTheDocument();
  });
  it("re-enables stopped partner through selected id", async () => {
    fetcher.mockImplementation(async (url: string) =>
      url === "/api/partners/2"
        ? response({ ...row, use_yn: "N" })
        : response({ ...row, use_yn: "Y" }),
    );
    const changed = vi.fn();
    render(
      <PartnerDetailDrawer id="2" onClose={vi.fn()} onChanged={changed} />,
    );
    fireEvent.click(await screen.findByRole("button", { name: "다시 사용" }));
    await waitFor(() =>
      expect(changed).toHaveBeenCalledWith("다시 사용하도록 변경했습니다."),
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/partners/2/enable",
      expect.objectContaining({ method: "POST" }),
    );
  });
  it("empty and failed lists are not fabricated rows", async () => {
    fetcher.mockImplementation(async (url: string) =>
      url.startsWith("/api/codes/")
        ? response([])
        : response({ items: [], total: 0, page: 1, size: 50 }),
    );
    render(<UI15Screen />);
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(screen.queryByText("협력사 B")).not.toBeInTheDocument();
  });
});
describe("다운로드 범위", () => {
  it.each(["partners", "sales-parts"] as const)(
    "%s cancellation sends no request",
    (resource) => {
      render(
        <PartnerExportButton
          resource={resource}
          columns={["ptnr_nm"]}
          ids={["1", "2"]}
          total={105}
        />,
      );
      fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
      expect(screen.getByLabelText("선택한 2건만")).toBeChecked();
      expect(screen.getByLabelText("조회된 전체 105건")).toBeInTheDocument();
      fireEvent.click(screen.getByRole("button", { name: "취소" }));
      expect(fetcher).not.toHaveBeenCalled();
    },
  );
  it("selected export uses repeated ids and columns without page", async () => {
    fetcher.mockResolvedValue(failure());
    render(
      <PartnerExportButton
        filters={{ q: "협력", useYn: "Y" }}
        columns={["ptnr_nm", "paymentAccount"]}
        ids={["1", "2"]}
        total={105}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    fireEvent.click(screen.getByRole("button", { name: "내려받기" }));
    await screen.findByRole("alert");
    const url = new URL(fetcher.mock.calls[0][0], "https://example.test");
    expect(url.searchParams.getAll("columns")).toEqual([
      "ptnr_nm",
      "paymentAccount",
    ]);
    expect(url.searchParams.getAll("ids")).toEqual(["1", "2"]);
    expect(url.searchParams.has("page")).toBe(false);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
  it("no selection downloads all immediately and reports failure", async () => {
    fetcher.mockResolvedValue(failure());
    render(<PartnerExportButton columns={["ptnr_nm"]} ids={[]} total={105} />);
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await screen.findByRole("alert");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(fetcher.mock.calls[0][0]).not.toContain("ids=");
  });
  it("all range excludes selection ids", async () => {
    fetcher.mockResolvedValue(failure());
    render(
      <PartnerExportButton columns={["ptnr_nm"]} ids={["2"]} total={105} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    fireEvent.click(screen.getByLabelText("조회된 전체 105건"));
    fireEvent.click(screen.getByRole("button", { name: "내려받기" }));
    await screen.findByRole("alert");
    expect(fetcher.mock.calls[0][0]).not.toContain("ids=");
  });
});
it("mapping save highlights recheck without making an automatic request", async () => {
  const recheck = vi.fn();
  render(
    <PartnerMappingResolution
      issue={{
        rawValue: "협력업체",
        fix: { type: "MAPPING", group: "PARTNER_KIND" },
      }}
      upldId="7"
      onRecheck={recheck}
    />,
  );
  fireEvent.click(screen.getByRole("button", { name: "대응 등록" }));
  await screen.findByRole("option", { name: "협력사" });
  fireEvent.change(screen.getByLabelText("코드"), {
    target: { value: "VEND" },
  });
  fireEvent.change(screen.getByLabelText("주/보조"), {
    target: { value: "MAIN" },
  });
  fireEvent.click(screen.getByRole("button", { name: "저장" }));
  expect(
    await screen.findByText("대응을 저장했습니다. 다시 검사해 주세요."),
  ).toBeInTheDocument();
  expect(recheck).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "다시 검사" }));
  expect(recheck).toHaveBeenCalledOnce();
});
it("mapping keeps label/group read only and requires explicit MAIN or SUB", async () => {
  const saved = vi.fn();
  render(
    <PartnerMappingDialog
      rawValue="협력업체"
      group="PARTNER_KIND"
      onCancel={vi.fn()}
      onSaved={saved}
    />,
  );
  expect(screen.getByLabelText("엑셀 표기")).toHaveAttribute("readonly");
  expect(screen.getByLabelText("그룹")).toHaveAttribute("readonly");
  await screen.findByRole("option", { name: "협력사" });
  fireEvent.change(screen.getByLabelText("코드"), {
    target: { value: "VEND" },
  });
  expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
  expect(screen.getByLabelText("주/보조")).toHaveValue("");
  fireEvent.change(screen.getByLabelText("주/보조"), {
    target: { value: "SUB" },
  });
  fireEvent.click(screen.getByRole("button", { name: "저장" }));
  await waitFor(() => expect(saved).toHaveBeenCalledOnce());
  const call = fetcher.mock.calls.find(
    ([url]) => url === "/api/admin/excel-maps",
  );
  expect(JSON.parse(call![1].body)).toMatchObject({
    excel_lbl_nm: "협력업체",
    trns_cd_grp_id: "PARTNER_KIND",
    trns_cd: "VEND",
    map_role_cd: "SUB",
  });
});
