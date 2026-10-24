import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  PartnerDisableDialog,
  type PartnerDisableRow,
} from "../partnersops/PartnerActions";
import UI15Screen from "../partners/UI15Screen";
import { useSharedPreferences } from "../shared/preferences";

const row: PartnerDisableRow = {
  ptnr_id: "101",
  ptnr_nm: "고객 거래처",
  ptnr_se_cd: "CUST",
  use_yn: "Y",
  projectCount: 0,
};
const rows = [
  row,
  { ...row, ptnr_id: "102", ptnr_nm: "협력 거래처", ptnr_se_cd: "VEND" },
  { ...row, ptnr_id: "103", ptnr_nm: "겸용 거래처", ptnr_se_cd: "BOTH" },
];
const block = {
  id: "102",
  name: "협력 거래처",
  projectCount: 0,
  peopleCount: 3,
  reason: "먼저 연결을 이동해 주세요.",
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failure = (status: number, code: string, message: string) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code,
      message,
      field: null,
      requestId: "partner-request",
    }),
    { status, headers: { "Content-Type": "application/json" } },
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
  fetcher = vi.fn(async () => response({ disabledIds: ["101"], blocked: [] }));
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function dialog(selected = rows, reload = vi.fn(async () => {})) {
  const close = vi.fn(),
    complete = vi.fn();
  render(
    <PartnerDisableDialog
      rows={selected}
      reload={reload}
      onClose={close}
      onComplete={complete}
    />,
  );
  return { close, complete, reload };
}
const confirm = () =>
  fireEvent.click(screen.getByTestId("partner-disable-confirm"));

describe("partner disable confirmation and authoritative results", () => {
  it("shows selected names and preservation, with no write on open or cancel", () => {
    const { close } = dialog();
    expect(screen.getByText(/선택한 거래처 3건/)).toBeVisible();
    expect(
      screen.getByText("고객 거래처, 협력 거래처, 겸용 거래처"),
    ).toBeVisible();
    expect(
      screen.getByText(/기존 거래·프로젝트·투입 기록은 보존됩니다/),
    ).toBeVisible();
    expect(screen.getByTestId("partner-disable-confirm").className).toContain(
      "danger",
    );
    fireEvent.click(screen.getByTestId("partner-disable-cancel"));
    expect(close).toHaveBeenCalledOnce();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("allows a fresh server check even when all displayed customer project counts were blocked", async () => {
    const { close } = dialog([{ ...row, projectCount: 2 }]);
    expect(screen.getByText(/조회 시점에 진행 프로젝트/)).toBeVisible();
    expect(fetcher).not.toHaveBeenCalled();
    confirm();
    await waitFor(() => expect(close).toHaveBeenCalledOnce());
    expect(fetcher).toHaveBeenCalledWith(
      "/api/partners/disable",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ ids: ["101"] }),
      }),
    );
  });
  it("does not treat BOTH aggregate projects or assignment people as precise blocking counts", () => {
    dialog([{ ...row, ptnr_se_cd: "BOTH", projectCount: 2 }]);
    expect(
      screen.queryByTestId("partner-known-blocked-101"),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("partner-disable-confirm")).toBeEnabled();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("shows partial completion rather than falsely claiming every partner is blocked", async () => {
    fetcher.mockResolvedValueOnce(
      response({ disabledIds: ["101", "103"], blocked: [block] }),
    );
    const { complete, reload } = dialog();
    confirm();
    expect(await screen.findByText("중지 완료: 2건 / 제외: 1건")).toBeVisible();
    expect(
      screen.getByRole("dialog", { name: "거래처 사용 중지" }),
    ).toBeVisible();
    expect(
      screen.getByText(/협력 거래처 · 진행 프로젝트 0건 · 소속 인력 3명/),
    ).toBeVisible();
    expect(complete).toHaveBeenCalledWith("2건 사용 중지 완료 · 1건 제외");
    await waitFor(() => expect(reload).toHaveBeenCalledOnce());
    expect(
      screen.queryByTestId("partner-disable-confirm"),
    ).not.toBeInTheDocument();
  });
  it("all blocked batch shows server reasons with no remaining mutation action", async () => {
    fetcher.mockResolvedValueOnce(
      response({
        disabledIds: [],
        blocked: [
          block,
          {
            ...block,
            id: "101",
            name: "고객 거래처",
            projectCount: 2,
            peopleCount: 0,
          },
        ],
      }),
    );
    dialog(rows.slice(0, 2));
    confirm();
    expect(
      await screen.findByRole("dialog", { name: "사용을 중지할 수 없습니다" }),
    ).toBeVisible();
    expect(
      screen.getByText(/고객 거래처 · 진행 프로젝트 2건 · 소속 인력 0명/),
    ).toBeVisible();
    expect(
      screen.queryByTestId("partner-disable-confirm"),
    ).not.toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("single in-use conflict displays actual counts without inventing a preflight request", async () => {
    fetcher.mockResolvedValueOnce(
      failure(
        409,
        "PARTNER_IN_USE",
        "진행 프로젝트 0건 · 소속 인력 3명. 먼저 연결을 이동해 주세요.",
      ),
    );
    const { complete } = dialog([row]);
    confirm();
    expect(
      await screen.findByRole("dialog", { name: "사용을 중지할 수 없습니다" }),
    ).toBeVisible();
    expect(screen.getByText(/소속 인력 3명/)).toBeVisible();
    expect(screen.getByText(/partner-request/)).toBeVisible();
    expect(complete).not.toHaveBeenCalled();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("already stopped selection never writes", () => {
    dialog([{ ...row, use_yn: "N" }]);
    expect(screen.getByText("이미 중지된 거래처입니다.")).toBeVisible();
    expect(
      screen.queryByTestId("partner-disable-confirm"),
    ).not.toBeInTheDocument();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("pending disables cancel and prevents duplicate submits", async () => {
    let resolve!: (value: Response) => void;
    fetcher.mockImplementationOnce(
      () =>
        new Promise<Response>((done) => {
          resolve = done;
        }),
    );
    const { close } = dialog([row]);
    confirm();
    confirm();
    expect(screen.getByTestId("partner-disable-confirm")).toBeDisabled();
    expect(screen.getByTestId("partner-disable-cancel")).toBeDisabled();
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(close).not.toHaveBeenCalled();
    expect(fetcher).toHaveBeenCalledTimes(1);
    resolve(response({ disabledIds: ["101"], blocked: [] }));
    await waitFor(() => expect(close).toHaveBeenCalledOnce());
  });
  it("keeps successful result open until the refreshed list has arrived", async () => {
    let resolve!: () => void;
    const reload = vi.fn(
      () =>
        new Promise<void>((done) => {
          resolve = done;
        }),
    );
    const { close } = dialog([row], reload);
    confirm();
    await waitFor(() => expect(reload).toHaveBeenCalledOnce());
    expect(close).not.toHaveBeenCalled();
    resolve();
    await waitFor(() => expect(close).toHaveBeenCalledOnce());
  });
  it("failed post-save requery remains recoverable and cannot repeat the committed write", async () => {
    const reload = vi
      .fn(async () => {})
      .mockRejectedValueOnce(new Error("offline"));
    const { close } = dialog([row], reload);
    confirm();
    expect(await screen.findByText(/저장 후 조회에 실패/)).toBeVisible();
    expect(close).not.toHaveBeenCalled();
    expect(
      screen.queryByTestId("partner-disable-confirm"),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(2));
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("missing batch target remains an error instead of displaying success", async () => {
    fetcher.mockResolvedValueOnce(
      failure(404, "NOT_FOUND", "거래처가 없습니다."),
    );
    const { complete } = dialog();
    confirm();
    expect(await screen.findByText("거래처가 없습니다.")).toBeVisible();
    expect(complete).not.toHaveBeenCalled();
    expect(screen.getByTestId("partner-disable-confirm")).toBeDisabled();
  });
  it("network failure retries only the read, never the mutation automatically", async () => {
    fetcher.mockRejectedValueOnce(new Error("timeout"));
    const { reload } = dialog();
    confirm();
    await screen.findByText(/저장 결과를 다시 조회/);
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(reload).toHaveBeenCalledOnce());
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});

function screenApi() {
  let stopped = false;
  fetcher.mockImplementation(async (path: string, init?: RequestInit) => {
    if (path.startsWith("/api/codes/")) return response([]);
    if (path.endsWith("/history") || path.endsWith("/assignments"))
      return response([]);
    if (path === "/api/partners/disable") {
      expect(init?.method).toBe("POST");
      stopped = true;
      return response({ disabledIds: [row.ptnr_id], blocked: [] });
    }
    const detail = {
      ...row,
      ptnr_no: "C-101",
      biz_reg_no: "123-45-67890",
      use_yn: stopped ? "N" : "Y",
    };
    if (path.startsWith("/api/partners?"))
      return response({
        items: stopped
          ? []
          : [
              {
                ...detail,
                projectCount: 0,
                assignmentPeopleCount: 0,
                paymentAccount: null,
              },
            ],
        page: 1,
        size: 50,
        total: stopped ? 0 : 1,
      });
    if (path === "/api/partners/101") return response(detail);
    throw new Error(`Unexpected request ${path}`);
  });
}
describe("registered UI-15 disable entry points", () => {
  it("bulk confirmation re-queries and removes a stopped partner from the active list", async () => {
    screenApi();
    render(<UI15Screen />);
    fireEvent.click(await screen.findByLabelText("고객 거래처 선택"));
    fireEvent.click(screen.getByTestId("partner-disable-selected"));
    expect(
      fetcher.mock.calls.filter(([path]) => path === "/api/partners/disable"),
    ).toHaveLength(0);
    confirm();
    await screen.findByText("1건 사용 중지 완료");
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeVisible();
    expect(screen.queryByTestId("partner-row-101")).not.toBeInTheDocument();
  });
  it("detail more opens the same confirmation and Escape preserves the underlying detail", async () => {
    screenApi();
    render(<UI15Screen />);
    fireEvent.click(await screen.findByTestId("partner-open-101"));
    const detail = await screen.findByRole("dialog", { name: "거래처 상세" });
    await within(detail).findByText("C-101");
    fireEvent.click(within(detail).getByLabelText("더보기"));
    fireEvent.click(
      within(detail).getByRole("menuitem", { name: "사용 중지" }),
    );
    const confirmation = screen.getByRole("dialog", {
      name: "거래처 사용 중지",
    });
    expect(
      within(confirmation).getByTestId("partner-disable-dialog"),
    ).toHaveAttribute("data-screen-id", "SCR-UI-15-M4");
    fireEvent.keyDown(confirmation, { key: "Escape" });
    expect(
      screen.queryByTestId("partner-disable-dialog"),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "거래처 상세" })).toBeVisible();
    expect(
      fetcher.mock.calls.filter(([path]) => path === "/api/partners/disable"),
    ).toHaveLength(0);
  });
});
