import { useState } from "react";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import {
  SalesPartActions,
  SalesPartNameDialog,
  SalesPartStandardDialog,
} from "./SalesPartActions";
import { SalesPartExportButton } from "./SalesPartExportButton";
import {
  getSalesPartExport,
  listSalesPartOptions,
  type SalesPartDetail,
} from "./client";
import styles from "./SalesPartManagement.module.css";

const standard = {
  part_std_hist_id: "20",
  aply_bgn_ym: "2026-01",
  aply_end_ym: null,
  ldr_nm: "파트장",
  prft_dstrb_rt: 40,
  bsn_fee_amt: 1500000,
  status: "적용 중" as const,
};
const part: SalesPartDetail = {
  bsn_part_id: "9007199254740993",
  bsn_part_nm: "시험 파트",
  hq_yn: "N",
  use_yn: "Y",
  sort_seq: 2,
  referenceMonth: "2026-10",
  currentStandard: standard,
  scheduledChanges: [],
  peopleCount: 0,
  projectCount: 0,
  standards: [standard],
  lastClosedMonth: "2026-09",
  firstAllowedMonth: "2026-10",
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failed = (
  code: string,
  message: string,
  field: string | null = null,
  status = 409,
) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code,
      message,
      field,
      requestId: "server-request",
    }),
    { status, headers: { "Content-Type": "application/json" } },
  );
const xlsx = () =>
  new Response(new Uint8Array([80, 75, 3, 4]), {
    headers: {
      "Content-Type":
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "Content-Disposition":
        "attachment; filename*=UTF-8''" +
        encodeURIComponent("영업파트_20261022.xlsx"),
    },
  });
let fetcher: ReturnType<typeof vi.fn>;
let saved: SalesPartDetail;
let writes: { url: string; init: RequestInit }[];
beforeEach(() => {
  saved = structuredClone(part);
  writes = [];
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/sales-parts/export?")) return xlsx();
    if (url === "/api/sales-parts/options")
      return response([
        { id: saved.bsn_part_id, name: saved.bsn_part_nm, hq: false },
      ]);
    if (init?.method && init.method !== "GET") {
      writes.push({ url, init });
      if (url.endsWith("/name"))
        saved = { ...saved, ...JSON.parse(init.body as string) };
      if (url.endsWith("/disable")) saved = { ...saved, use_yn: "N" };
      if (url.endsWith("/enable")) saved = { ...saved, use_yn: "Y" };
      if (url.endsWith("/standards")) {
        const future = {
          ...standard,
          ...JSON.parse(init.body as string),
          part_std_hist_id: "21",
          status: "예정",
        };
        saved = {
          ...saved,
          scheduledChanges: [future],
          standards: [standard, future],
        };
      }
      return response(saved);
    }
    return response(saved);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const change = (name: string, value: string) =>
  fireEvent.change(screen.getByLabelText(name), { target: { value } });
const save = () =>
  fireEvent.submit(
    screen.getByRole("button", { name: "저장" }).closest("form")!,
  );
function Harness({
  value = part,
  onListRefresh = vi.fn(async () => {}),
}: {
  value?: SalesPartDetail;
  onListRefresh?: () => Promise<unknown>;
}) {
  const [row, setRow] = useState(value);
  return (
    <>
      <h2>{row.bsn_part_nm}</h2>
      <p>{row.use_yn}</p>
      <p>예정 {row.scheduledChanges.length}건</p>
      <SalesPartActions
        part={row}
        onUpdated={setRow}
        onListRefresh={onListRefresh}
      />
    </>
  );
}
const openMore = () =>
  fireEvent.click(screen.getByRole("button", { name: "영업 파트 더보기" }));

describe("SCR-UI-15 sales part changes", () => {
  it("initializes the standard editor from current values, excludes name, and selects no month when no close exists", () => {
    render(
      <SalesPartStandardDialog
        part={{ ...part, lastClosedMonth: null, firstAllowedMonth: null }}
        reload={vi.fn()}
        onCancel={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    expect(screen.queryByLabelText("파트명")).not.toBeInTheDocument();
    expect(screen.getByLabelText("파트장")).toHaveValue("파트장");
    expect(screen.getByLabelText("이익 배분율")).toHaveValue("40");
    expect(screen.getByLabelText("영업비")).toHaveValue("1,500,000");
    expect(screen.getByLabelText("적용 시작월")).toHaveValue("");
    expect(
      screen.getByText("적용 시작월을 직접 선택해 주세요."),
    ).toBeInTheDocument();
  });
  it("saves selected part identity and refreshes detail and list with scheduled changes", async () => {
    const refresh = vi.fn(async () => {});
    render(<Harness onListRefresh={refresh} />);
    fireEvent.click(screen.getByRole("button", { name: "기준 변경" }));
    change("적용 시작월", "2026-11");
    change("이익 배분율", "45");
    save();
    await screen.findByText(
      "시험 파트 기준을 바꿨습니다. 2026-11부터 적용됩니다.",
    );
    expect(screen.getByText("예정 1건")).toBeInTheDocument();
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(writes).toHaveLength(1);
    expect(writes[0].url).toBe(
      `/api/sales-parts/${part.bsn_part_id}/standards`,
    );
    expect(JSON.parse(writes[0].init.body as string)).toEqual({
      aply_bgn_ym: "2026-11",
      ldr_nm: "파트장",
      prft_dstrb_rt: 45,
      bsn_fee_amt: 1500000,
    });
  });
  it("refuses a closed month locally", async () => {
    render(<Harness />);
    fireEvent.click(screen.getByText("기준 변경"));
    change("적용 시작월", "2026-09");
    change("이익 배분율", "45");
    save();
    await screen.findAllByText(
      "적용 시작월은 마감된 달(2026-09) 다음 달부터 고를 수 있습니다.",
    );
    expect(writes).toHaveLength(0);
  });
  it.each(["100.001", "101", "-1"])(
    "rejects invalid distribution %s",
    async (rate) => {
      render(<Harness />);
      fireEvent.click(screen.getByText("기준 변경"));
      change("적용 시작월", "2026-11");
      change("이익 배분율", rate);
      save();
      await screen.findAllByText(
        "이익 배분율은 0~100 사이 숫자로 입력해 주세요.",
      );
      expect(writes).toHaveLength(0);
    },
  );
  it("does not save a zero fee", async () => {
    render(<Harness />);
    fireEvent.click(screen.getByText("기준 변경"));
    change("영업비", "0");
    save();
    await screen.findAllByText("영업비를 입력해 주세요.");
    expect(writes).toHaveLength(0);
  });
  it("requires a leader", async () => {
    render(<Harness />);
    fireEvent.click(screen.getByText("기준 변경"));
    change("파트장", "");
    save();
    expect(screen.getByLabelText("파트장")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(writes).toHaveLength(0);
  });
  it("keeps SAME_STANDARD errors and entered values in the editor", async () => {
    fetcher.mockResolvedValue(
      failed("SAME_STANDARD", "지금 기준과 같습니다. 바꿀 값을 입력해 주세요."),
    );
    render(<Harness />);
    fireEvent.click(screen.getByText("기준 변경"));
    change("적용 시작월", "2026-11");
    save();
    await screen.findByText("지금 기준과 같습니다. 바꿀 값을 입력해 주세요.");
    expect(screen.getByLabelText("적용 시작월")).toHaveValue("2026-11");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
  it("has no HQ mutation controls", () => {
    render(
      <SalesPartActions part={{ ...part, hq_yn: "Y" }} onUpdated={vi.fn()} />,
    );
    expect(screen.getByText("본사")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "기준 변경" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByLabelText("영업 파트 더보기")).not.toBeInTheDocument();
  });
  it("supports a read-only host and marks write controls for mobile hiding", () => {
    const view = render(
      <SalesPartActions part={part} onUpdated={vi.fn()} readOnly />,
    );
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    view.rerender(<SalesPartActions part={part} onUpdated={vi.fn()} />);
    expect(screen.getByTestId("sales-part-write-controls")).toHaveClass(
      styles.writeControls,
    );
  });
  it("handles no selection without creating data", () => {
    render(<SalesPartActions part={null} onUpdated={vi.fn()} />);
    expect(screen.getByText("영업 파트를 선택하세요.")).toBeInTheDocument();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("renames via more menu, displays linking help and refreshes parent name", async () => {
    render(<Harness />);
    openMore();
    fireEvent.click(screen.getByText("파트명 수정"));
    expect(
      screen.getByText("소속 인력·프로젝트·사업계획의 파트명도 함께 바뀝니다."),
    ).toBeInTheDocument();
    change("파트명", "새 이름");
    save();
    await screen.findByRole("heading", { name: "새 이름" });
    expect(writes[0].init.method).toBe("PUT");
    expect(JSON.parse(writes[0].init.body as string)).toEqual({
      bsn_part_nm: "새 이름",
    });
  });
  it("unchanged name closes without request or success toast", () => {
    render(<Harness />);
    openMore();
    fireEvent.click(screen.getByText("파트명 수정"));
    save();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes).toHaveLength(0);
    expect(screen.queryByText("파트명을 바꿨습니다.")).not.toBeInTheDocument();
  });
  it("limits the name to 30 characters and rejects empty", () => {
    render(
      <SalesPartNameDialog
        part={part}
        reload={vi.fn()}
        onCancel={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    expect(screen.getByLabelText("파트명")).toHaveAttribute("maxlength", "30");
    change("파트명", "");
    save();
    expect(screen.getByLabelText("파트명")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(writes).toHaveLength(0);
  });
  it("preserves duplicate name input with the server field message", async () => {
    fetcher.mockResolvedValue(
      failed(
        "DUPLICATE_PART_NAME",
        "같은 이름의 파트가 이미 있습니다.",
        "bsn_part_nm",
      ),
    );
    render(<Harness />);
    openMore();
    fireEvent.click(screen.getByText("파트명 수정"));
    change("파트명", "중복 이름");
    save();
    await screen.findAllByText("같은 이름의 파트가 이미 있습니다.");
    expect(screen.getByLabelText("파트명")).toHaveValue("중복 이름");
  });
  it.each([
    { peopleCount: 3, projectCount: 0 },
    { peopleCount: 0, projectCount: 1 },
  ])("blocks disable before requesting when linked: %o", (counts) => {
    render(<Harness value={{ ...part, ...counts }} />);
    openMore();
    fireEvent.click(screen.getByText("사용 중지"));
    expect(
      screen.getByRole("dialog", { name: "사용을 중지할 수 없습니다" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/먼저 다른 파트로 옮겨 주세요/),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByText("확인"));
    expect(writes).toHaveLength(0);
  });
  it("cancels disable with no writes", () => {
    render(<Harness />);
    openMore();
    fireEvent.click(screen.getByText("사용 중지"));
    fireEvent.click(screen.getByText("취소"));
    expect(writes).toHaveLength(0);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("confirms disable and replaces menu with enable after refresh", async () => {
    render(<Harness />);
    openMore();
    fireEvent.click(screen.getByText("사용 중지"));
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {
        name: "사용 중지",
      }),
    );
    await screen.findByText("사용을 중지했습니다.");
    openMore();
    expect(screen.getByText("다시 사용")).toBeInTheDocument();
    expect(writes).toHaveLength(1);
  });
  it("shows server PART_IN_USE race instead of claiming success", async () => {
    fetcher.mockResolvedValue(
      failed(
        "PART_IN_USE",
        "시험 파트에 소속 인력 3명, 진행 프로젝트 0건이 있습니다. 먼저 다른 파트로 옮겨 주세요.",
      ),
    );
    render(<Harness />);
    openMore();
    fireEvent.click(screen.getByText("사용 중지"));
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {
        name: "사용 중지",
      }),
    );
    await screen.findByRole("dialog", { name: "사용을 중지할 수 없습니다" });
    expect(screen.queryByText("사용을 중지했습니다.")).not.toBeInTheDocument();
  });
  it("enables inactive part and returns the name in success feedback", async () => {
    saved = { ...part, use_yn: "N" };
    render(<Harness value={saved} />);
    openMore();
    fireEvent.click(screen.getByText("다시 사용"));
    await screen.findByText("시험 파트을 다시 사용합니다.");
    expect(writes[0].url).toBe(`/api/sales-parts/${part.bsn_part_id}/enable`);
    expect(screen.getByText("Y")).toBeInTheDocument();
  });
  it("on unknown write outcome retry only reads and preserves the draft", async () => {
    fetcher.mockRejectedValueOnce(new Error("network"));
    render(<Harness />);
    fireEvent.click(screen.getByText("기준 변경"));
    change("적용 시작월", "2026-11");
    change("이익 배분율", "45");
    save();
    await screen.findByText(
      "저장 결과를 다시 조회한 뒤 확인하고 직접 저장해 주세요.",
    );
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    fireEvent.click(screen.getByText("다시 시도"));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "저장" })).not.toBeDisabled(),
    );
    expect(
      fetcher.mock.calls.filter((c) => c[1]?.method === "POST"),
    ).toHaveLength(1);
    expect(screen.getByLabelText("이익 배분율")).toHaveValue("45");
  });
});

describe("SCR-UI-15-M14 independent sales part export", () => {
  beforeEach(() => {
    Object.defineProperty(URL, "createObjectURL", {
      value: vi.fn(() => "blob:download"),
      configurable: true,
      writable: true,
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      value: vi.fn(),
      configurable: true,
      writable: true,
    });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  });
  it("exports all matches from page two without ids or page restriction and sends column order", async () => {
    render(
      <SalesPartExportButton
        columns={["bsn_part_nm", "currentStandard.bsn_fee_amt"]}
        selectedIds={[]}
        total={43}
      />,
    );
    fireEvent.click(screen.getByText("엑셀 다운로드"));
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalledTimes(1));
    const query = new URL(fetcher.mock.calls[0][0], "http://test").searchParams;
    expect(query.getAll("columns")).toEqual([
      "bsn_part_nm",
      "currentStandard.bsn_fee_amt",
    ]);
    expect(query.has("ids")).toBe(false);
    expect(query.has("page")).toBe(false);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:download");
  });
  it("defaults to the two selected rows and downloads only on confirm", async () => {
    render(
      <SalesPartExportButton
        columns={["bsn_part_nm"]}
        selectedIds={[part.bsn_part_id, "43"]}
        total={43}
      />,
    );
    fireEvent.click(screen.getByText("엑셀 다운로드"));
    expect(screen.getByLabelText("선택한 2건만")).toBeChecked();
    expect(fetcher).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("내려받기"));
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled());
    expect(
      new URL(fetcher.mock.calls[0][0], "http://test").searchParams.getAll(
        "ids",
      ),
    ).toEqual([part.bsn_part_id, "43"]);
  });
  it("cancels range selection without a request", () => {
    render(
      <SalesPartExportButton
        columns={["bsn_part_nm"]}
        selectedIds={["43"]}
        total={43}
      />,
    );
    fireEvent.click(screen.getByText("엑셀 다운로드"));
    fireEvent.click(screen.getByText("취소"));
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("can switch selected range to the full result set", async () => {
    render(
      <SalesPartExportButton
        columns={["bsn_part_nm"]}
        selectedIds={["43"]}
        total={43}
      />,
    );
    fireEvent.click(screen.getByText("엑셀 다운로드"));
    fireEvent.click(screen.getByLabelText("조회된 전체 43건"));
    fireEvent.click(screen.getByText("내려받기"));
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled());
    expect(
      new URL(fetcher.mock.calls[0][0], "http://test").searchParams.has("ids"),
    ).toBe(false);
  });
  it("keeps range and server error on failed download, no fake success", async () => {
    fetcher.mockResolvedValue(
      failed("INVALID_INPUT", "내려받을 열을 확인하세요.", "columns", 400),
    );
    render(
      <SalesPartExportButton columns={[]} selectedIds={["43"]} total={43} />,
    );
    fireEvent.click(screen.getByText("엑셀 다운로드"));
    fireEvent.click(screen.getByText("내려받기"));
    await screen.findByText("내려받을 열을 확인하세요.");
    expect(screen.getByLabelText("선택한 1건만")).toBeChecked();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });
  it("rejects JSON success pretending to be an xlsx", async () => {
    fetcher.mockResolvedValue(response({}));
    await expect(getSalesPartExport(["bsn_part_nm"])).rejects.toThrow(
      "엑셀 파일 응답을 확인할 수 없습니다.",
    );
  });
  it("rejects malformed filename metadata", async () => {
    fetcher.mockResolvedValue(
      new Response("xlsx", {
        headers: {
          "Content-Type":
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "Content-Disposition":
            "attachment; filename*=UTF-8''%2Fetc%2Ffile.xlsx",
        },
      }),
    );
    await expect(getSalesPartExport(["bsn_part_nm"])).rejects.toThrow(
      "파일명을 확인할 수 없습니다.",
    );
  });
  it("reads complete public options and preserves string ids", async () => {
    expect(await listSalesPartOptions()).toEqual([
      { id: part.bsn_part_id, name: "시험 파트", hq: false },
    ]);
  });
});
