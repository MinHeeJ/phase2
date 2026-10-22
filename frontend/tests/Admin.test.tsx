import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Ui27Screen } from "../src/pages/admin/Ui27Screen";
import { GroupDialog } from "../src/pages/admin/GroupDialog";
import { ExcelMapDialog, ExcelMapsTab } from "../src/pages/admin/ExcelMapsTab";
import {
  ParamDialog,
  ParamVersions,
  CodeHistoryTab,
} from "../src/pages/admin/ParamsTab";
import {
  AdminScreenSearch,
  AdminTabLimitDialog,
  PlaceholderScreen,
} from "../src/pages/admin/PlaceholderScreen";
import {
  collectAdminPages,
  type CodeGroup,
  type CodeRow,
  type OperatingParam,
} from "../src/pages/admin/adminApi";
import { useSharedPreferences } from "../src/pages/shared/preferences";

const groups: CodeGroup[] = [
  {
    cd_grp_id: "CONTRACT_TYPE",
    cd_grp_nm: "계약 유형",
    cd_grp_se_cd: "SYSTEM",
    attr1_nm: null,
    attr2_nm: null,
    attr3_nm: null,
    sort_seq: 1,
    use_yn: "Y",
    codeCount: 1,
  },
  {
    cd_grp_id: "JOB",
    cd_grp_nm: "직무",
    cd_grp_se_cd: "BIZ",
    attr1_nm: "기본 단가(원)",
    attr2_nm: null,
    attr3_nm: null,
    sort_seq: 2,
    use_yn: "Y",
    codeCount: 2,
  },
];
const tk: CodeRow = {
  dtl_cd: "TK",
  cd_nm: "턴키",
  sort_seq: 1,
  use_yn: "Y",
  attr1_val: null,
  attr2_val: null,
  attr3_val: null,
  aply_bgn_dt: null,
};
const job: CodeRow[] = [
  { ...tk, dtl_cd: "BE", cd_nm: "백엔드", attr1_val: "200000" },
  { ...tk, dtl_cd: "OPS", cd_nm: "운영", sort_seq: 2, use_yn: "N" },
];
const param: OperatingParam = {
  oprt_std_id: "VAT_RATE",
  oprt_std_nm: "부가세",
  aply_bgn_ym: "2000-01",
  num_val: 10,
  txt_val: null,
  unit_nm: "%",
  use_plc_desc: "세금",
};
const excel = {
  map_id: "11",
  excel_lbl_nm: "개발자",
  trns_cd_grp_id: "JOB",
  trns_cd: "BE",
  cd_grp_nm: "직무",
  cd_nm: "백엔드",
  map_role_cd: "MAIN",
  rmrk_desc: "기존 메모",
  use_yn: "Y",
};
function response(data: unknown, status = 200) {
  return new Response(
    JSON.stringify(status === 200 ? { success: true, meta: {}, data } : data),
    {
      status,
      headers: {
        "Content-Type": "application/json",
        "X-Request-Id": "server_123",
      },
    },
  );
}
function page(items: unknown[], size = 100, total = items.length, number = 1) {
  return { items, total, page: number, size };
}
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  localStorage.clear();
  useSharedPreferences.setState({ pageSize: 50, dense: false });
  fetcher = vi.fn(async (path: string, options?: RequestInit) => {
    if (options?.method) return response(undefined);
    if (path.startsWith("/api/admin/code-groups?"))
      return response(page(groups));
    if (path.includes("/CONTRACT_TYPE/codes")) return response(page([tk]));
    if (path.includes("/JOB/codes")) return response(page(job));
    if (path.startsWith("/api/codes/"))
      return response([
        { code: "BE", name: "백엔드", attr1: null, attr2: null, attr3: null },
      ]);
    if (path.startsWith("/api/admin/excel-maps"))
      return response(page([excel], 50));
    if (path.includes("/versions"))
      return response(
        page([{ ...param, aply_bgn_ym: "2026-11", num_val: 11 }, param]),
      );
    if (path.startsWith("/api/admin/params"))
      return response(page([param], 50));
    if (path.startsWith("/api/month-close"))
      return response([{ month: "2026-09", status: "CLOSED" }]);
    if (path.startsWith("/api/admin/code-history"))
      return response(
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
          50,
        ),
      );
    if (path.startsWith("/api/menus")) return response([]);
    throw new Error(`Unexpected path ${path}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
const writes = () =>
  fetcher.mock.calls.filter(
    (call) => (call[1] as RequestInit | undefined)?.method,
  );
async function openJob() {
  render(<Ui27Screen />);
  await screen.findByDisplayValue("턴키");
  fireEvent.click(screen.getByRole("button", { name: /직무/ }));
  await screen.findByDisplayValue("백엔드");
}

describe("UI-27 코드 계약", () => {
  it("처음 첫 그룹, SYSTEM 잠금, 불변 코드·사용 및 추가 숨김", async () => {
    render(<Ui27Screen />);
    await screen.findByDisplayValue("턴키");
    expect(screen.getByDisplayValue("TK")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("사용 TK")).toBeDisabled();
    expect(
      screen.queryByRole("button", { name: "코드 추가" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText(/계산 규칙/)).toBeInTheDocument();
    expect(screen.getAllByRole("tab")).toHaveLength(4);
  });
  it("BIZ 동적 제목, 비활성 행 보존, 추가는 최대순서+1", async () => {
    await openJob();
    expect(
      screen.getByRole("columnheader", { name: "기본 단가(원)" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("사용 OPS")).not.toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: "코드 추가" }));
    expect(screen.getByLabelText("순서 3")).toHaveValue(3);
    expect(screen.getByLabelText("코드 3 *")).not.toHaveAttribute("readonly");
  });
  it("취소가 서버 저장본으로 복원", async () => {
    await openJob();
    fireEvent.change(screen.getByDisplayValue("백엔드"), {
      target: { value: "변경" },
    });
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.getByDisplayValue("백엔드")).toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("전체 배열을 PUT하고 코드·그룹 건수를 성공 재조회", async () => {
    await openJob();
    const groupReads = () =>
      fetcher.mock.calls.filter((call) =>
        String(call[0]).startsWith("/api/admin/code-groups?"),
      ).length;
    const before = groupReads();
    fireEvent.change(screen.getByDisplayValue("백엔드"), {
      target: { value: "새 이름" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(writes()).toHaveLength(1));
    const [path, options] = writes()[0];
    expect(path).toBe("/api/admin/code-groups/JOB/codes");
    expect(JSON.parse((options as RequestInit).body as string)).toHaveLength(2);
    await screen.findByText("공통코드를 저장했습니다.");
    expect(groupReads()).toBeGreaterThan(before);
  });
  it("API 오류 field 표시 및 draft 유지·자동 재전송 없음", async () => {
    await openJob();
    fetcher.mockImplementationOnce(async () =>
      response(
        {
          success: false,
          meta: {},
          code: "VALIDATION_ERROR",
          message: "이름을 입력하세요.",
          field: "[0].cd_nm",
          requestId: "server_123",
        },
        400,
      ),
    );
    fireEvent.change(screen.getByDisplayValue("백엔드"), {
      target: { value: "변경" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findAllByText("이름을 입력하세요.");
    expect(screen.getByDisplayValue("변경")).toBeInTheDocument();
    expect(screen.getByText("문의 번호: server_123")).toBeInTheDocument();
    expect(writes()).toHaveLength(1);
  });
  it("미저장 하위탭 이동은 계속 작성/나가기 확인", async () => {
    await openJob();
    fireEvent.change(screen.getByDisplayValue("백엔드"), {
      target: { value: "draft" },
    });
    fireEvent.click(screen.getByRole("tab", { name: "기준값" }));
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(screen.getByDisplayValue("draft")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "기준값" }));
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    await screen.findByText("부가세");
  });
  it("미저장 그룹 이동도 확인", async () => {
    await openJob();
    fireEvent.change(screen.getByDisplayValue("백엔드"), {
      target: { value: "draft" },
    });
    fireEvent.click(screen.getByRole("button", { name: /계약 유형/ }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(screen.getByDisplayValue("draft")).toBeInTheDocument();
  });
  it("PC 전용 접근은 API도 호출하지 않음", () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => ({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    );
    render(<Ui27Screen />);
    expect(screen.getByText("PC에서 이용해 주세요")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "대시보드로" })).toHaveAttribute(
      "href",
      "/UI-16",
    );
    expect(fetcher).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });
  it("조회 실패 재시도와 문의번호", async () => {
    fetcher.mockRejectedValueOnce(new TypeError("network"));
    render(<Ui27Screen />);
    await screen.findByText("문의 번호: 서버 연결 전 실패");
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByDisplayValue("턴키");
  });
  it("빈 그룹 안내", async () => {
    fetcher.mockResolvedValueOnce(response(page([])));
    render(<Ui27Screen />);
    await screen.findByText("조건에 맞는 결과가 없습니다");
  });
  it("모든 페이지 수집 후에만 완료", async () => {
    fetcher
      .mockResolvedValueOnce(response(page([groups[0]], 100, 2, 1)))
      .mockResolvedValueOnce(response(page([groups[1]], 100, 2, 2)));
    expect(
      await collectAdminPages<CodeGroup>("/api/admin/code-groups"),
    ).toEqual(groups);
    expect(fetcher.mock.calls[1][0]).toContain("page=2");
  });
  it("불완전 페이지는 저장 가능한 성공으로 취급하지 않음", async () => {
    fetcher.mockResolvedValueOnce(response(page([], 100, 3)));
    await expect(collectAdminPages("/api/admin/code-groups")).rejects.toThrow(
      "전체 목록",
    );
  });
});

describe("그룹/대응 작업창", () => {
  it("그룹 추가 본문은 사용자 필드만", async () => {
    const saved = vi.fn();
    render(<GroupDialog onClose={vi.fn()} onSaved={saved} />);
    fireEvent.change(screen.getByLabelText("그룹 코드 *"), {
      target: { value: "NEW_GROUP" },
    });
    fireEvent.change(screen.getByLabelText("그룹명 *"), {
      target: { value: "신규" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(saved).toHaveBeenCalledOnce());
    expect(JSON.parse((writes()[0][1] as RequestInit).body as string)).toEqual({
      cd_grp_id: "NEW_GROUP",
      cd_grp_nm: "신규",
    });
  });
  it("그룹 코드 규칙과 길이 카운터", () => {
    render(<GroupDialog onClose={vi.fn()} onSaved={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("그룹 코드 *"), {
      target: { value: "A1" },
    });
    expect(
      screen.getByText("영문 대문자와 _만 입력하세요."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("추가 값 이름1")).toHaveAttribute(
      "maxlength",
      "50",
    );
  });
  it("작업창 취소는 dirty 확인", () => {
    render(<GroupDialog onClose={vi.fn()} onSaved={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("그룹명 *"), {
      target: { value: "draft" },
    });
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(
      screen.getByText("저장하지 않은 내용이 있습니다. 나가면 사라집니다."),
    ).toBeInTheDocument();
  });
  it("대응 MAIN 기본 선택 없음, 그룹 뒤 활성 코드 API", async () => {
    render(
      <ExcelMapDialog groups={groups} onClose={vi.fn()} onSaved={vi.fn()} />,
    );
    expect(screen.getByLabelText("주/보조 *")).toHaveValue("");
    fireEvent.change(screen.getByLabelText("그룹 *"), {
      target: { value: "JOB" },
    });
    await screen.findByRole("option", { name: "백엔드" });
    expect(fetcher).toHaveBeenCalledWith("/api/codes/JOB", expect.anything());
    expect(
      screen.queryByRole("option", { name: "운영" }),
    ).not.toBeInTheDocument();
  });
  it("기존 대응 표기·대상 readonly, blur는 PATCH 안 함", async () => {
    render(<ExcelMapsTab groups={groups} onDirty={vi.fn()} />);
    await screen.findByText("개발자");
    expect(screen.getByText("직무 · 백엔드")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("메모 11"), {
      target: { value: "새 메모" },
    });
    fireEvent.blur(screen.getByLabelText("메모 11"));
    expect(writes()).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(writes()).toHaveLength(1));
    expect(JSON.parse((writes()[0][1] as RequestInit).body as string)).toEqual({
      map_role_cd: "MAIN",
      rmrk_desc: "새 메모",
      use_yn: "Y",
    });
  });
  it("행 취소는 마지막 저장본", async () => {
    render(<ExcelMapsTab groups={groups} onDirty={vi.fn()} />);
    await screen.findByText("개발자");
    fireEvent.change(screen.getByLabelText("메모 11"), {
      target: { value: "draft" },
    });
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.getByLabelText("메모 11")).toHaveValue("기존 메모");
  });
});

describe("기준값과 이력", () => {
  it("공통 목록 기본 건수는 대응에 적용하고 이력 초기 50건은 유지", async () => {
    useSharedPreferences.setState({ pageSize: 20, dense: true });
    const view = render(<ExcelMapsTab groups={groups} onDirty={vi.fn()} />);
    await screen.findByText("개발자");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/admin/excel-maps?page=1&size=20",
      expect.anything(),
    );
    expect(screen.getByLabelText("목록 건수")).toHaveValue("20");
    expect(screen.getByLabelText("좁게 보기")).toBeChecked();
    fireEvent.click(screen.getByLabelText("좁게 보기"));
    expect(useSharedPreferences.getState().dense).toBe(false);
    expect(localStorage.getItem("si.dense")).toBe("false");
    view.unmount();
    render(<CodeHistoryTab groups={groups} />);
    await screen.findByText("기본 사용자");
    expect(screen.getByLabelText("목록 건수")).toHaveValue("50");
  });
  it("전체 CLOSED 조회 전 적용월/저장 비활성", async () => {
    let resolve!: (r: Response) => void;
    fetcher.mockReturnValueOnce(
      new Promise<Response>((r) => {
        resolve = r;
      }),
    );
    render(<ParamDialog row={param} onClose={vi.fn()} onSaved={vi.fn()} />);
    expect(screen.getByLabelText("적용 시작월")).toBeDisabled();
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/month-close?from=0001-01&to=9999-12",
    );
    await act(async () =>
      resolve(response([{ month: "2026-09", status: "CLOSED" }])),
    );
    expect(screen.getByLabelText("적용 시작월")).toHaveAttribute(
      "min",
      "2026-10",
    );
  });
  it("마감월 및 숫자 범위는 저장 불가", async () => {
    render(<ParamDialog row={param} onClose={vi.fn()} onSaved={vi.fn()} />);
    await waitFor(() =>
      expect(screen.getByLabelText("적용 시작월")).not.toBeDisabled(),
    );
    fireEvent.change(screen.getByLabelText("적용 시작월"), {
      target: { value: "2026-09" },
    });
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("적용 시작월"), {
      target: { value: "2026-11" },
    });
    fireEvent.change(screen.getByLabelText("새 값 *"), {
      target: { value: "101" },
    });
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
  });
  it("0은 유효하고 이름·단위는 요청에서 제외", async () => {
    const saved = vi.fn();
    render(<ParamDialog row={param} onClose={vi.fn()} onSaved={saved} />);
    await waitFor(() =>
      expect(screen.getByLabelText("적용 시작월")).not.toBeDisabled(),
    );
    fireEvent.change(screen.getByLabelText("적용 시작월"), {
      target: { value: "2026-11" },
    });
    fireEvent.change(screen.getByLabelText("새 값 *"), {
      target: { value: "0" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(writes()[0][0]).toBe("/api/admin/params/VAT_RATE/versions/2026-11");
    expect(JSON.parse((writes()[0][1] as RequestInit).body as string)).toEqual({
      num_val: 0,
    });
  });
  it("문자형은 빈 값 차단", async () => {
    render(
      <ParamDialog
        row={{ ...param, num_val: null, txt_val: "기존" }}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    await waitFor(() =>
      expect(screen.getByLabelText("적용 시작월")).not.toBeDisabled(),
    );
    fireEvent.change(screen.getByLabelText("새 값 *"), {
      target: { value: "" },
    });
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
  });
  it("적용 이력 미래 행도 표시", async () => {
    render(<ParamVersions paramKey={param.oprt_std_id} />);
    await screen.findByText("2026-11");
    expect(screen.getByText("2000-01")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("변경 이력은 읽기전용·NULL 변경자·PARAM 한글", async () => {
    render(<CodeHistoryTab groups={groups} />);
    await screen.findByText("기본 사용자");
    expect(screen.getByRole("cell", { name: "기준값" })).toBeInTheDocument();
    expect(screen.getByText("수정")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("목록 건수")).toHaveValue("50");
  });
});

describe("공통 자리 화면과 overlay", () => {
  it.each([
    "UI-01",
    "UI-03",
    "UI-04",
    "UI-05",
    "UI-07",
    "UI-08",
    "UI-09",
    "UI-10",
    "UI-11",
    "UI-12",
    "UI-13",
    "UI-14",
    "UI-16",
    "UI-17",
    "UI-18",
    "UI-19",
    "UI-20",
    "UI-21",
    "UI-22",
    "UI-23",
    "UI-24",
    "UI-25",
    "UI-28",
  ])("%s는 업무 기능 없는 자리", async (screenId) => {
    render(<PlaceholderScreen screenId={screenId} name="API 화면명" />);
    await screen.findByText("이 화면은 다음 단계에서 만듭니다.");
    expect(
      screen.getByRole("heading", { name: "API 화면명" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("PC 전용 자리 차단", () => {
    render(
      <PlaceholderScreen
        screenId="UI-14"
        name="화면명"
        mobile={false}
        isMobile
      />,
    );
    expect(screen.getByText("PC에서 이용해 주세요")).toBeInTheDocument();
  });
  it("월마감 배지와 안내", async () => {
    render(
      <PlaceholderScreen
        screenId="UI-19"
        name="월마감"
        periodType="MONTH"
        period="2026-09"
      />,
    );
    await screen.findByText("마감");
    expect(screen.getByText(/고칠 내용은 다음 달/)).toBeInTheDocument();
  });
  it("검색은 실제 leaf 부분일치·원래 순서", () => {
    const onSelect = vi.fn();
    const menu = [
      {
        menuId: 1,
        name: "재무",
        screenId: null,
        icon: null,
        mobile: true,
        children: [
          {
            menuId: 2,
            name: "부가세",
            screenId: "UI-01",
            icon: null,
            mobile: true,
            children: [],
          },
          {
            menuId: 3,
            name: "다른 화면",
            screenId: "UI-02",
            icon: null,
            mobile: true,
            children: [],
          },
        ],
      },
    ];
    render(
      <AdminScreenSearch menus={menu} onClose={vi.fn()} onSelect={onSelect} />,
    );
    fireEvent.change(screen.getByLabelText("화면 이름 또는 ID"), {
      target: { value: "ui-01" },
    });
    expect(
      screen.queryByRole("button", { name: /다른 화면/ }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /부가세/ }));
    expect(onSelect).toHaveBeenCalledWith(menu[0].children[0]);
  });
  it("탭 초과 확인은 후보와 명시 확인·취소", () => {
    const confirm = vi.fn();
    render(
      <AdminTabLimitDialog
        candidateName="직무"
        onCancel={vi.fn()}
        onConfirm={confirm}
      />,
    );
    expect(confirm).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "닫기 확인" }));
    expect(confirm).toHaveBeenCalledOnce();
  });
});
