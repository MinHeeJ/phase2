import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import UI15Screen from "./UI15Screen";
import {
  PartnerDisableDialog,
  ListExportButton,
  PartnerEnableButton,
} from "./PartnerActions";
import { PartnerAssignments, PartnerHistory } from "./PartnerReadPanels";
import { PartnerMappingRepair } from "./PartnerMappingDialog";
import { downloadList, type PartnerRow } from "./client";

const row: PartnerRow = {
  ptnr_id: "101",
  ptnr_no: "C-101",
  ptnr_se_cd: "CUST",
  ptnr_nm: "고객사 A",
  biz_reg_no: "123-45-67890",
  use_yn: "Y",
  kindName: "고객사",
  paymentAccount: null,
  projectCount: 0,
  assignmentPeopleCount: 0,
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failed = (
  message = "요청에 실패했습니다.",
  status = 500,
  code = "SERVER_ERROR",
) =>
  new Response(
    JSON.stringify({
      success: false,
      code,
      message,
      requestId: "test-request",
      field: null,
    }),
    { status, headers: { "Content-Type": "application/json" } },
  );
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.includes("/api/codes/"))
      return response([
        { code: "CUST", name: "고객사" },
        { code: "SI", name: "SI" },
      ]);
    if (path.includes("/api/partners?"))
      return response({ items: [row], total: 101, page: 1, size: 50 });
    if (path.endsWith("/assignments") || path.endsWith("/history"))
      return response([]);
    if (path.endsWith("/disable"))
      return response({
        disabledIds: JSON.parse(init?.body as string).ids,
        blocked: [],
      });
    if (path.endsWith("/enable")) return response({ ...row, use_yn: "Y" });
    if (path === "/api/admin/excel-maps") return response(null);
    return response({
      ...row,
      paymentAccount: undefined,
      projectCount: undefined,
      assignmentPeopleCount: undefined,
    });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("UI-15 partner lifecycle", () => {
  it("renders API data and the selected row opens a 600px drawer with read tabs", async () => {
    render(<UI15Screen />);
    fireEvent.click(await screen.findByRole("button", { name: "고객사 A" }));
    expect(
      await screen.findByRole("dialog", { name: "거래처 상세" }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "투입 내역" }));
    expect(
      await screen.findByText("지금 투입 중인 내역이 없습니다."),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "변경 이력" }));
    expect(
      await screen.findByText("아직 바꾼 기록이 없습니다."),
    ).toBeInTheDocument();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(
      screen.queryByRole("dialog", { name: "거래처 상세" }),
    ).not.toBeInTheDocument();
  });

  it("bulk confirmation displays preservation and only posts on confirm", async () => {
    render(<UI15Screen />);
    fireEvent.click(
      await screen.findByRole("checkbox", { name: "고객사 A 선택" }),
    );
    fireEvent.click(screen.getByTestId("partner-bulk-disable"));
    expect(
      screen.getByText(/기존 거래·프로젝트·투입 기록은 보존됩니다/),
    ).toBeInTheDocument();
    expect(
      fetcher.mock.calls.filter(([path]) => path.endsWith("/disable")),
    ).toHaveLength(0);
    fireEvent.click(screen.getByTestId("partner-disable-confirm"));
    expect(await screen.findByText("1건 사용 중지 완료")).toBeInTheDocument();
    expect(
      fetcher.mock.calls.find(([path]) => path.endsWith("/disable"))?.[1],
    ).toMatchObject({ method: "POST", body: JSON.stringify({ ids: ["101"] }) });
  });
  it("detail more uses identical disable dialog", async () => {
    render(<UI15Screen />);
    fireEvent.click(await screen.findByRole("button", { name: "고객사 A" }));
    await screen.findByTestId("partner-basic-info");
    fireEvent.click(screen.getByTestId("partner-more"));
    fireEvent.click(screen.getByTestId("partner-detail-disable"));
    expect(screen.getByTestId("partner-disable-dialog")).toBeInTheDocument();
  });
  it("known project counts are advisory until the user confirms a server check", () => {
    render(
      <PartnerDisableDialog
        rows={[{ ...row, projectCount: 2 }]}
        onClose={vi.fn()}
        reload={async () => {}}
        onComplete={vi.fn()}
      />,
    );
    expect(
      screen.getByRole("heading", { name: "거래처 사용 중지" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/진행 프로젝트 2건/)).toBeInTheDocument();
    expect(screen.getByTestId("partner-disable-confirm")).toBeEnabled();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("partial result keeps latest reasons in the same dialog", async () => {
    fetcher.mockResolvedValueOnce(
      response({
        disabledIds: ["101", "103"],
        blocked: [
          {
            id: "102",
            name: "협력사 B",
            projectCount: 0,
            peopleCount: 3,
            reason: "인력을 이동해 주세요.",
          },
        ],
      }),
    );
    const completed = vi.fn();
    render(
      <PartnerDisableDialog
        rows={[row, { ...row, ptnr_id: "102" }, { ...row, ptnr_id: "103" }]}
        onClose={vi.fn()}
        reload={async () => {}}
        onComplete={completed}
      />,
    );
    fireEvent.click(screen.getByTestId("partner-disable-confirm"));
    expect(await screen.findByText(/소속 인력 3명/)).toBeInTheDocument();
    expect(completed).toHaveBeenCalledWith("2건 사용 중지 완료 · 1건 제외");
    expect(
      screen.queryByTestId("partner-disable-confirm"),
    ).not.toBeInTheDocument();
  });
  it("all blocked server result displays cannot disable without another POST", async () => {
    fetcher.mockResolvedValueOnce(
      response({
        disabledIds: [],
        blocked: [
          {
            id: "101",
            name: "고객사 A",
            projectCount: 1,
            peopleCount: 0,
            reason: "진행 중",
          },
        ],
      }),
    );
    render(
      <PartnerDisableDialog
        rows={[row]}
        onClose={vi.fn()}
        reload={async () => {}}
        onComplete={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId("partner-disable-confirm"));
    expect(
      await screen.findByRole("heading", { name: "사용을 중지할 수 없습니다" }),
    ).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("single 409 displays latest server counts rather than fabricated people counts", async () => {
    fetcher.mockResolvedValueOnce(
      failed(
        "진행 프로젝트 0건 · 소속 인력 3명. 먼저 연결을 이동해 주세요.",
        409,
        "PARTNER_IN_USE",
      ),
    );
    render(
      <PartnerDisableDialog
        rows={[row]}
        onClose={vi.fn()}
        reload={async () => {}}
        onComplete={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId("partner-disable-confirm"));
    expect(await screen.findByText(/소속 인력 3명/)).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("already stopped selection has only close and no request", () => {
    render(
      <PartnerDisableDialog
        rows={[{ ...row, use_yn: "N" }]}
        onClose={vi.fn()}
        reload={async () => {}}
        onComplete={vi.fn()}
      />,
    );
    expect(screen.getByText("이미 중지된 거래처입니다.")).toBeInTheDocument();
    expect(
      screen.queryByTestId("partner-disable-confirm"),
    ).not.toBeInTheDocument();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("does not treat BOTH supplier project totals as customer disable blockers", () => {
    render(
      <PartnerDisableDialog
        rows={[{ ...row, ptnr_se_cd: "BOTH", projectCount: 2 }]}
        onClose={vi.fn()}
        reload={async () => {}}
        onComplete={vi.fn()}
      />,
    );
    expect(screen.getByTestId("partner-disable-confirm")).toBeEnabled();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("timeout never resends mutation through retry", async () => {
    fetcher.mockRejectedValueOnce(new Error("timeout"));
    const reload = vi.fn(async () => {});
    render(
      <PartnerDisableDialog
        rows={[row]}
        onClose={vi.fn()}
        reload={reload}
        onComplete={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId("partner-disable-confirm"));
    expect(
      await screen.findByText(/저장 결과를 다시 조회/),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(reload).toHaveBeenCalled());
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("enable calls selected id once and refreshes", async () => {
    const reload = vi.fn(async () => {});
    const complete = vi.fn();
    render(
      <PartnerEnableButton id="104" reload={reload} onComplete={complete} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "다시 사용" }));
    await waitFor(() => expect(reload).toHaveBeenCalled());
    expect(fetcher).toHaveBeenCalledWith(
      "/api/partners/104/enable",
      expect.objectContaining({ method: "POST" }),
    );
  });
  it("empty and failed lists do not show invented rows and support read retry", async () => {
    fetcher.mockImplementation(async (path: string) =>
      path.includes("/api/codes/")
        ? response([])
        : response({ items: [], total: 0, page: 1, size: 50 }),
    );
    render(<UI15Screen />);
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("partner-bulk-disable")).toBeDisabled();
  });
  it("failed assignments have a read-only retry", async () => {
    fetcher.mockResolvedValueOnce(failed()).mockResolvedValueOnce(
      response([
        {
          projectName: "프로젝트",
          developerName: "개발자",
          affiliation: "자사",
          jobCode: "BE",
          jobName: "백엔드",
          assignmentRate: 0.5,
          endDate: "2026-12-31",
        },
      ]),
    );
    render(<PartnerAssignments id="101" />);
    await screen.findByText("요청에 실패했습니다.");
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("50%")).toBeInTheDocument();
    expect(screen.getByText("자사")).toBeInTheDocument();
  });
  it("history displays localized items and masked values only from API", async () => {
    fetcher.mockResolvedValueOnce(
      response([
        {
          changedAt: "2026-10-22T09:00:00",
          column: "acnt_no",
          itemName: "계좌번호",
          before: "1234",
          after: "5678",
          changeType: "UPDATE",
          actorName: "기본 사용자",
        },
      ]),
    );
    render(<PartnerHistory id="101" />);
    expect(await screen.findByText("계좌번호")).toBeInTheDocument();
    expect(screen.getByText("기본 사용자")).toBeInTheDocument();
    expect(screen.getByText("5678")).toBeInTheDocument();
  });
  it("selection and mutations carry the mobile-hidden class while export remains", async () => {
    render(<UI15Screen />);
    expect(
      (await screen.findByRole("checkbox", { name: "고객사 A 선택" }))
        .className,
    ).toContain("mutate");
    expect(
      screen.getByTestId("partner-bulk-disable").parentElement?.className,
    ).toContain("mutate");
    expect(screen.getByTestId("partners-export").className).not.toContain(
      "mutate",
    );
  });
});

describe("export and mapping", () => {
  it("selected rows open scope dialog with all-filter count", () => {
    render(
      <ListExportButton
        filter={{ q: "검색" }}
        columns={["ptnr_nm"]}
        selectedIds={["101", "102"]}
        total={101}
      />,
    );
    fireEvent.click(screen.getByText("엑셀 다운로드"));
    expect(screen.getByLabelText("선택 2건")).toBeChecked();
    expect(screen.getByLabelText("조회 전체 101건")).toBeInTheDocument();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("no selection downloads immediately and failed file is not success", async () => {
    fetcher.mockResolvedValueOnce(failed("열을 확인하세요.", 400));
    render(
      <ListExportButton
        filter={{ q: "검색" }}
        columns={["ptnr_nm"]}
        selectedIds={[]}
        total={101}
      />,
    );
    fireEvent.click(screen.getByText("엑셀 다운로드"));
    expect(await screen.findByText("열을 확인하세요.")).toBeInTheDocument();
    expect(
      screen.queryByTestId("partner-export-scope"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("엑셀 파일을 내려받았습니다."),
    ).not.toBeInTheDocument();
    expect(fetcher.mock.calls[0][0]).toContain("q=%EA%B2%80%EC%83%89");
  });
  it("query arrays use form explode and files preserve server filename", async () => {
    const blob = {
      slice: () => ({
        arrayBuffer: async () => new Uint8Array([80, 75]).buffer,
      }),
    };
    fetcher.mockResolvedValueOnce({
      ok: true,
      headers: new Headers({
        "Content-Type":
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "Content-Disposition":
          "attachment; filename*=UTF-8''%EA%B1%B0%EB%9E%98%EC%B2%98_20261022.xlsx",
      }),
      blob: async () => blob,
    });
    const create = vi.fn(() => "blob:test"),
      revoke = vi.fn();
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: create,
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: revoke,
    });
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});
    await downloadList(
      "partners",
      { kind: "CUST" },
      ["ptnr_nm", "paymentAccount"],
      ["101", "102"],
    );
    const query = new URL(fetcher.mock.calls[0][0], "https://test.invalid")
      .searchParams;
    expect(query.getAll("ids")).toEqual(["101", "102"]);
    expect(query.getAll("columns")).toEqual(["ptnr_nm", "paymentAccount"]);
    expect(query.has("page")).toBe(false);
    expect(click).toHaveBeenCalled();
    expect(revoke).toHaveBeenCalledWith("blob:test");
  });
  it("mapping keeps label and group readonly and requires explicit role before save", async () => {
    render(
      <PartnerMappingRepair
        label="핀테크"
        group="INDUSTRY"
        recheck={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByText("대응 등록"));
    expect(screen.getByLabelText("엑셀 표기")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("그룹")).toHaveAttribute("readonly");
    expect(screen.getByRole("combobox", { name: "주/보조" })).toHaveValue("");
    expect(screen.getByTestId("mapping-save")).toBeDisabled();
    await screen.findByRole("option", { name: "SI" });
    fireEvent.change(screen.getByRole("combobox", { name: "대응 코드" }), {
      target: { value: "SI" },
    });
    fireEvent.change(screen.getByRole("combobox", { name: "주/보조" }), {
      target: { value: "SUB" },
    });
    fireEvent.submit(screen.getByTestId("partner-mapping-dialog"));
    expect(await screen.findByTestId("mapping-recheck")).toBeInTheDocument();
    const call = fetcher.mock.calls.find(
      ([path]) => path === "/api/admin/excel-maps",
    );
    expect(JSON.parse(call?.[1]?.body as string)).toMatchObject({
      excel_lbl_nm: "핀테크",
      trns_cd_grp_id: "INDUSTRY",
      trns_cd: "SI",
      map_role_cd: "SUB",
    });
  });
});
