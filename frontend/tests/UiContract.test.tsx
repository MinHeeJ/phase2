import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CodeEditor } from "../src/pages/admin/CodesTab";
import { CodeHistoryTab, ParamDialog } from "../src/pages/admin/ParamsTab";
import { ExcelMapDialog } from "../src/pages/admin/ExcelMapsTab";
import type { CodeGroup, OperatingParam } from "../src/pages/admin/adminApi";

// Values are isolated API response fixtures, never product defaults.
const group: CodeGroup = {
  cd_grp_id: "JOB",
  cd_grp_nm: "직무",
  cd_grp_se_cd: "BIZ",
  attr1_nm: null,
  attr2_nm: null,
  attr3_nm: null,
  sort_seq: 1,
  use_yn: "Y",
  codeCount: 1,
};
const row = {
  dtl_cd: "BE",
  cd_nm: "백엔드",
  sort_seq: 1,
  use_yn: "Y",
  attr1_val: null,
  attr2_val: null,
  attr3_val: null,
  aply_bgn_dt: null,
};
const param: OperatingParam = {
  oprt_std_id: "VAT_RATE",
  oprt_std_nm: "부가세",
  aply_bgn_ym: "2000-01",
  num_val: 10,
  txt_val: null,
  unit_nm: "%",
  use_plc_desc: "세금",
};
function json(data: unknown) {
  return new Response(JSON.stringify({ success: true, meta: {}, data }));
}
function failure(field: string) {
  return new Response(
    JSON.stringify({
      success: false,
      code: "VALIDATION_ERROR",
      field,
      message: "서버에서 입력을 거절했습니다.",
      requestId: "contract_test",
    }),
    { status: 400 },
  );
}
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetcher = vi.fn(async (path: string, options?: RequestInit) => {
    if (options?.method) return json(undefined);
    if (path.startsWith("/api/month-close")) return json([]);
    return json({ items: [row], total: 1, page: 1, size: 100 });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.unstubAllGlobals());

describe("명백한 UI Contract 보강", () => {
  it.each([
    ["sort_seq", "순서 1"],
    ["aply_bgn_dt", "적용 시작일 1"],
  ])(
    "코드 %s 서버 오류가 해당 입력에 연결되고 수정 후 재저장 가능",
    async (field, label) => {
      render(<CodeEditor group={group} onDirty={vi.fn()} />);
      await screen.findByDisplayValue("백엔드");
      fireEvent.change(screen.getByDisplayValue("백엔드"), {
        target: { value: "변경" },
      });
      fetcher.mockResolvedValueOnce(failure(`[0].${field}`));
      fireEvent.click(screen.getByRole("button", { name: "저장" }));
      const input = screen.getByLabelText(label);
      await waitFor(() =>
        expect(input).toHaveAttribute("aria-invalid", "true"),
      );
      expect(input).toHaveAccessibleDescription(
        "서버에서 입력을 거절했습니다.",
      );
      await waitFor(() => expect(input).toHaveFocus());
      expect(screen.getByDisplayValue("변경")).toBeInTheDocument();
      fireEvent.change(input, {
        target: { value: field === "sort_seq" ? "2" : "2026-11-01" },
      });
      expect(input).toHaveAttribute("aria-invalid", "false");
      fireEvent.click(screen.getByRole("button", { name: "저장" }));
      await screen.findByText("공통코드를 저장했습니다.");
      expect(
        fetcher.mock.calls.filter((call) => call[1]?.method === "PUT"),
      ).toHaveLength(2);
    },
  );

  it("적용월 서버 오류 표시·포커스 및 월 수정 후 오류 해제", async () => {
    const saved = vi.fn();
    render(<ParamDialog row={param} onClose={vi.fn()} onSaved={saved} />);
    const month = screen.getByLabelText("적용 시작월");
    await waitFor(() => expect(month).not.toBeDisabled());
    fireEvent.change(month, { target: { value: "2026-11" } });
    fetcher.mockResolvedValueOnce(failure("month"));
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(month).toHaveAttribute("aria-invalid", "true"));
    expect(month).toHaveAccessibleDescription("서버에서 입력을 거절했습니다.");
    await waitFor(() => expect(month).toHaveFocus());
    expect(saved).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    fireEvent.change(month, { target: { value: "2026-12" } });
    expect(month).toHaveAttribute("aria-invalid", "false");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(saved).toHaveBeenCalledOnce());
  });

  it("대응 추가 메모는 여러 줄 입력이고 개행을 payload에 보존", async () => {
    render(
      <ExcelMapDialog groups={[group]} onClose={vi.fn()} onSaved={vi.fn()} />,
    );
    fireEvent.change(screen.getByLabelText("엑셀 표기 *"), {
      target: { value: "테스트표기" },
    });
    fetcher.mockResolvedValueOnce(json([{ code: "BE", name: "백엔드" }]));
    fireEvent.change(screen.getByLabelText("그룹 *"), {
      target: { value: "JOB" },
    });
    await screen.findByRole("option", { name: "백엔드" });
    fireEvent.change(screen.getByLabelText("코드 *"), {
      target: { value: "BE" },
    });
    fireEvent.change(screen.getByLabelText("주/보조 *"), {
      target: { value: "MAIN" },
    });
    const memo = screen.getByRole("textbox", { name: "메모" });
    expect(memo.tagName).toBe("TEXTAREA");
    fireEvent.change(memo, { target: { value: "첫 줄\n둘째 줄" } });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some((call) => call[1]?.method === "POST"),
      ).toBe(true),
    );
    const write = fetcher.mock.calls.find(
      (call) => call[1]?.method === "POST",
    )!;
    expect(JSON.parse(write[1].body).rmrk_desc).toBe("첫 줄\n둘째 줄");
  });

  it("이력 초기화는 조건만 해제하고 Enter 조회로 결과를 갱신", async () => {
    fetcher.mockImplementation(async () =>
      json({ items: [], total: 0, page: 1, size: 50 }),
    );
    render(<CodeHistoryTab groups={[group]} />);
    await screen.findByText("조건에 맞는 결과가 없습니다");
    fireEvent.change(screen.getByLabelText("그룹"), {
      target: { value: "JOB" },
    });
    fireEvent.click(screen.getByRole("button", { name: "조회" }));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    const before = fetcher.mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: "초기화" }));
    expect(screen.getByLabelText("그룹")).toHaveValue("");
    expect(fetcher).toHaveBeenCalledTimes(before);
    screen.getByRole("button", { name: "조회" }).focus();
    await userEvent.keyboard("{Enter}");
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(before + 1));
    expect(fetcher.mock.calls.at(-1)![0]).not.toContain("group=");
  });
});
