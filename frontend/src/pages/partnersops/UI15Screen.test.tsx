import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI15Screen from "./UI15Screen";
import { PartnerActivity } from "./PartnerActivity";
import { PartnerStatusActions, PartnerExportButton } from "./PartnerActions";
import { PartnerMappingDialog } from "./PartnerMappingDialog";
import { listPartnerOptions, partnerExportPath } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failed = (code = "INVALID_INPUT", status = 400) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code,
      message: "요청 실패",
      field: "ids",
      requestId: "server-request",
    }),
    { status },
  );
const rows = [
  { ptnr_id: "9001", ptnr_nm: "고객 A", use_yn: "Y" as const },
  { ptnr_id: "9002", ptnr_nm: "협력사 B", use_yn: "Y" as const },
  { ptnr_id: "9003", ptnr_nm: "둘 다", use_yn: "Y" as const },
];
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetcher = vi.fn(async (url: string) => {
    if (url.startsWith("/api/codes/"))
      return response([
        { code: "SI", name: "SI", attr1: null, attr2: null, attr3: null },
      ]);
    if (url.includes("/assignments") || url.includes("/history"))
      return response([]);
    if (url.startsWith("/api/partners?"))
      return response({ items: rows, total: 120, page: 1, size: 50 });
    return response(undefined);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe("UI-15 거래처 조회와 상태", () => {
  it("renders the route screen and uses server total instead of page length", async () => {
    render(<UI15Screen />);
    expect(
      await screen.findByRole("button", { name: "고객 A" }),
    ).toBeInTheDocument();
    expect(screen.getByText("총 120건")).toBeInTheDocument();
    expect(screen.getByTestId("partner-screen")).toHaveAttribute(
      "data-screen-id",
      "SCR-UI-15",
    );
    fireEvent.click(screen.getByRole("button", { name: "고객 A" }));
    expect(screen.getByTestId("partner-detail")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("partner-changes-tab"));
    expect(
      await screen.findByText("아직 바꾼 기록이 없습니다."),
    ).toBeInTheDocument();
  });
  it("keeps failed reads distinct from empty and retries", async () => {
    fetcher.mockResolvedValueOnce(failed());
    render(<PartnerActivity id="9002" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("요청 실패");
    expect(
      screen.queryByText("지금 투입 중인 내역이 없습니다."),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(
      await screen.findByText("지금 투입 중인 내역이 없습니다."),
    ).toBeInTheDocument();
  });
  it("renders loading and real assignment and history values", async () => {
    fetcher.mockResolvedValueOnce(
      response([
        {
          projectName: "프로젝트 A",
          developerName: "가",
          affiliation: "협력사 B",
          jobCode: "BE",
          jobName: "백엔드",
          assignmentRate: 1,
          endDate: "2026-10-31",
        },
      ]),
    );
    render(<PartnerActivity id="9002" />);
    expect(screen.getByLabelText("불러오는 중")).toBeInTheDocument();
    expect(await screen.findByText("협력사 B")).toBeInTheDocument();
    fetcher.mockResolvedValueOnce(
      response([
        {
          changedAt: "2026-10-22",
          itemName: "계좌번호",
          column: "acnt_no",
          before: "6789",
          after: "4321",
          actorName: "기본 사용자",
          changeType: "UPDATE",
        },
      ]),
    );
    fireEvent.click(screen.getByRole("tab", { name: "변경 이력" }));
    expect(await screen.findByText("6789")).toBeInTheDocument();
    expect(screen.getByText("기본 사용자")).toBeInTheDocument();
  });
  it("does not preview by writing and cancel leaves server untouched", async () => {
    render(<PartnerStatusActions rows={rows} onChanged={vi.fn()} />);
    fireEvent.click(screen.getByTestId("partner-disable-open"));
    expect(fetcher).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "기록은 그대로 남습니다.",
    );
    fireEvent.click(screen.getByTestId("partner-disable-cancel"));
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("shows two complete and one excluded only after server confirmation", async () => {
    const reload = vi.fn();
    fetcher.mockResolvedValueOnce(
      response({
        disabledIds: ["9001", "9003"],
        blocked: [
          {
            id: "9002",
            name: "협력사 B",
            projectCount: 0,
            peopleCount: 3,
            reason: "소속 인력 3명",
          },
        ],
      }),
    );
    render(<PartnerStatusActions rows={rows} onChanged={reload} />);
    fireEvent.click(screen.getByTestId("partner-disable-open"));
    fireEvent.click(screen.getByTestId("partner-disable-confirm"));
    expect(await screen.findByTestId("partner-blocked-9002")).toHaveTextContent(
      "소속 인력 3명",
    );
    expect(reload).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({
      ids: ["9001", "9002", "9003"],
    });
  });
  it("all blocked keeps disabledIds empty and explains refusal", async () => {
    fetcher.mockResolvedValueOnce(
      response({
        disabledIds: [],
        blocked: [
          {
            id: "9001",
            name: "고객 A",
            projectCount: 1,
            peopleCount: 0,
            reason: "진행 프로젝트",
          },
        ],
      }),
    );
    const reload = vi.fn();
    render(<PartnerStatusActions rows={rows} onChanged={reload} />);
    fireEvent.click(screen.getByTestId("partner-disable-open"));
    fireEvent.click(screen.getByTestId("partner-disable-confirm"));
    expect(
      await screen.findByRole("heading", { name: "사용을 중지할 수 없습니다" }),
    ).toBeInTheDocument();
    expect(reload).not.toHaveBeenCalled();
  });
  it("single conflict is not success and cannot be silently resent", async () => {
    fetcher.mockResolvedValueOnce(failed("PARTNER_IN_USE", 409));
    render(<PartnerStatusActions rows={[rows[0]]} onChanged={vi.fn()} />);
    fireEvent.click(screen.getByTestId("partner-disable-open"));
    fireEvent.click(screen.getByTestId("partner-disable-confirm"));
    expect(
      await screen.findByRole("heading", { name: "사용을 중지할 수 없습니다" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByTestId("partner-disable-confirm"),
    ).not.toBeInTheDocument();
  });
  it("already stopped selection is local and enable calls selected string id", async () => {
    const reload = vi.fn();
    render(
      <PartnerStatusActions
        rows={[{ ...rows[0], use_yn: "N" }]}
        onChanged={reload}
      />,
    );
    fireEvent.click(screen.getByTestId("partner-disable-open"));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "이미 중지된 거래처입니다.",
    );
    expect(fetcher).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("partner-disable-cancel"));
    fireEvent.click(screen.getByTestId("partner-enable"));
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    expect(fetcher.mock.calls[0][0]).toBe("/api/partners/9001/enable");
  });
  it("network uncertainty preserves confirmation without automatic retransmission", async () => {
    fetcher.mockRejectedValueOnce(new TypeError("network"));
    render(<PartnerStatusActions rows={rows} onChanged={vi.fn()} />);
    fireEvent.click(screen.getByTestId("partner-disable-open"));
    fireEvent.click(screen.getByTestId("partner-disable-confirm"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "저장 결과를 다시 조회",
    );
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
  it("mobile actions carry only the responsive action container, not a PC-only page guard", () => {
    render(<PartnerStatusActions rows={rows} onChanged={vi.fn()} />);
    expect(screen.getByTestId("partner-status-actions").className).toContain(
      "desktopActions",
    );
  });
});
describe("UI-15 파일과 코드 대응", () => {
  it.each(["partners", "sales-parts"] as const)(
    "%s selection defaults to selected; whole export omits ids",
    async (resource) => {
      render(
        <PartnerExportButton
          resource={resource}
          filters={{ q: "고객" }}
          columns={["ptnr_nm", "paymentAccount"]}
          ids={["9001", "9002"]}
          total={120}
        />,
      );
      fireEvent.click(screen.getByTestId("partner-export-open"));
      expect(screen.getByLabelText("선택한 2건만")).toBeChecked();
      expect(fetcher).not.toHaveBeenCalled();
      fireEvent.click(screen.getByLabelText("조회된 전체 120건"));
      fetcher.mockResolvedValueOnce(failed());
      fireEvent.click(screen.getByTestId("partner-export-confirm"));
      await screen.findByRole("alert");
      const url = new URL(fetcher.mock.calls[0][0], "https://test.invalid");
      expect(url.pathname).toBe(`/api/${resource}/export`);
      expect(url.searchParams.has("ids")).toBe(false);
      expect(url.searchParams.getAll("columns")).toEqual([
        "ptnr_nm",
        "paymentAccount",
      ]);
    },
  );
  it("no selection downloads immediately and rejects JSON success as a file", async () => {
    render(
      <PartnerExportButton
        filters={{ useYn: "Y" }}
        columns={["ptnr_nm"]}
        ids={[]}
        total={120}
      />,
    );
    fireEvent.click(screen.getByTestId("partner-export-open"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "파일을 내려받을 수 없습니다.",
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("options caller preserves public kind wire", async () => {
    fetcher.mockResolvedValueOnce(
      response([{ id: "9001", no: "C-9001", name: "고객 A" }]),
    );
    expect(await listPartnerOptions("CUST")).toHaveLength(1);
    expect(fetcher.mock.calls[0][0]).toBe("/api/partners/options?kind=CUST");
  });
  it("mapping requires explicit role and readonly source, then highlights recheck", async () => {
    const saved = vi.fn();
    render(
      <PartnerMappingDialog
        label="핀테크"
        group="INDUSTRY"
        onCancel={vi.fn()}
        onSaved={saved}
      />,
    );
    expect(screen.getByLabelText("엑셀 표기")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("그룹")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("주/보조")).toHaveValue("");
    await screen.findByRole("option", { name: "SI" });
    fireEvent.change(screen.getByLabelText("코드"), {
      target: { value: "SI" },
    });
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("주/보조"), {
      target: { value: "SUB" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(await screen.findByRole("status")).toHaveTextContent(
      "다시 검사해 주세요.",
    );
    expect(saved).toHaveBeenCalledTimes(1);
    const call = fetcher.mock.calls.find(
      ([url]) => url === "/api/admin/excel-maps",
    );
    expect(JSON.parse(call![1].body)).toMatchObject({
      map_role_cd: "SUB",
      trns_cd: "SI",
      excel_lbl_nm: "핀테크",
    });
  });
  it("mapping failure preserves chosen role and source", async () => {
    render(
      <PartnerMappingDialog
        label="핀테크"
        group="INDUSTRY"
        onCancel={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    await screen.findByRole("option", { name: "SI" });
    fireEvent.change(screen.getByLabelText("코드"), {
      target: { value: "SI" },
    });
    fireEvent.change(screen.getByLabelText("주/보조"), {
      target: { value: "MAIN" },
    });
    fetcher.mockResolvedValueOnce(failed());
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(screen.getAllByRole("alert").length).toBeGreaterThan(0),
    );
    expect(screen.getByLabelText("코드")).toHaveValue("SI");
    expect(screen.getByLabelText("주/보조")).toHaveValue("MAIN");
  });
  it("export path preserves column order and excludes page parameters", () => {
    const path = partnerExportPath(
      "partners",
      { q: "가" },
      ["biz_reg_no", "ptnr_nm"],
      ["9001"],
    );
    const q = new URL(path, "https://test.invalid").searchParams;
    expect(q.getAll("columns")).toEqual(["biz_reg_no", "ptnr_nm"]);
    expect(q.getAll("ids")).toEqual(["9001"]);
    expect(q.has("page")).toBe(false);
  });
});
