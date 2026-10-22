import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import UI27Screen from "./UI27Screen";
import { CodeTab } from "./CodeTab";
import { ExcelMapsTab } from "./ExcelMapsTab";
import { GroupDialog, ExcelMapDialog } from "./CreateDialogs";
import { ParamsTab, validParamDraft, paramDisplay } from "./ParamsTab";
import { HistoryTab } from "./HistoryTab";
import {
  UnsavedDialog,
  TabOverflowDialog,
  ScreenSearchDialog,
} from "./AdminDialogs";
import {
  allPages,
  listCodeGroups,
  saveParamVersion,
  type CodeGroup,
  type CodeRow,
  type ExcelMap,
  type OperatingParam,
} from "./client";
import { useSharedPreferences } from "../shared/preferences";

const group: CodeGroup = {
  cd_grp_id: "JOB",
  cd_grp_nm: "직무",
  cd_grp_se_cd: "BIZ",
  attr1_nm: "기본 단가(원)",
  attr2_nm: null,
  attr3_nm: null,
  sort_seq: 1,
  use_yn: "Y",
  codeCount: 2,
};
const system: CodeGroup = {
  ...group,
  cd_grp_id: "CONTRACT_TYPE",
  cd_grp_nm: "계약 유형",
  cd_grp_se_cd: "SYSTEM",
  attr1_nm: null,
};
const rows: CodeRow[] = [
  {
    dtl_cd: "BE",
    cd_nm: "백엔드",
    sort_seq: 1,
    use_yn: "Y",
    attr1_val: "200000",
    attr2_val: null,
    attr3_val: null,
    aply_bgn_dt: null,
  },
  {
    dtl_cd: "OPS",
    cd_nm: "운영",
    sort_seq: 2,
    use_yn: "N",
    attr1_val: null,
    attr2_val: null,
    attr3_val: null,
    aply_bgn_dt: null,
  },
];
const mapping: ExcelMap = {
  map_id: "9",
  excel_lbl_nm: "표기",
  trns_cd_grp_id: "JOB",
  trns_cd: "OPS",
  cd_grp_nm: "직무",
  cd_nm: "운영",
  map_role_cd: "MAIN",
  rmrk_desc: "메모",
  use_yn: "Y",
};
const param: OperatingParam = {
  oprt_std_id: "VAT_RATE",
  oprt_std_nm: "부가세",
  aply_bgn_ym: "2000-01",
  num_val: 10,
  txt_val: null,
  unit_nm: "%",
  use_plc_desc: "계산",
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const page = (items: unknown[], total = items.length, p = 1, size = 50) =>
  response({ items, total, page: p, size });
const failed = (field: string | null = null) =>
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
    if (init?.method && init.method !== "GET") return response(undefined);
    if (url.startsWith("/api/admin/code-groups?"))
      return page([group, system], 2, 1, 100);
    if (url.includes("/codes?")) return page(rows, 2, 1, 100);
    if (url.startsWith("/api/codes/"))
      return response([
        { code: "BE", name: "백엔드", attr1: null, attr2: null, attr3: null },
      ]);
    if (url.startsWith("/api/admin/excel-maps")) return page([mapping]);
    if (url.includes("/versions?"))
      return page(
        [{ ...param, aply_bgn_ym: "2026-11", num_val: 11 }, param],
        2,
        1,
        100,
      );
    if (url.startsWith("/api/admin/params?")) return page([param]);
    if (url.startsWith("/api/month-close"))
      return response([{ month: "2026-09", status: "CLOSED" }]);
    if (url.startsWith("/api/admin/code-history")) return page([]);
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const change = (label: string, value: string) =>
  fireEvent.change(
    label === "화면 검색"
      ? screen.getByRole("textbox", { name: label })
      : screen.getByLabelText(label),
    { target: { value } },
  );
const writes = () =>
  fetcher.mock.calls.filter(
    (call) => call[1]?.method && call[1].method !== "GET",
  );

describe("UI-27 code contract", () => {
  it("shares density with shell preferences without losing the code draft", async () => {
    render(<CodeTab group={group} onDirtyChange={() => {}} />);
    await screen.findByLabelText("이름 1");
    change("이름 1", "작성 중");
    await act(async () => {
      useSharedPreferences.getState().configure({ dense: true });
    });
    expect(screen.getByRole("table").className).toContain("dense");
    expect(screen.getByLabelText("이름 1")).toHaveValue("작성 중");
    fireEvent.click(screen.getByLabelText("좁게 보기"));
    expect(useSharedPreferences.getState().dense).toBe(false);
    expect(localStorage.getItem("si.dense")).toBe("false");
    expect(writes()).toHaveLength(0);
  });
  it("selects first group and renders dynamic columns including inactive names", async () => {
    render(<UI27Screen />);
    expect(await screen.findByLabelText("이름 1")).toHaveValue("백엔드");
    expect(
      screen.getByRole("columnheader", { name: "기본 단가(원)" }),
    ).toBeVisible();
    expect(screen.getByLabelText("사용 2")).not.toBeChecked();
    expect(screen.getByLabelText("코드 1")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("SYSTEM 잠금")).toBeInTheDocument();
  });
  it("SYSTEM hides create and locks all but name and order", async () => {
    render(<CodeTab group={system} onDirtyChange={() => {}} />);
    await screen.findByLabelText("이름 1");
    expect(screen.queryByText("코드 추가")).not.toBeInTheDocument();
    expect(screen.getByLabelText("사용 1")).toBeDisabled();
    expect(screen.getByLabelText("적용 시작일 1")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("이름 1")).not.toHaveAttribute("readonly");
    expect(screen.getByText(/계산 규칙에/)).toBeVisible();
  });
  it("BIZ new row uses max plus one and empty required draft is not saved", async () => {
    render(<CodeTab group={group} onDirtyChange={() => {}} />);
    await screen.findByLabelText("이름 1");
    fireEvent.click(screen.getByText("코드 추가"));
    expect(screen.getByLabelText("순서 3")).toHaveValue(3);
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(await screen.findByText("코드를 입력하세요.")).toBeVisible();
    expect(screen.getByLabelText("코드 3")).toHaveFocus();
    expect(writes()).toHaveLength(0);
  });
  it("preserves draft and maps singular server row field on rejected save", async () => {
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      init?.method === "PUT" ? failed("[0].cd_nm") : page(rows, 2, 1, 100),
    );
    render(<CodeTab group={group} onDirtyChange={() => {}} />);
    await screen.findByLabelText("이름 1");
    change("이름 1", "수정");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(
      await screen.findByText("입력 오류", { selector: "span" }),
    ).toBeVisible();
    expect(screen.getByLabelText("이름 1")).toHaveValue("수정");
    expect(screen.getByText("문의 번호 server-request")).toBeVisible();
  });
  it("does not replay uncertain PUT and recovers only by GET", async () => {
    fetcher.mockImplementation(async (_url: string, init?: RequestInit) => {
      if (init?.method === "PUT") throw new Error("offline");
      return page(rows, 2, 1, 100);
    });
    render(<CodeTab group={group} onDirtyChange={() => {}} />);
    await screen.findByLabelText("이름 1");
    change("이름 1", "미확인");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByText("문의 번호 없음 (서버 응답 없음)");
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    fireEvent.click(screen.getByText("다시 시도"));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
    );
    expect(writes()).toHaveLength(1);
    expect(screen.getByLabelText("이름 1")).toHaveValue("미확인");
  });
  it("pending PUT suppresses second click and success requeries", async () => {
    let done!: (v: Response) => void;
    fetcher.mockImplementation(async (_url: string, init?: RequestInit) =>
      init?.method === "PUT"
        ? new Promise<Response>((resolve) => {
            done = resolve;
          })
        : page(rows, 2, 1, 100),
    );
    render(<CodeTab group={group} onDirtyChange={() => {}} />);
    await screen.findByLabelText("이름 1");
    change("이름 1", "수정");
    const save = screen.getByRole("button", { name: "저장" });
    fireEvent.click(save);
    fireEvent.click(save);
    expect(save).toBeDisabled();
    expect(writes()).toHaveLength(1);
    done(response(undefined));
    expect(await screen.findByText("공통코드를 저장했습니다.")).toBeVisible();
    expect(fetcher.mock.calls.filter((c) => !c[1]?.method)).toHaveLength(2);
  });
  it("dirty group switch continue retains data, leave discards only this draft", async () => {
    render(<UI27Screen />);
    await screen.findByLabelText("이름 1");
    change("이름 1", "작성 중");
    fireEvent.click(screen.getByRole("button", { name: /계약 유형/ }));
    fireEvent.click(screen.getByText("계속 작성"));
    await waitFor(() =>
      expect(screen.getByLabelText("이름 1")).toHaveValue("작성 중"),
    );
    fireEvent.click(screen.getByRole("button", { name: /계약 유형/ }));
    fireEvent.click(screen.getByText("나가기"));
    await screen.findByText(/계산 규칙에/);
    expect(screen.getByLabelText("이름 1")).toHaveValue("백엔드");
  });
  it("dirty subtab switch uses M1 and does not request other tab before acceptance", async () => {
    render(<UI27Screen />);
    await screen.findByLabelText("이름 1");
    change("이름 1", "draft");
    fireEvent.click(screen.getByRole("tab", { name: "기준값" }));
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(
      fetcher.mock.calls.some((c) =>
        String(c[0]).startsWith("/api/admin/params"),
      ),
    ).toBe(false);
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByRole("tab", { name: "코드" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });
  it("empty list uses exact message and allows BIZ add", async () => {
    fetcher.mockResolvedValue(page([], 0, 1, 100));
    render(<CodeTab group={group} onDirtyChange={() => {}} />);
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeVisible();
    expect(screen.getByText("코드 추가")).toBeEnabled();
  });
  it("failed read disables partial PUT and offers retry", async () => {
    fetcher.mockRejectedValueOnce(new Error("offline"));
    render(<CodeTab group={group} onDirtyChange={() => {}} />);
    await screen.findByText("다시 시도");
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByLabelText("이름 1")).toHaveValue("백엔드");
  });
  it("whole-list client collects all pages before PUT data is available", async () => {
    const many = Array.from({ length: 100 }, (_, i) => ({
      ...rows[0],
      dtl_cd: `CODE${i}`,
    }));
    fetcher.mockImplementation(async (url: string) =>
      url.includes("page=1")
        ? page(many, 101, 1, 100)
        : page([rows[1]], 101, 2, 100),
    );
    expect(
      await allPages<CodeRow>("/api/admin/code-groups/JOB/codes"),
    ).toHaveLength(101);
    expect(fetcher.mock.calls[1][0]).toContain("page=2");
  });
});

describe("group and mapping dialogs", () => {
  it("group form exposes exact fields and submits no type/sort/use/audit", async () => {
    const saved = vi.fn(),
      reload = vi.fn().mockResolvedValue(undefined);
    render(<GroupDialog onCancel={() => {}} onSaved={saved} reload={reload} />);
    change("그룹 코드", "NEW_GROUP");
    change("그룹명", "새 그룹");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(saved).toHaveBeenCalledOnce());
    const body = JSON.parse(writes()[0][1].body as string);
    expect(Object.keys(body).sort()).toEqual([
      "attr1_nm",
      "attr2_nm",
      "attr3_nm",
      "cd_grp_id",
      "cd_grp_nm",
    ]);
    expect(reload).toHaveBeenCalledOnce();
  });
  it("edited modal cancel asks M1 and backdrop never closes", () => {
    const cancel = vi.fn();
    render(
      <GroupDialog
        onCancel={cancel}
        onSaved={() => {}}
        reload={async () => {}}
      />,
    );
    change("그룹명", "작성");
    fireEvent.click(screen.getByRole("dialog").parentElement!);
    expect(cancel).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("취소"));
    expect(screen.getAllByRole("dialog")).toHaveLength(2);
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByLabelText("그룹명")).toHaveValue("작성");
  });
  it("mapping creation has no default role and gets only active options from selected group", async () => {
    const saved = vi.fn();
    render(
      <ExcelMapDialog
        groups={[group]}
        onCancel={() => {}}
        onSaved={saved}
        reload={async () => {}}
      />,
    );
    expect(screen.getByLabelText("주/보조")).toHaveValue("");
    change("그룹", "JOB");
    await screen.findByRole("option", { name: "백엔드" });
    expect(
      screen.queryByRole("option", { name: "운영" }),
    ).not.toBeInTheDocument();
    change("엑셀 표기", "테스트표기");
    change("코드", "BE");
    change("주/보조", "MAIN");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(saved).toHaveBeenCalledOnce());
    expect(JSON.parse(writes()[0][1].body as string)).toMatchObject({
      excel_lbl_nm: "테스트표기",
      trns_cd_grp_id: "JOB",
      trns_cd: "BE",
      map_role_cd: "MAIN",
    });
  });
  it("mapping existing identities are readonly text and blur never PATCHes", async () => {
    render(<ExcelMapsTab groups={[group]} onDirtyChange={() => {}} />);
    await screen.findByLabelText("메모 9");
    expect(screen.getByText("직무 · 운영")).toBeVisible();
    change("메모 9", "새 메모");
    fireEvent.blur(screen.getByLabelText("메모 9"));
    expect(writes()).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.getByLabelText("메모 9")).toHaveValue("메모");
  });
  it("explicit mapping save sends only allowed fields and selected map id", async () => {
    render(<ExcelMapsTab groups={[group]} onDirtyChange={() => {}} />);
    await screen.findByLabelText("메모 9");
    change("메모 9", "새 메모");
    change("주/보조 9", "SUB");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(writes()).toHaveLength(1));
    expect(writes()[0][0]).toBe("/api/admin/excel-maps/9");
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({
      map_role_cd: "SUB",
      rmrk_desc: "새 메모",
      use_yn: "Y",
    });
  });
  it("mapping error preserves row draft", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      init?.method === "PATCH" ? failed("rmrk_desc") : original(url, init),
    );
    render(<ExcelMapsTab groups={[group]} onDirtyChange={() => {}} />);
    await screen.findByLabelText("메모 9");
    change("메모 9", "보존");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByText("문의 번호 server-request");
    expect(screen.getByLabelText("메모 9")).toHaveValue("보존");
  });
});

describe("parameters and history", () => {
  it.each(["maps", "params", "history"])(
    "%s uses the shared default page size and density",
    async (resource) => {
      useSharedPreferences.getState().configure({ pageSize: 20, dense: true });
      render(
        resource === "maps" ? (
          <ExcelMapsTab groups={[group]} onDirtyChange={() => {}} />
        ) : resource === "params" ? (
          <ParamsTab />
        ) : (
          <HistoryTab groups={[group]} />
        ),
      );
      const path =
        resource === "maps"
          ? "/api/admin/excel-maps"
          : resource === "params"
            ? "/api/admin/params"
            : "/api/admin/code-history";
      await waitFor(() =>
        expect(
          fetcher.mock.calls.some(
            (call) => call[0] === `${path}?page=1&size=20`,
          ),
        ).toBe(true),
      );
      expect(screen.getByLabelText("목록 건수")).toHaveValue("20");
      expect(screen.getByRole("table").className).toContain("dense");
      fireEvent.click(screen.getByLabelText("좁게 보기"));
      expect(useSharedPreferences.getState().dense).toBe(false);
      expect(writes()).toHaveLength(0);
    },
  );
  it("shows numeric zero instead of text and ratio is not multiplied", () => {
    expect(paramDisplay({ ...param, num_val: 0, txt_val: "대체" })).toBe(
      "0.0%",
    );
    expect(paramDisplay(param)).toBe("10.0%");
    expect(
      paramDisplay({ ...param, num_val: null, txt_val: "문자", unit_nm: null }),
    ).toBe("문자");
  });
  it.each(["", "-1", "101", "0.00001", "100000000000000"])(
    "rejects numeric draft %s",
    (value) => {
      expect(validParamDraft(value, param)).toBe(false);
    },
  );
  it.each(["0", "100"])("allows numeric boundary %s", (value) => {
    expect(validParamDraft(value, param)).toBe(true);
  });
  it("text length and required are enforced", () => {
    const text = { ...param, num_val: null };
    expect(validParamDraft("", text)).toBe(false);
    expect(validParamDraft("x".repeat(201), text)).toBe(false);
    expect(validParamDraft("문자", text)).toBe(true);
  });
  it("parameter modal queries full close boundary and blocks saving when read fails", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      url.startsWith("/api/month-close")
        ? Promise.reject(new Error("offline"))
        : original(url, init),
    );
    render(<ParamsTab />);
    fireEvent.click(await screen.findByText("값 변경"));
    await screen.findByText("문의 번호 없음 (서버 응답 없음)");
    change("새 값", "11");
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    expect(
      fetcher.mock.calls.some(
        (c) => c[0] === "/api/month-close?from=0001-01&to=9999-12",
      ),
    ).toBe(true);
    expect(writes()).toHaveLength(0);
  });
  it("closed month fails inline without PUT and retains new value", async () => {
    render(<ParamsTab />);
    fireEvent.click(await screen.findByText("값 변경"));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
    );
    change("새 값", "11");
    change("적용 시작월", "2026-09");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(
      await screen.findByText("마감된 월 이후의 올바른 적용월을 입력하세요."),
    ).toBeVisible();
    expect(screen.getByLabelText("새 값")).toHaveValue("11");
    expect(writes()).toHaveLength(0);
  });
  it("future parameter save uses composite path and one value, history includes future", async () => {
    render(<ParamsTab />);
    fireEvent.click(await screen.findByText("값 변경"));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
    );
    change("새 값", "11");
    change("적용 시작월", "2026-11");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(writes()[0][0]).toBe("/api/admin/params/VAT_RATE/versions/2026-11");
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({ num_val: 11 });
    fireEvent.click(screen.getByText("이력 보기"));
    expect(await screen.findByText("2026-11")).toBeVisible();
    expect(screen.getAllByText("10.0%")[0]).toBeVisible();
  });
  it("decimal client does not round before sending JSON", async () => {
    await saveParamVersion("AMOUNT", "2026-11", {
      num_val: "12345678901234.1234",
    });
    expect(writes()[0][1].body).toBe('{"num_val":12345678901234.1234}');
  });
  it("history is readonly, PARAM/default actor are labelled and filters reset", async () => {
    fetcher.mockImplementation(async (url: string) =>
      page(
        [
          {
            chg_at: "2026-10-22T09:00:00",
            cd_grp_id: "PARAM",
            cd_grp_nm: "기준값",
            dtl_cd: "VAT_RATE",
            cd_chg_ty_cd: "UPDATE",
            item_nm: "적용 시작월",
            bfr_val: "2000-01",
            aft_val: "2026-11",
            chg_user_id: null,
          },
        ],
        1,
      ),
    );
    render(<HistoryTab groups={[group]} />);
    await screen.findByText("기본 사용자");
    expect(screen.getByText("2026-10-22 09:00")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
    change("이력 그룹", "PARAM");
    fireEvent.submit(screen.getByLabelText("이력 그룹").closest("form")!);
    await waitFor(() =>
      expect(fetcher.mock.calls.at(-1)?.[0]).toContain("group=PARAM"),
    );
    fireEvent.click(screen.getByText("초기화"));
    await waitFor(() =>
      expect(fetcher.mock.calls.at(-1)?.[0]).not.toContain("group=PARAM"),
    );
  });
});

describe("specified shared overlays", () => {
  it("M1 exposes continue and leave", () => {
    const leave = vi.fn(),
      stay = vi.fn();
    render(<UnsavedDialog onLeave={leave} onContinue={stay} />);
    fireEvent.click(screen.getByText("계속 작성"));
    fireEvent.click(screen.getByText("나가기"));
    expect(stay).toHaveBeenCalledOnce();
    expect(leave).toHaveBeenCalledOnce();
  });
  it("M2 displays the supplied non-dashboard candidate", () => {
    const confirm = vi.fn();
    render(
      <TabOverflowDialog
        candidate="직무"
        onConfirm={confirm}
        onCancel={() => {}}
      />,
    );
    expect(screen.getByText(/후보: 직무/)).toBeVisible();
    fireEvent.click(screen.getByText("닫기 확인"));
    expect(confirm).toHaveBeenCalledOnce();
  });
  it("M6 filters existing menu without a search API and selects route identity", () => {
    const select = vi.fn();
    render(
      <ScreenSearchDialog
        menus={[
          { screenId: "UI-27", name: "공통코드" },
          { screenId: "UI-16", name: "대시보드" },
        ]}
        onSelect={select}
        onCancel={() => {}}
      />,
    );
    change("화면 검색", "코드");
    expect(screen.queryByText("대시보드 UI-16")).not.toBeInTheDocument();
    fireEvent.click(screen.getByText("공통코드 UI-27"));
    expect(select).toHaveBeenCalledWith("UI-27");
    expect(fetcher).not.toHaveBeenCalled();
  });
});
