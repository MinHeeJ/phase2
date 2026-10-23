import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { SalesPartActions } from "./SalesPartActions";
import { SalesPartDownload } from "./SalesPartDownload";
import {
  getSalesPartExport,
  listSalesPartOptions,
  type SalesPartDetail,
} from "./client";

const detail: SalesPartDetail = {
  bsn_part_id: "2",
  bsn_part_nm: "영업1",
  hq_yn: "N",
  use_yn: "Y",
  sort_seq: 1,
  referenceMonth: "2026-10",
  currentStandard: {
    part_std_hist_id: "10",
    aply_bgn_ym: "2026-01",
    aply_end_ym: null,
    ldr_nm: "파트장",
    prft_dstrb_rt: 40,
    bsn_fee_amt: 1500000,
    status: "적용 중",
  },
  scheduledChanges: [],
  peopleCount: 0,
  projectCount: 0,
  standards: [],
  lastClosedMonth: "2026-09",
  firstAllowedMonth: "2026-10",
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failed = (code: string, message: string, field: string | null = null) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code,
      message,
      field,
      requestId: "server-request",
    }),
    { status: 409 },
  );
let fetcher: ReturnType<typeof vi.fn>;
let saved: SalesPartDetail;
let refresh: ReturnType<typeof vi.fn>;
beforeEach(() => {
  saved = structuredClone(detail);
  refresh = vi.fn(async () => {});
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (!init?.method) return response(saved);
    const body = init.body ? JSON.parse(String(init.body)) : {};
    if (url.endsWith("/name"))
      saved = { ...saved, bsn_part_nm: body.bsn_part_nm };
    if (url.endsWith("/disable")) saved = { ...saved, use_yn: "N" };
    if (url.endsWith("/enable")) saved = { ...saved, use_yn: "Y" };
    if (url.endsWith("/standards"))
      saved = {
        ...saved,
        scheduledChanges: [
          {
            ...body,
            part_std_hist_id: "20",
            aply_end_ym: null,
            status: "예정",
          },
        ],
      };
    return response(saved);
  });
  vi.stubGlobal("fetch", fetcher);
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function Host({ initial = detail }: { initial?: SalesPartDetail }) {
  const [row, setRow] = useState(initial);
  return (
    <>
      <p data-testid="host-name">{row.bsn_part_nm}</p>
      <p data-testid="host-state">{row.use_yn}</p>
      <p data-testid="host-future">
        {row.scheduledChanges.map((s) => s.aply_bgn_ym).join(",")}
      </p>
      <SalesPartActions
        detail={row}
        onChanged={refresh}
        onDetailChange={setRow}
      />
    </>
  );
}
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const writes = () => fetcher.mock.calls.filter((call) => call[1]?.method);
const openName = () => {
  fireEvent.click(screen.getByRole("button", { name: "더보기" }));
  fireEvent.click(screen.getByRole("button", { name: "파트명 수정" }));
};
const submit = () =>
  fireEvent.click(screen.getByRole("button", { name: "저장" }));

describe("SCR-UI-15 영업 파트 작업", () => {
  it("opens M8 initialized from current not scheduled standard without a part-name input", () => {
    render(
      <Host
        initial={{
          ...detail,
          scheduledChanges: [
            {
              ...detail.currentStandard!,
              ldr_nm: "예정 파트장",
              prft_dstrb_rt: 45,
              status: "예정",
            },
          ],
        }}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "기준 변경" }));
    expect(screen.getByLabelText("파트장")).toHaveValue("파트장");
    expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue("40");
    expect(screen.getByLabelText("영업비 (원)")).toHaveValue("1500000");
    expect(screen.queryByLabelText("파트명")).not.toBeInTheDocument();
    expect(
      screen.getByText("기준 변경은 시스템 관리자·재무 담당만 할 수 있습니다."),
    ).toBeVisible();
  });
  it("does not invent operating defaults when current standard is absent", () => {
    render(<Host initial={{ ...detail, currentStandard: null }} />);
    fireEvent.click(screen.getByRole("button", { name: "기준 변경" }));
    expect(screen.getByLabelText("파트장")).toHaveValue("");
    expect(screen.getByLabelText("영업비 (원)")).toHaveValue("");
  });
  it.each([
    ["파트장", "", "파트장을 입력해 주세요."],
    [
      "이익 배분율 (%)",
      "100.001",
      "이익 배분율은 0~100 사이 숫자로 입력해 주세요.",
    ],
    ["이익 배분율 (%)", "-1", "이익 배분율은 0~100 사이 숫자로 입력해 주세요."],
    ["영업비 (원)", "0", "영업비를 입력해 주세요."],
    ["영업비 (원)", "1.5", "영업비를 입력해 주세요."],
    ["적용 시작월", "2026-09", "적용 시작월은 2026-10부터 고를 수 있습니다."],
  ])("validates %s before a standard request", (label, value, message) => {
    render(<Host />);
    fireEvent.click(screen.getByRole("button", { name: "기준 변경" }));
    change(label, value);
    submit();
    expect(screen.getByRole("alert")).toHaveTextContent(message);
    expect(writes()).toHaveLength(0);
  });
  it("saves allowed standards and refreshes detail and list with part/month success", async () => {
    render(<Host />);
    fireEvent.click(screen.getByRole("button", { name: "기준 변경" }));
    change("적용 시작월", "2026-11");
    change("이익 배분율 (%)", "45");
    submit();
    await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
    expect(screen.getByTestId("host-future")).toHaveTextContent("2026-11");
    expect(screen.getByRole("status")).toHaveTextContent(
      "영업1 기준을 바꿨습니다. 2026-11부터 적용됩니다.",
    );
    expect(JSON.parse(String(writes()[0][1].body))).toEqual({
      aply_bgn_ym: "2026-11",
      ldr_nm: "파트장",
      prft_dstrb_rt: 45,
      bsn_fee_amt: 1500000,
    });
    expect(fetcher).toHaveBeenCalledWith(
      "/api/sales-parts/2",
      expect.anything(),
    );
  });
  it.each(["SAME_STANDARD", "CLOSED_MONTH", "HQ_PART_LOCKED"])(
    "keeps the draft and shows server business conflict %s",
    async (code) => {
      fetcher.mockResolvedValue(
        failed(
          code,
          code === "SAME_STANDARD"
            ? "지금 기준과 같습니다. 바꿀 값을 입력해 주세요."
            : "변경할 수 없습니다.",
        ),
      );
      render(<Host />);
      fireEvent.click(screen.getByRole("button", { name: "기준 변경" }));
      change("이익 배분율 (%)", "45");
      submit();
      expect(await screen.findByRole("alert")).toHaveTextContent(
        code === "SAME_STANDARD"
          ? "지금 기준과 같습니다."
          : "변경할 수 없습니다.",
      );
      expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue("45");
      expect(refresh).not.toHaveBeenCalled();
    },
  );
  it("shows field-specific server validation and retains input", async () => {
    fetcher.mockResolvedValue(
      failed("INVALID_INPUT", "入力値エラー", "ldr_nm"),
    );
    render(<Host />);
    fireEvent.click(screen.getByRole("button", { name: "기준 변경" }));
    submit();
    await waitFor(() =>
      expect(screen.getByLabelText("파트장")).toHaveAttribute(
        "aria-invalid",
        "true",
      ),
    );
  });
  it("hides all mutations for headquarters", () => {
    render(<Host initial={{ ...detail, hq_yn: "Y" }} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
  it("hides mutations on mobile without adding role rejection", () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => ({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    );
    render(<Host />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByTestId("host-name")).toHaveTextContent("영업1");
  });
  it("same-name save only closes without a request", () => {
    render(<Host />);
    openName();
    submit();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("renames from more menu with relationship help and refresh", async () => {
    render(<Host />);
    openName();
    expect(
      screen.getByText("소속 인력·프로젝트·사업계획의 파트명도 함께 바뀝니다."),
    ).toBeVisible();
    change("파트명", "새 이름");
    submit();
    await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
    expect(screen.getByTestId("host-name")).toHaveTextContent("새 이름");
    expect(JSON.parse(String(writes()[0][1].body))).toEqual({
      bsn_part_nm: "새 이름",
    });
  });
  it.each(["", " "])("does not submit blank part name %s", (value) => {
    render(<Host />);
    openName();
    change("파트명", value);
    submit();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "파트명을 입력해 주세요.",
    );
    expect(writes()).toHaveLength(0);
  });
  it("limits name entry to thirty characters", () => {
    render(<Host />);
    openName();
    expect(screen.getByLabelText("파트명")).toHaveAttribute("maxlength", "30");
  });
  it("duplicate part name remains editable", async () => {
    fetcher.mockResolvedValue(
      failed(
        "DUPLICATE_PART_NAME",
        "같은 이름의 파트가 이미 있습니다.",
        "bsn_part_nm",
      ),
    );
    render(<Host />);
    openName();
    change("파트명", "중복");
    submit();
    await waitFor(() =>
      expect(screen.getByLabelText("파트명")).toHaveAttribute(
        "aria-invalid",
        "true",
      ),
    );
    expect(screen.getByLabelText("파트명")).toHaveValue("중복");
  });
  it("dirty cancellation keeps draft until explicitly discarded", async () => {
    const user = userEvent.setup();
    render(<Host />);
    openName();
    change("파트명", "작성중");
    await user.click(screen.getByRole("button", { name: "취소" }));
    await user.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(screen.getByLabelText("파트명")).toHaveValue("작성중");
    await user.click(screen.getByRole("button", { name: "취소" }));
    await user.click(screen.getByRole("button", { name: /^닫기$/ }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it.each([
    { peopleCount: 3, projectCount: 0 },
    { peopleCount: 0, projectCount: 1 },
  ])("shows blocked counts without writing for %j", (counts) => {
    render(<Host initial={{ ...detail, ...counts }} />);
    fireEvent.click(screen.getByRole("button", { name: "더보기" }));
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      `소속 인력 ${counts.peopleCount}명, 진행 프로젝트 ${counts.projectCount}건`,
    );
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "먼저 다른 파트로 옮겨 주세요.",
    );
    fireEvent.click(screen.getByRole("button", { name: "확인" }));
    expect(writes()).toHaveLength(0);
  });
  it("cancelling disable never posts", () => {
    render(<Host />);
    fireEvent.click(screen.getByRole("button", { name: "더보기" }));
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(writes()).toHaveLength(0);
  });
  it("confirming danger changes state and refreshes", async () => {
    render(<Host />);
    fireEvent.click(screen.getByRole("button", { name: "더보기" }));
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {
        name: "사용 중지",
      }),
    );
    await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
    expect(screen.getByTestId("host-state")).toHaveTextContent("N");
  });
  it("server-side new affiliation blocks confirmed disable and keeps active state", async () => {
    fetcher.mockResolvedValue(
      failed(
        "PART_IN_USE",
        "영업1에 소속 인력 3명, 진행 프로젝트 1건이 있습니다. 먼저 다른 파트로 옮겨 주세요.",
      ),
    );
    render(<Host />);
    fireEvent.click(screen.getByRole("button", { name: "더보기" }));
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {
        name: "사용 중지",
      }),
    );
    await screen.findByText("사용을 중지할 수 없습니다");
    expect(screen.getByTestId("host-state")).toHaveTextContent("Y");
    expect(refresh).not.toHaveBeenCalled();
  });
  it("inactive row offers enable instead of disable", async () => {
    saved = { ...detail, use_yn: "N" };
    render(<Host initial={saved} />);
    fireEvent.click(screen.getByRole("button", { name: "더보기" }));
    expect(
      screen.queryByRole("button", { name: "사용 중지" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다시 사용" }));
    await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
    expect(screen.getByRole("status")).toHaveTextContent(
      "영업1을 다시 사용합니다.",
    );
    expect(screen.getByTestId("host-state")).toHaveTextContent("Y");
  });
  it("locks submit while pending", async () => {
    fetcher.mockImplementation(() => new Promise(() => {}));
    render(<Host />);
    openName();
    change("파트명", "새 이름");
    submit();
    expect(screen.getByTestId("sales-part-edit-save")).toBeDisabled();
    expect(writes()).toHaveLength(1);
  });
});

describe("SCR-UI-15-M14 영업 파트 독립 다운로드", () => {
  const columns = ["bsn_fee_amt", "bsn_part_nm"];
  function xlsx() {
    return new Response(new Uint8Array([80, 75, 3, 4]), {
      status: 200,
      headers: {
        "Content-Type":
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent("영업파트_20261022.xlsx")}`,
      },
    });
  }
  function setupDownload() {
    fetcher.mockImplementation(async () => xlsx());
    vi.stubGlobal(
      "URL",
      class extends URL {
        static createObjectURL = vi.fn(() => "blob:test");
        static revokeObjectURL = vi.fn();
      },
    );
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  }
  it("downloads all results immediately when nothing selected, with ordered repeated keys and no page", async () => {
    setupDownload();
    render(
      <SalesPartDownload columns={columns} selectedIds={[]} total={121} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await screen.findByRole("status");
    const url = new URL(
      String(fetcher.mock.calls[0][0]),
      "https://test.invalid",
    );
    expect(url.searchParams.getAll("columns")).toEqual(columns);
    expect(url.searchParams.has("page")).toBe(false);
    expect(url.searchParams.has("ids")).toBe(false);
    expect(HTMLAnchorElement.prototype.click).toHaveBeenCalledOnce();
  });
  it("offers selection two or full total and cancel sends no request", () => {
    render(
      <SalesPartDownload
        columns={columns}
        selectedIds={["2", "4"]}
        total={121}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(screen.getByLabelText("선택한 2건만")).toBeChecked();
    expect(screen.getByLabelText("조회된 전체 121건")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("exports selected two using repeated ids", async () => {
    setupDownload();
    render(
      <SalesPartDownload
        columns={columns}
        selectedIds={["2", "4"]}
        total={121}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    fireEvent.click(screen.getByRole("button", { name: "내려받기" }));
    await screen.findByRole("status");
    expect(
      new URL(
        String(fetcher.mock.calls[0][0]),
        "https://test.invalid",
      ).searchParams.getAll("ids"),
    ).toEqual(["2", "4"]);
  });
  it("full-range choice omits selected ids", async () => {
    setupDownload();
    render(
      <SalesPartDownload
        columns={columns}
        selectedIds={["2", "4"]}
        total={121}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    fireEvent.click(screen.getByLabelText("조회된 전체 121건"));
    fireEvent.click(screen.getByRole("button", { name: "내려받기" }));
    await screen.findByRole("status");
    expect(String(fetcher.mock.calls[0][0])).not.toContain("ids=");
  });
  it("failed download keeps range and shows no success", async () => {
    fetcher.mockResolvedValue(failed("SERVER_ERROR", "파일 생성 실패"));
    render(
      <SalesPartDownload
        columns={columns}
        selectedIds={["2", "4"]}
        total={121}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    fireEvent.click(screen.getByRole("button", { name: "내려받기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "파일 생성 실패",
    );
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
  it("mobile hides download", () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => ({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    );
    render(<SalesPartDownload columns={columns} selectedIds={[]} total={0} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
  it("rejects JSON masquerading as successful export", async () => {
    fetcher.mockResolvedValue(response({}));
    await expect(getSalesPartExport(columns)).rejects.toThrow(
      "엑셀 파일 응답이 아닙니다.",
    );
  });
  it("decodes UTF-8 filename and returns a binary blob", async () => {
    fetcher.mockImplementation(async () => xlsx());
    const file = await getSalesPartExport(columns);
    expect(file.filename).toBe("영업파트_20261022.xlsx");
    expect(file.blob.size).toBe(4);
  });
  it("options client uses public relative API", async () => {
    fetcher.mockResolvedValue(response([{ id: "1", name: "본사", hq: true }]));
    expect(await listSalesPartOptions()).toEqual([
      { id: "1", name: "본사", hq: true },
    ]);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/sales-parts/options",
      expect.anything(),
    );
  });
});
