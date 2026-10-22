import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { CommonCodes } from "../src/features/common-codes/CommonCodes";
import { Mappings } from "../src/features/common-codes/Mappings";
import { Parameters } from "../src/features/common-codes/Parameters";
import type { Group, Param } from "../src/services/api";

// HTTP fixtures are isolated to rendered tests, never application data.
const job: Group = {
  cd_grp_id: "JOB",
  cd_grp_nm: "직무",
  cd_grp_se_cd: "BIZ",
  sort_seq: 1,
  codeCount: 1,
  attr1_nm: null,
  attr2_nm: null,
  attr3_nm: null,
  use_yn: "Y",
};
const position = {
  ...job,
  cd_grp_id: "POSITION",
  cd_grp_nm: "직급",
  sort_seq: 2,
};
const param: Param = {
  oprt_std_id: "VAT_RATE",
  oprt_std_nm: "부가세율",
  aply_bgn_ym: "2026-01",
  num_val: 10,
  txt_val: null,
  unit_nm: "%",
  use_plc_desc: "원가 계산",
};
const reply = (data: unknown) =>
  new Response(JSON.stringify({ success: true, data, meta: {} }));
const page = (items: unknown[]) =>
  reply({ items, total: items.length, page: 1, size: 100 });
const failure = (field?: string) =>
  new Response(
    JSON.stringify({
      success: false,
      code: "INVALID_INPUT",
      field,
      message: "입력을 확인하세요.",
      requestId: "component-request",
    }),
    { status: 400 },
  );
afterEach(() => vi.unstubAllGlobals());

async function fillMap(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "대응 추가" }));
  const dialog = within(screen.getByRole("dialog"));
  await user.type(dialog.getByLabelText(/엑셀 표기/), "테스트표기");
  await user.selectOptions(
    dialog.getByRole("combobox", { name: /그룹/ }),
    "JOB",
  );
  await dialog.findByRole("option", { name: "백엔드" });
  await user.selectOptions(
    dialog.getByRole("combobox", { name: /^코드/ }),
    "BE",
  );
  await user.selectOptions(
    dialog.getByRole("combobox", { name: /주\/보조/ }),
    "MAIN",
  );
  return dialog;
}

it.each([
  ["trns_cd_grp_id", "그룹"],
  ["trns_cd", "코드"],
  ["map_role_cd", "주/보조"],
  ["rmrk_desc", "메모"],
])(
  "대응 추가 %s 서버 오류는 해당 입력 아래 표시·포커스·입력 보존",
  async (field, label) => {
    const user = userEvent.setup();
    const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
      if (init?.method === "POST") return failure(field);
      return path.startsWith("/api/codes/")
        ? reply([{ code: "BE", name: "백엔드" }])
        : page([]);
    });
    vi.stubGlobal("fetch", fetcher);
    render(<Mappings groups={[job]} toast={vi.fn()} />);
    const dialog = await fillMap(user);
    await user.click(dialog.getByRole("button", { name: "저장" }));
    const input = dialog.getByLabelText(
      new RegExp(`^${label.replace("/", "\\/")}`),
    );
    await waitFor(() => expect(input).toHaveFocus());
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription("입력을 확인하세요.");
    expect(dialog.getByLabelText(/엑셀 표기/)).toHaveValue("테스트표기");
    expect(dialog.getByRole("alert")).toHaveTextContent("component-request");
    const post = fetcher.mock.calls.find(
      ([, init]) => init?.method === "POST",
    )!;
    expect(JSON.parse(post[1]!.body as string)).toEqual({
      excel_lbl_nm: "테스트표기",
      trns_cd_grp_id: "JOB",
      trns_cd: "BE",
      map_role_cd: "MAIN",
      rmrk_desc: "",
    });
  },
);

it("대응 코드 조회 오류는 입력을 보존하고 읽기만 재시도한 뒤 저장 복구", async () => {
  const user = userEvent.setup();
  let attempts = 0;
  const fetcher = vi.fn(async (path: string, _init?: RequestInit) => {
    if (path.startsWith("/api/codes/"))
      return ++attempts === 1
        ? failure()
        : reply([{ code: "BE", name: "백엔드" }]);
    return page([]);
  });
  vi.stubGlobal("fetch", fetcher);
  render(<Mappings groups={[job]} toast={vi.fn()} />);
  await user.click(screen.getByRole("button", { name: "대응 추가" }));
  const dialog = within(screen.getByRole("dialog"));
  await user.type(dialog.getByLabelText(/엑셀 표기/), "테스트표기");
  await user.selectOptions(
    dialog.getByRole("combobox", { name: /그룹/ }),
    "JOB",
  );
  await dialog.findByRole("alert");
  expect(dialog.getByRole("button", { name: "저장" })).toBeDisabled();
  await user.click(dialog.getByRole("button", { name: "다시 시도" }));
  await dialog.findByRole("option", { name: "백엔드" });
  expect(dialog.getByRole("button", { name: "저장" })).toBeEnabled();
  expect(dialog.getByLabelText(/엑셀 표기/)).toHaveValue("테스트표기");
  expect(dialog.getByRole("combobox", { name: /주\/보조/ })).toHaveValue("");
  expect(fetcher.mock.calls.every(([, init]) => init?.method === "GET")).toBe(
    true,
  );
  expect(attempts).toBe(2);
});

it("대응 추가 성공은 창을 닫고 읽기 전용 목록을 재조회", async () => {
  const user = userEvent.setup();
  let added = false;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string, init?: RequestInit) => {
      if (init?.method === "POST") {
        added = true;
        return reply({});
      }
      if (path.startsWith("/api/codes/"))
        return reply([{ code: "BE", name: "백엔드" }]);
      return page(
        added
          ? [
              {
                map_id: "1",
                excel_lbl_nm: "테스트표기",
                trns_cd_grp_id: "JOB",
                trns_cd: "BE",
                cd_grp_nm: "직무",
                cd_nm: "백엔드",
                map_role_cd: "MAIN",
                rmrk_desc: "",
                use_yn: "Y",
              },
            ]
          : [],
      );
    }),
  );
  render(<Mappings groups={[job]} toast={vi.fn()} />);
  const dialog = await fillMap(user);
  await user.click(dialog.getByRole("button", { name: "저장" }));
  expect(await screen.findByRole("cell", { name: "테스트표기" })).toBeVisible();
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(screen.getByRole("cell", { name: "직무 · 백엔드" })).toBeVisible();
  expect(
    screen.queryByRole("button", { name: "저장" }),
  ).not.toBeInTheDocument();
});

it("그룹을 빠르게 바꿔도 늦은 코드 응답은 새 그룹 선택지를 덮지 않음", async () => {
  const user = userEvent.setup();
  let resolveFirst!: (response: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string) => {
      if (path === "/api/codes/JOB")
        return new Promise<Response>((resolve) => {
          resolveFirst = resolve;
        });
      if (path === "/api/codes/POSITION")
        return reply([{ code: "TL", name: "팀장" }]);
      return page([]);
    }),
  );
  render(<Mappings groups={[job, position]} toast={vi.fn()} />);
  await user.click(screen.getByRole("button", { name: "대응 추가" }));
  const dialog = within(screen.getByRole("dialog"));
  const group = dialog.getByRole("combobox", { name: /그룹/ });
  await user.selectOptions(group, "JOB");
  expect(dialog.getByRole("combobox", { name: /^코드/ })).toBeDisabled();
  await user.selectOptions(group, "POSITION");
  await dialog.findByRole("option", { name: "팀장" });
  await act(async () => {
    resolveFirst(reply([{ code: "BE", name: "백엔드" }]));
  });
  expect(
    dialog.queryByRole("option", { name: "백엔드" }),
  ).not.toBeInTheDocument();
  expect(dialog.getByRole("option", { name: "팀장" })).toBeVisible();
});

it("그룹 추가 후 목록을 재조회해도 기존 선택 그룹과 코드가 유지됨", async () => {
  const user = userEvent.setup();
  let added = false;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string, init?: RequestInit) => {
      if (init?.method === "POST") {
        added = true;
        return reply({});
      }
      if (path.startsWith("/api/admin/code-groups?"))
        return page(
          added
            ? [
                job,
                position,
                { ...job, cd_grp_id: "NEW", cd_grp_nm: "새 그룹", sort_seq: 3 },
              ]
            : [job, position],
        );
      return page([
        {
          dtl_cd: path.includes("/POSITION/") ? "TL" : "BE",
          cd_nm: "코드명",
          sort_seq: 1,
          use_yn: "Y",
          attr1_val: null,
          attr2_val: null,
          attr3_val: null,
          aply_bgn_dt: null,
        },
      ]);
    }),
  );
  render(
    <CommonCodes
      guard={(action) => action()}
      setDirty={vi.fn()}
      toast={vi.fn()}
    />,
  );
  await screen.findByLabelText("코드 1");
  await user.click(screen.getByRole("button", { name: /직급/ }));
  await waitFor(() =>
    expect(screen.getByLabelText("코드 1")).toHaveValue("TL"),
  );
  await user.click(screen.getByRole("button", { name: "그룹 추가" }));
  const dialog = within(screen.getByRole("dialog"));
  await user.type(dialog.getByLabelText(/그룹 코드/), "NEW");
  await user.type(dialog.getByLabelText(/그룹명/), "새 그룹");
  await user.click(dialog.getByRole("button", { name: "저장" }));
  await screen.findByRole("button", { name: /새 그룹/ });
  expect(screen.getByRole("button", { name: /직급/ })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  expect(screen.getByLabelText("코드 1")).toHaveValue("TL");
});

it("그룹 추가 field 오류는 창·입력을 유지하고 활성화된 입력으로 포커스", async () => {
  const user = userEvent.setup();
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_path: string, init?: RequestInit) =>
      init?.method === "POST" ? failure("cd_grp_id") : page([]),
    ),
  );
  render(
    <CommonCodes
      guard={(action) => action()}
      setDirty={vi.fn()}
      toast={vi.fn()}
    />,
  );
  await user.click(screen.getByRole("button", { name: "그룹 추가" }));
  const dialog = within(screen.getByRole("dialog"));
  await user.type(dialog.getByLabelText(/그룹 코드/), "NEW");
  await user.type(dialog.getByLabelText(/그룹명/), "새 그룹");
  await user.click(dialog.getByRole("button", { name: "저장" }));
  await waitFor(() => expect(dialog.getByLabelText(/그룹 코드/)).toHaveFocus());
  expect(dialog.getByLabelText(/그룹 코드/)).toHaveValue("NEW");
  expect(dialog.getByLabelText(/그룹 코드/)).toHaveAccessibleDescription(
    "3/30 입력을 확인하세요.",
  );
});

it.each(["-1", "101", ""])(
  "기준값 범위·필수 오류 %s는 인라인 표시하고 PUT하지 않음",
  async (invalid) => {
    const user = userEvent.setup();
    const fetcher = vi.fn(async (path: string) =>
      path.startsWith("/api/month-close") ? reply([]) : page([param]),
    );
    vi.stubGlobal("fetch", fetcher);
    render(<Parameters toast={vi.fn()} />);
    await user.click(await screen.findByRole("button", { name: "값 변경" }));
    const dialog = within(screen.getByRole("dialog"));
    await waitFor(() =>
      expect(dialog.getByRole("button", { name: "저장" })).toBeEnabled(),
    );
    const input = dialog.getByLabelText(/새 값/);
    await user.clear(input);
    if (invalid) await user.type(input, invalid);
    await user.tab();
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription("올바른 값을 입력하세요.");
    await user.click(dialog.getByRole("button", { name: "저장" }));
    expect(
      fetcher.mock.calls.some(([path]) => path.includes("/versions/")),
    ).toBe(false);
    expect(screen.getByRole("dialog")).toBeVisible();
  },
);

it("기준값 새 값만 PUT하고 서버 field 오류는 재활성화 후 입력으로 포커스", async () => {
  const user = userEvent.setup();
  const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === "PUT") return failure("num_val");
    return path.startsWith("/api/month-close") ? reply([]) : page([param]);
  });
  vi.stubGlobal("fetch", fetcher);
  render(<Parameters toast={vi.fn()} />);
  await user.click(await screen.findByRole("button", { name: "값 변경" }));
  const dialog = within(screen.getByRole("dialog"));
  await waitFor(() =>
    expect(dialog.getByRole("button", { name: "저장" })).toBeEnabled(),
  );
  await user.click(dialog.getByRole("button", { name: "저장" }));
  await waitFor(() => expect(dialog.getByLabelText(/새 값/)).toHaveFocus());
  expect(dialog.getByLabelText(/새 값/)).toHaveAccessibleDescription(
    "입력을 확인하세요.",
  );
  const put = fetcher.mock.calls.find(([, init]) => init?.method === "PUT")!;
  expect(JSON.parse(put[1]!.body as string)).toEqual({ num_val: 10 });
  expect(
    dialog.queryByRole("textbox", { name: /이름|단위|쓰는 곳/ }),
  ).not.toBeInTheDocument();
});
