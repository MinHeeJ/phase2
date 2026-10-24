import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI15Screen from "../partners/UI15Screen";
import { emptyPartner } from "../partners/PartnerForm";
import type {
  PartnerDetail,
  PartnerListItem,
  PartnerChange,
} from "../partners/client";
import { useSharedPreferences } from "../shared/preferences";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failed = (status: number) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code: status === 404 ? "NOT_FOUND" : "SERVER_ERROR",
      message:
        status === 404 ? "거래처가 없습니다." : "서버 오류가 발생했습니다.",
      requestId: "enable-request",
    }),
    { status },
  );
let row: PartnerDetail;
let history: PartnerChange[];
let fetcher: ReturnType<typeof vi.fn>;
let enable: () => Promise<Response>;
let failList: boolean;
const listItem = (): PartnerListItem => ({
  ptnr_id: row.ptnr_id,
  ptnr_no: row.ptnr_no,
  ptnr_nm: row.ptnr_nm,
  ptnr_se_cd: row.ptnr_se_cd,
  biz_reg_no: row.biz_reg_no,
  ceo_nm: null,
  indty_cd: null,
  chrg_nm: null,
  chrg_tel_no: null,
  txinv_email_addr: row.txinv_email_addr,
  use_yn: row.use_yn,
  kindName: "고객사",
  industryName: null,
  paymentAccount: null,
  projectCount: 0,
  assignmentPeopleCount: 0,
});
beforeEach(() => {
  localStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  row = {
    ...emptyPartner,
    ptnr_id: "41",
    ptnr_no: "C-041",
    ptnr_nm: "고객사 A",
    ptnr_se_cd: "CUST",
    biz_reg_no: "123-45-67890",
    txinv_email_addr: "tax@company.co.kr",
    use_yn: "N",
  };
  history = [];
  failList = false;
  enable = async () => {
    row = { ...row, use_yn: "Y" };
    history = [
      {
        changedAt: "2026-10-23T10:30:00",
        column: "use_yn",
        itemName: "사용 여부",
        before: "중지",
        after: "사용",
        changeType: "UPDATE",
        actorName: "기본 사용자",
      },
    ];
    return response(row);
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/codes/"))
      return response(
        url.endsWith("PARTNER_KIND") ? [{ code: "CUST", name: "고객사" }] : [],
      );
    if (
      url === `/api/partners/${row.ptnr_id}/enable` &&
      init?.method === "POST"
    )
      return enable();
    if (url === `/api/partners/${row.ptnr_id}/history`)
      return response(history);
    if (url === `/api/partners/${row.ptnr_id}`) return response(row);
    if (url.startsWith("/api/partners?")) {
      if (failList) return failed(500);
      const use = new URLSearchParams(url.split("?")[1]).get("useYn");
      const items = !use || use === row.use_yn ? [listItem()] : [];
      return response({ items, total: items.length, page: 1, size: 50 });
    }
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const writes = () => fetcher.mock.calls.filter((c) => c[1]?.method === "POST");
async function openStopped() {
  render(<UI15Screen />);
  await screen.findByText("조건에 맞는 결과가 없습니다.");
  fireEvent.change(screen.getByLabelText("사용 여부"), {
    target: { value: "N" },
  });
  fireEvent.click(screen.getByTestId("partner-query"));
  fireEvent.click(await screen.findByTestId("partner-open-41"));
  await screen.findByText("C-041");
  fireEvent.click(screen.getByRole("button", { name: "더보기" }));
}
function activate() {
  fireEvent.click(screen.getByRole("menuitem", { name: "다시 사용" }));
}

describe("SCR-UI-15 partner enable", () => {
  it("does not write on opening and shows re-enable only for stopped rows", async () => {
    await openStopped();
    expect(
      screen.getByRole("menuitem", { name: "다시 사용" }).className,
    ).toContain("writeActions");
    expect(
      screen.queryByRole("menuitem", { name: "사용 중지" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText(/사용을 중지한 거래처입니다/)).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("enables selected identity, refreshes the stopped list, returns to active list and shows history", async () => {
    await openStopped();
    activate();
    expect(await screen.findByText("다시 사용합니다.")).toBeVisible();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByTestId("partner-row-41")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(1);
    expect(writes()[0][0]).toBe("/api/partners/41/enable");
    expect(writes()[0][1]?.body).toBeUndefined();
    fireEvent.change(screen.getByLabelText("사용 여부"), {
      target: { value: "Y" },
    });
    fireEvent.click(screen.getByTestId("partner-query"));
    const activeRow = await screen.findByTestId("partner-row-41");
    expect(within(activeRow).getByText("사용").className).toContain("success");
    fireEvent.click(screen.getByTestId("partner-open-41"));
    await screen.findByText("C-041");
    expect(
      screen.queryByText(/사용을 중지한 거래처입니다/),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "더보기" }));
    expect(screen.getByRole("menuitem", { name: "사용 중지" })).toBeVisible();
    expect(
      screen.queryByRole("menuitem", { name: "다시 사용" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("menuitem", { name: "변경 이력 보기" }));
    expect(await screen.findByText("기본 사용자")).toBeVisible();
    expect(screen.getByTestId("partner-history-panel")).toHaveTextContent(
      "사용 여부중지사용기본 사용자",
    );
  });
  it("blocks duplicate requests and closing while pending", async () => {
    let release!: (value: Response) => void;
    enable = () =>
      new Promise<Response>((done) => {
        release = done;
      });
    await openStopped();
    activate();
    fireEvent.click(screen.getByRole("button", { name: "더보기" }));
    expect(screen.getByTestId("partner-state-action")).toBeDisabled();
    fireEvent.click(screen.getByTestId("partner-state-action"));
    expect(screen.getByRole("button", { name: "상세 닫기" })).toBeDisabled();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(writes()).toHaveLength(1);
    row = { ...row, use_yn: "Y" };
    release(response(row));
    await screen.findByText("다시 사용합니다.");
  });
  it("keeps 404 inside the drawer and recovery only reads, never repeats POST", async () => {
    enable = async () => failed(404);
    await openStopped();
    activate();
    const dialog = screen.getByRole("dialog");
    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent("거래처가 없습니다.");
    expect(alert).toHaveTextContent("문의 번호 enable-request");
    expect(screen.queryByText("다시 사용합니다.")).not.toBeInTheDocument();
    fireEvent.click(within(alert).getByText("다시 시도"));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(writes()).toHaveLength(1);
  });
  it("does not automatically replay an uncertain network write", async () => {
    enable = async () => {
      throw new Error("offline");
    };
    await openStopped();
    activate();
    const alert = await within(screen.getByRole("dialog")).findByRole("alert");
    expect(alert).toHaveTextContent(
      "저장 결과를 다시 조회한 뒤 확인하고 직접 저장해 주세요.",
    );
    fireEvent.click(screen.getByRole("button", { name: "더보기" }));
    expect(screen.getByTestId("partner-state-action")).toBeDisabled();
    fireEvent.click(within(alert).getByText("다시 시도"));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(writes()).toHaveLength(1);
  });
  it("reports post-commit reload failure without false success and recovers with GET", async () => {
    const normal = enable;
    enable = async () => {
      const result = await normal();
      failList = true;
      return result;
    };
    await openStopped();
    activate();
    const dialog = screen.getByRole("dialog");
    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent(
      "저장 후 조회에 실패했습니다. 현재 상태를 확인하세요.",
    );
    expect(screen.queryByText("다시 사용합니다.")).not.toBeInTheDocument();
    failList = false;
    fireEvent.click(within(alert).getByText("다시 시도"));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(writes()).toHaveLength(1);
  });
});
