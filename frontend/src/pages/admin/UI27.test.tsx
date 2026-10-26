import {
  render,
  screen,
  fireEvent,
  waitFor,
  within,
  act,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { UI27, renderAdminScreen } from "./UI27";
import * as api from "./adminApi";
import { getMenus, getMonthClose } from "../shared/sharedApi";
import { SharedWorkspace } from "../shared/SharedWorkspace";
import { ApiFailure } from "../../shared/http";
import { usePreferences } from "../../shared/preferences";
import { validAdminParam } from "./AdminParamRegion";

vi.mock("./adminApi", async (original) => ({
  ...(await original<typeof import("./adminApi")>()),
  listCodeGroups: vi.fn(),
  listManagedCodes: vi.fn(),
  listExcelMaps: vi.fn(),
  listCodeHistory: vi.fn(),
  listCurrentParams: vi.fn(),
  listParamVersions: vi.fn(),
  createCodeGroup: vi.fn(),
  saveGroupCodes: vi.fn(),
  createExcelMap: vi.fn(),
  updateExcelMap: vi.fn(),
  saveParamVersion: vi.fn(),
}));
vi.mock("../shared/sharedApi", async (original) => ({
  ...(await original<typeof import("../shared/sharedApi")>()),
  getMenus: vi.fn(),
  getMonthClose: vi.fn(),
}));
const groups: api.CodeGroup[] = [
  {
    cd_grp_id: "JOB",
    cd_grp_nm: "직무",
    cd_grp_se_cd: "BIZ",
    sort_seq: 1,
    use_yn: "Y",
    attr1_nm: "기본 단가(원)",
    attr2_nm: null,
    attr3_nm: null,
    codeCount: 2,
  },
  {
    cd_grp_id: "CONTRACT_TYPE",
    cd_grp_nm: "계약 유형",
    cd_grp_se_cd: "SYSTEM",
    sort_seq: 2,
    use_yn: "Y",
    attr1_nm: null,
    attr2_nm: null,
    attr3_nm: null,
    codeCount: 1,
  },
];
const codeRows: api.ManagedCode[] = [
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
const mapping: api.ExcelMap = {
  map_id: "12",
  excel_lbl_nm: "기존 표기",
  trns_cd_grp_id: "JOB",
  trns_cd: "OPS",
  cd_grp_nm: "직무",
  cd_nm: "운영",
  map_role_cd: "MAIN",
  rmrk_desc: "기존 메모",
  use_yn: "Y",
};
const param: api.OperatingParam = {
  oprt_std_id: "VAT_RATE",
  aply_bgn_ym: "2000-01",
  oprt_std_nm: "부가세율",
  num_val: 10,
  txt_val: null,
  unit_nm: "%",
  use_plc_desc: "정산",
};
function page<T>(
  items: T[],
  total = items.length,
  index = 0,
): api.AdminPage<T> {
  return { items, total, page: index, size: 50 };
}
const failure = (field?: string) =>
  new ApiFailure(
    {
      success: false,
      meta: {},
      code: "VALIDATION_ERROR",
      field,
      message: "입력값을 확인하세요.",
      requestId: "admin-1",
    },
    400,
  );
function mount() {
  return render(<UI27 />);
}
async function codesReady() {
  await screen.findByLabelText("BE cd_nm");
}
async function tab(name: string) {
  await act(async () => fireEvent.click(screen.getByRole("tab", { name })));
}

beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
  usePreferences.getState().update({ pageSize: 50, dense: false });
  vi.mocked(api.listCodeGroups).mockResolvedValue(page(groups));
  vi.mocked(api.listManagedCodes).mockImplementation(async (group) =>
    page(
      group === "JOB"
        ? codeRows
        : [{ ...codeRows[0], dtl_cd: "TK", cd_nm: "턴키", attr1_val: null }],
    ),
  );
  vi.mocked(api.listExcelMaps).mockResolvedValue(page([mapping]));
  vi.mocked(api.listCodeHistory).mockResolvedValue(page([]));
  vi.mocked(api.listCurrentParams).mockResolvedValue(page([param]));
  vi.mocked(api.listParamVersions).mockResolvedValue(page([param]));
  vi.mocked(getMonthClose).mockResolvedValue([
    { month: "2026-09", status: "CLOSED" },
    { month: "2026-10", status: "OPEN" },
  ]);

  vi.mocked(api.saveGroupCodes).mockResolvedValue();
  vi.mocked(api.createCodeGroup).mockResolvedValue();
  vi.mocked(api.createExcelMap).mockResolvedValue();
  vi.mocked(api.updateExcelMap).mockResolvedValue();
  vi.mocked(api.saveParamVersion).mockResolvedValue();
  vi.stubGlobal(
    "matchMedia",
    vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

describe("공통코드 화면과 내부 작업 창", () => {
  it("모든 그룹 페이지와 코드 페이지를 수집하고 첫 그룹을 선택한다", async () => {
    vi.mocked(api.listCodeGroups).mockImplementation(async (index) =>
      page([groups[index ?? 0]], 2, index),
    );
    vi.mocked(api.listManagedCodes).mockImplementation(async (_group, index) =>
      page([codeRows[index ?? 0]], 2, index),
    );
    mount();
    await codesReady();
    await screen.findByLabelText("OPS cd_nm");
    expect(api.listCodeGroups).toHaveBeenCalledWith(1, 100);
    expect(api.listManagedCodes).toHaveBeenCalledWith("JOB", 1, 100);
    expect(
      screen.getByRole("columnheader", { name: "기본 단가(원)" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("OPS 사용")).not.toBeChecked();
  });
  it("중지 행은 남고 저장된 코드 값은 읽기 전용이다", async () => {
    mount();
    await codesReady();
    expect(screen.getByLabelText("BE dtl_cd")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("OPS 사용")).not.toBeChecked();
    expect(screen.getByLabelText("OPS cd_nm")).toHaveValue("운영");
  });
  it("SYSTEM은 추가 버튼 없이 이름과 순서만 수정한다", async () => {
    mount();
    await codesReady();
    fireEvent.click(
      screen.getByRole("button", { name: /계약 유형 SYSTEM 잠금/ }),
    );
    await screen.findByLabelText("TK cd_nm");
    expect(
      screen.queryByRole("button", { name: "코드 추가" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("TK 사용")).toBeDisabled();
    expect(screen.getByLabelText("TK aply_bgn_dt")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("TK sort_seq")).not.toHaveAttribute(
      "readonly",
    );
    expect(
      screen.getByText("시스템 코드는 이름과 순서만 바꿀 수 있습니다."),
    ).toBeInTheDocument();
  });
  it("새 코드는 최대 순서 다음에 추가하고 전체 목록으로 저장한다", async () => {
    mount();
    await codesReady();
    fireEvent.click(screen.getByRole("button", { name: "코드 추가" }));
    fireEvent.change(screen.getByLabelText("신규 dtl_cd"), {
      target: { value: "TEST1" },
    });
    fireEvent.change(screen.getByLabelText("TEST1 cd_nm"), {
      target: { value: "테스트" },
    });
    expect(screen.getByLabelText("TEST1 sort_seq")).toHaveValue(3);
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(api.saveGroupCodes).toHaveBeenCalledWith(
        "JOB",
        expect.arrayContaining([
          codeRows[1],
          expect.objectContaining({
            dtl_cd: "TEST1",
            use_yn: "Y",
            sort_seq: 3,
          }),
        ]),
      ),
    );
    expect(
      await screen.findByText("공통코드를 저장했습니다."),
    ).toBeInTheDocument();
  });
  it("서버의 행 오류는 첫 입력으로 초점을 옮기고 draft를 보존한다", async () => {
    vi.mocked(api.saveGroupCodes).mockRejectedValue(failure("[0].cd_nm"));
    mount();
    await codesReady();
    fireEvent.change(screen.getByLabelText("BE cd_nm"), {
      target: { value: "바뀐 이름" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(screen.getByLabelText("BE cd_nm")).toHaveFocus(),
    );
    expect(screen.getByLabelText("BE cd_nm")).toHaveValue("바뀐 이름");
    expect(screen.getByText("문의 번호: admin-1")).toBeInTheDocument();
  });
  it("blur 필수 오류와 최대 길이를 표시한다", async () => {
    mount();
    await codesReady();
    const name = screen.getByLabelText("BE cd_nm");
    fireEvent.change(name, { target: { value: "" } });
    fireEvent.blur(name);
    expect(await screen.findByText("이름을 입력하세요.")).toBeInTheDocument();
    expect(name).toHaveAttribute("maxlength", "100");
  });
  it("그룹 이동은 미저장 확인 후 계속 작성에서 상태를 유지하고 나가기에서 폐기한다", async () => {
    mount();
    await codesReady();
    fireEvent.change(screen.getByLabelText("BE cd_nm"), {
      target: { value: "작성중" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: /계약 유형 SYSTEM 잠금/ }),
    );
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "저장하지 않은 내용이 있습니다. 나가면 사라집니다.",
    );
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(screen.getByLabelText("BE cd_nm")).toHaveValue("작성중");
    fireEvent.click(
      screen.getByRole("button", { name: /계약 유형 SYSTEM 잠금/ }),
    );
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    expect(await screen.findByLabelText("TK cd_nm")).toBeInTheDocument();
  });
  it("하위 탭 이동은 보호하고 코드 취소는 마지막 저장본을 바로 복원한다", async () => {
    mount();
    await codesReady();
    fireEvent.change(screen.getByLabelText("BE cd_nm"), {
      target: { value: "작성중" },
    });
    await tab("변경 이력");
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(screen.getByLabelText("BE cd_nm")).toHaveValue("작성중");
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByLabelText("BE cd_nm")).toHaveValue("백엔드");
  });
  it("저장 시 빈 코드의 지정 오류를 표시하고 첫 오류 입력에 초점을 둔다", async () => {
    mount();
    await codesReady();
    fireEvent.click(screen.getByRole("button", { name: "코드 추가" }));
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(await screen.findByText("코드를 입력하세요.")).toBeInTheDocument();
    expect(screen.getByLabelText("신규 dtl_cd")).toHaveFocus();
    expect(screen.getByLabelText("신규 dtl_cd")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(api.saveGroupCodes).not.toHaveBeenCalled();
  });
  it("사용 체크는 추가 확인 없이 draft로만 반영하고 전체 저장에서 확정한다", async () => {
    mount();
    await codesReady();
    fireEvent.click(screen.getByLabelText("BE 사용"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByLabelText("BE 사용")).not.toBeChecked();
    expect(api.saveGroupCodes).not.toHaveBeenCalled();
    expect(
      screen.queryByRole("button", { name: "삭제" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(api.saveGroupCodes).toHaveBeenCalledWith("JOB", [
        { ...codeRows[0], use_yn: "N" },
        codeRows[1],
      ]),
    );
  });
  it("저장 중 재클릭을 차단한다", async () => {
    vi.mocked(api.saveGroupCodes).mockReturnValue(new Promise(() => {}));
    mount();
    await codesReady();
    fireEvent.change(screen.getByLabelText("BE cd_nm"), {
      target: { value: "작성중" },
    });
    const button = screen.getByRole("button", { name: "저장" });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(button).toBeDisabled();
    expect(api.saveGroupCodes).toHaveBeenCalledTimes(1);
  });
  it("그룹 추가는 이름과 식별자만 입력받고 서버 소유 필드는 보내지 않는다", async () => {
    mount();
    await codesReady();
    fireEvent.click(screen.getByRole("button", { name: "그룹 추가" }));
    const dialog = screen.getByRole("dialog", { name: "그룹 추가" });
    fireEvent.change(within(dialog).getByLabelText(/그룹 코드/), {
      target: { value: "NEW_GROUP" },
    });
    fireEvent.change(within(dialog).getByLabelText(/그룹명/), {
      target: { value: "새 그룹" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(api.createCodeGroup).toHaveBeenCalledWith({
        cd_grp_id: "NEW_GROUP",
        cd_grp_nm: "새 그룹",
      }),
    );
  });
  it("빈 그룹은 지정 빈 결과를 표시한다", async () => {
    vi.mocked(api.listCodeGroups).mockResolvedValue(page([]));
    mount();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
  });
  it("조회 오류는 재시도하며 오류 토스트를 만들지 않는다", async () => {
    vi.mocked(api.listCodeGroups).mockRejectedValueOnce(new Error("연결 오류"));
    mount();
    expect(await screen.findByRole("alert")).toHaveTextContent("연결 오류");
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await codesReady();
  });
});

describe("대응·기준값·변경 이력", () => {
  it.each(["map_role_cd", "use_yn"] as const)(
    "대응 편집의 %s 서버 오류를 해당 입력에 표시한다",
    async (field) => {
      vi.mocked(api.updateExcelMap).mockRejectedValue(failure(field));
      mount();
      await codesReady();
      await tab("엑셀 표기 대응");
      await screen.findByText("직무 · 운영");
      fireEvent.click(screen.getByRole("button", { name: "편집" }));
      const form = screen.getByRole("form", { name: "대응 편집" });
      fireEvent.change(within(form).getByLabelText("메모"), {
        target: { value: "남을 메모" },
      });
      fireEvent.click(within(form).getByRole("button", { name: "저장" }));
      const input = within(form).getByLabelText(
        field === "map_role_cd" ? "주/보조" : "사용",
      );
      await waitFor(() =>
        expect(input).toHaveAttribute("aria-invalid", "true"),
      );
      expect(input).toHaveFocus();
      expect(
        within(form).getByText("입력값을 확인하세요."),
      ).toBeInTheDocument();
      expect(within(form).getByLabelText("메모")).toHaveValue("남을 메모");
    },
  );
  it("대응 사용 변경은 저장 한 번으로 허용 필드만 확정한다", async () => {
    mount();
    await codesReady();
    await tab("엑셀 표기 대응");
    await screen.findByText("직무 · 운영");
    fireEvent.click(screen.getByRole("button", { name: "편집" }));
    fireEvent.click(screen.getByLabelText("사용"));
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(api.updateExcelMap).toHaveBeenCalledWith("12", { use_yn: "N" }),
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("전체 달력이 마감이면 적용월 입력과 저장을 허용하지 않는다", async () => {
    vi.mocked(getMonthClose).mockResolvedValue([
      { month: "9999-12", status: "CLOSED" },
    ]);
    mount();
    await codesReady();
    await tab("기준값");
    await screen.findByText("부가세율");
    fireEvent.click(screen.getByRole("button", { name: "값 변경" }));
    await screen.findByText(/마지막 마감월/);
    expect(screen.getByLabelText(/적용 시작월/)).toBeDisabled();
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    expect(api.saveParamVersion).not.toHaveBeenCalled();
  });
  it("이력 초기화는 조건만 해제하고 조회 시 전체 결과를 요청한다", async () => {
    mount();
    await codesReady();
    await tab("변경 이력");
    await waitFor(() =>
      expect(api.listCodeHistory).toHaveBeenCalledWith(undefined, 0, 50),
    );
    fireEvent.change(screen.getByLabelText("그룹"), {
      target: { value: "JOB" },
    });
    fireEvent.click(screen.getByRole("button", { name: "조회" }));
    await waitFor(() =>
      expect(api.listCodeHistory).toHaveBeenLastCalledWith("JOB", 0, 50),
    );
    const calls = vi.mocked(api.listCodeHistory).mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: "초기화" }));
    expect(screen.getByLabelText("그룹")).toHaveValue("");
    expect(api.listCodeHistory).toHaveBeenCalledTimes(calls);
    fireEvent.submit(
      screen.getByRole("button", { name: "조회" }).closest("form")!,
    );
    await waitFor(() =>
      expect(api.listCodeHistory).toHaveBeenLastCalledWith(undefined, 0, 50),
    );
  });
  it("기존 중지 코드의 이름은 조인 응답으로 보여주고 변경은 허용 필드만 PATCH한다", async () => {
    mount();
    await codesReady();
    await tab("엑셀 표기 대응");
    await screen.findByText("직무 · 운영");
    fireEvent.click(screen.getByRole("button", { name: "편집" }));
    const form = screen.getByRole("form", { name: "대응 편집" });
    fireEvent.change(within(form).getByLabelText("메모"), {
      target: { value: "수정 메모" },
    });
    fireEvent.click(within(form).getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(api.updateExcelMap).toHaveBeenCalledWith("12", {
        rmrk_desc: "수정 메모",
      }),
    );
  });
  it("대응 추가는 MAIN 명시와 useCodes의 활성 선택만 사용한다", async () => {
    // useCodes closes over getCodeOptions, so mock HTTP rather than the same-module export.
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          success: true,
          meta: {},
          data: {
            success: true,
            meta: {},
            data: [
              {
                code: "BE",
                name: "백엔드",
                attr1: null,
                attr2: null,
                attr3: null,
              },
            ],
          },
        }),
      ),
    );
    vi.stubGlobal("fetch", fetcher);
    mount();
    await codesReady();
    await tab("엑셀 표기 대응");
    await screen.findByText("직무 · 운영");
    fireEvent.click(screen.getByRole("button", { name: "대응 추가" }));
    const dialog = screen.getByRole("dialog", { name: "대응 추가" });
    fireEvent.change(within(dialog).getByLabelText(/엑셀 표기/), {
      target: { value: "테스트표기" },
    });
    fireEvent.change(within(dialog).getByLabelText(/그룹/), {
      target: { value: "JOB" },
    });
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        "/api/codes/JOB",
        expect.any(Object),
      ),
    );
    expect(
      await within(dialog).findByRole("option", { name: "백엔드" }),
    ).toHaveValue("BE");
    expect(
      within(dialog).queryByRole("option", { name: "운영" }),
    ).not.toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText(/대응 코드/), {
      target: { value: "BE" },
    });
    expect(within(dialog).getByLabelText(/주\/보조/)).toHaveValue("");
    expect(within(dialog).getByRole("button", { name: "저장" })).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/주\/보조/), {
      target: { value: "MAIN" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(api.createExcelMap).toHaveBeenCalledWith(
        expect.objectContaining({
          trns_cd_grp_id: "JOB",
          trns_cd: "BE",
          map_role_cd: "MAIN",
        }),
      ),
    );
  });
  it("대응 저장 오류는 편집 내용과 불변 표기·대상을 유지한다", async () => {
    vi.mocked(api.updateExcelMap).mockRejectedValue(failure("rmrk_desc"));
    mount();
    await codesReady();
    await tab("엑셀 표기 대응");
    await screen.findByText("직무 · 운영");
    fireEvent.click(screen.getByRole("button", { name: "편집" }));
    fireEvent.change(screen.getByLabelText("메모"), {
      target: { value: "남을 메모" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(screen.getByLabelText("메모")).toHaveFocus());
    expect(screen.getByLabelText("메모")).toHaveValue("남을 메모");
    expect(screen.queryByLabelText("엑셀 표기")).not.toBeInTheDocument();
  });
  it("적용월 창은 전체 마감 범위를 읽고 마감월 이하 저장을 막는다", async () => {
    mount();
    await codesReady();
    await tab("기준값");
    await screen.findByText("부가세율");
    fireEvent.click(screen.getByRole("button", { name: "값 변경" }));
    await waitFor(() =>
      expect(getMonthClose).toHaveBeenCalledWith("0001-01", "9999-12"),
    );
    await screen.findByText(/마지막 마감월/);
    fireEvent.change(screen.getByLabelText(/적용 시작월/), {
      target: { value: "2026-09" },
    });
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/적용 시작월/), {
      target: { value: "2026-11" },
    });
    fireEvent.change(screen.getByLabelText(/새 값/), {
      target: { value: "11" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(api.saveParamVersion).toHaveBeenCalledWith("VAT_RATE", "2026-11", {
        num_val: "11",
      }),
    );
  });
  it("마감 조회 실패 시 OPEN으로 가정하지 않고 재시도를 제공한다", async () => {
    vi.mocked(getMonthClose).mockRejectedValueOnce(new Error("마감 조회 실패"));
    mount();
    await codesReady();
    await tab("기준값");
    await screen.findByText("부가세율");
    fireEvent.click(screen.getByRole("button", { name: "값 변경" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "마감 조회 실패",
    );
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText(/마지막 마감월/);
    expect(screen.getByRole("button", { name: "저장" })).not.toBeDisabled();
  });
  it("적용월 이력은 미래를 포함한 모든 페이지를 모은다", async () => {
    vi.mocked(api.listParamVersions).mockImplementation(async (_key, index) =>
      page(
        [
          index === 0
            ? { ...param, aply_bgn_ym: "2026-11", num_val: 11 }
            : param,
        ],
        2,
        index,
      ),
    );
    mount();
    await codesReady();
    await tab("기준값");
    await screen.findByText("부가세율");
    fireEvent.click(screen.getByRole("button", { name: "이력 보기" }));
    expect(
      await within(screen.getByLabelText("기준값 적용 이력")).findByText(
        "2026-11",
      ),
    ).toBeInTheDocument();
    expect(api.listParamVersions).toHaveBeenCalledWith("VAT_RATE", 1, 100);
    expect(
      within(screen.getByLabelText("기준값 적용 이력")).getByText("11 %"),
    ).toBeInTheDocument();
    expect(
      within(screen.getByLabelText("기준값 적용 이력")).getByText("10 %"),
    ).toBeInTheDocument();
  });
  it("숫자 비율과 문자형 경계는 임의 반올림 없이 검증한다", () => {
    expect(validAdminParam("0", param)).toBe(true);
    expect(validAdminParam("100", param)).toBe(true);
    expect(validAdminParam("-1", param)).toBe(false);
    expect(validAdminParam("101", param)).toBe(false);
    expect(validAdminParam("1.00001", param)).toBe(false);
    expect(
      validAdminParam("", { ...param, num_val: null, txt_val: "문자" }),
    ).toBe(false);
    expect(
      validAdminParam("가".repeat(201), {
        ...param,
        num_val: null,
        txt_val: "문자",
      }),
    ).toBe(false);
  });
  it("변경 이력은 NULL 변경자를 기본 사용자로 표시하고 읽기 전용이다", async () => {
    vi.mocked(api.listCodeHistory).mockResolvedValue(
      page([
        {
          chg_at: "2026-10-01T09:00:00",
          cd_grp_id: "PARAM",
          cd_grp_nm: "기준값",
          dtl_cd: "VAT_RATE",
          cd_chg_ty_cd: "UPDATE",
          item_nm: "적용 시작월",
          bfr_val: "10",
          aft_val: "11",
          chg_user_id: null,
        },
      ]),
    );
    mount();
    await codesReady();
    await tab("변경 이력");
    expect(await screen.findByText("기본 사용자")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("그룹"), {
      target: { value: "PARAM" },
    });
    fireEvent.click(screen.getByRole("button", { name: "조회" }));
    await waitFor(() =>
      expect(api.listCodeHistory).toHaveBeenCalledWith("PARAM", 0, 50),
    );
  });
  it("공유 셸에 UI-27을 등록하는 단방향 콜백과 모바일 안내를 재사용한다", async () => {
    vi.mocked(getMenus).mockResolvedValue([
      {
        menuId: 10,
        name: "시스템",
        screenId: null,
        icon: null,
        mobile: true,
        children: [
          {
            menuId: 41,
            name: "공통코드 관리",
            screenId: "UI-27",
            icon: null,
            mobile: false,
            children: [],
          },
        ],
      },
    ]);
    vi.stubGlobal(
      "matchMedia",
      vi.fn().mockReturnValue({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }),
    );
    render(
      <MemoryRouter initialEntries={["/UI-27"]}>
        <SharedWorkspace renderScreen={renderAdminScreen} />
      </MemoryRouter>,
    );
    expect(await screen.findByText("PC에서 이용해 주세요")).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "코드" })).not.toBeInTheDocument();
  });
});
