import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { MemoryRouter, useLocation } from "react-router-dom";
import { App } from "../src/app/App";
import { CodeEditor } from "../src/features/common-codes/CodeEditor";
import { Parameters } from "../src/features/common-codes/Parameters";
import { usePreferences } from "../src/stores/preferences";
import type { CodeRow, Group, MenuNode, Param } from "../src/services/api";

// Isolated HTTP responses for rendered component tests; never used by the application.
const job: Group = {
  cd_grp_id: "JOB",
  cd_grp_nm: "직무",
  cd_grp_se_cd: "BIZ",
  sort_seq: 1,
  codeCount: 1,
  attr1_nm: "기본 단가(원)",
  attr2_nm: null,
  attr3_nm: null,
  use_yn: "Y",
};
const code: CodeRow = {
  dtl_cd: "BE",
  cd_nm: "백엔드 개발",
  sort_seq: 1,
  use_yn: "Y",
  attr1_val: null,
  attr2_val: null,
  attr3_val: null,
  aply_bgn_dt: null,
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
  new Response(JSON.stringify({ success: true, meta: {}, data }));
const page = (items: unknown[]) =>
  reply({ items, total: items.length, page: 1, size: 100 });
const failure = () =>
  new Response(
    JSON.stringify({
      success: false,
      message: "조회 실패",
      requestId: "unit-request",
      code: "SERVER_ERROR",
    }),
    { status: 500 },
  );
function Location() {
  return <output aria-label="현재 경로">{useLocation().pathname}</output>;
}
function menusFor(id: string): MenuNode[] {
  return [
    {
      menuId: 1,
      name: "대시보드",
      screenId: null,
      icon: "grid",
      mobile: true,
      children: [
        {
          menuId: 11,
          name: "대시보드",
          screenId: "UI-16",
          icon: null,
          mobile: true,
          children: [],
        },
      ],
    },
    {
      menuId: 2,
      name: "시스템",
      screenId: null,
      icon: "gear",
      mobile: true,
      children: [
        {
          menuId: 21,
          name: id,
          screenId: id,
          icon: null,
          mobile: true,
          children: [],
        },
      ],
    },
  ];
}
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", { value: 1280, writable: true });
  usePreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    size: 50,
    dense: false,
  });
});
afterEach(() => vi.unstubAllGlobals());

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
])("명세 route /%s는 제목과 계약상 안내를 렌더링", async (id) => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string) =>
      reply(path === "/api/menus" ? menusFor(id) : []),
    ),
  );
  render(
    <MemoryRouter initialEntries={[`/${id}`]}>
      <App />
      <Location />
    </MemoryRouter>,
  );
  expect(
    await screen.findByText("이 화면은 다음 단계에서 만듭니다."),
  ).toBeVisible();
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
    id === "UI-16" ? "대시보드" : id,
  );
  expect(screen.getByLabelText("현재 경로")).toHaveTextContent(`/${id}`);
  expect(
    screen.queryByRole("button", { name: "저장" }),
  ).not.toBeInTheDocument();
});

it("공통코드 제목의 그룹 추가는 실제 창을 열고 다른 route에는 노출되지 않음", async () => {
  const user = userEvent.setup();
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string) =>
      path === "/api/menus"
        ? reply(menusFor("UI-27"))
        : page(path.includes("/JOB/codes") ? [code] : [job]),
    ),
  );
  render(
    <MemoryRouter initialEntries={["/UI-27"]}>
      <App />
      <Location />
    </MemoryRouter>,
  );
  await screen.findByLabelText("이름 1");
  await user.click(screen.getByRole("button", { name: "그룹 추가" }));
  expect(screen.getByRole("dialog", { name: "그룹 추가" })).toBeVisible();
  await user.click(
    within(screen.getByRole("dialog")).getByRole("button", { name: "취소" }),
  );
  await user.click(screen.getByTestId("brand-home"));
  expect(screen.getByLabelText("현재 경로")).toHaveTextContent("/UI-16");
  expect(
    screen.queryByRole("button", { name: "그룹 추가" }),
  ).not.toBeInTheDocument();
});

it("신규 코드가 기존 코드와 같아도 readonly가 되지 않아 바로 수정할 수 있음", async () => {
  const user = userEvent.setup();
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => page([code])),
  );
  render(
    <CodeEditor
      groups={[job]}
      guard={(a) => a()}
      setDirty={vi.fn()}
      toast={vi.fn()}
    />,
  );
  await screen.findByLabelText("코드 1");
  await user.click(screen.getByRole("button", { name: "코드 추가" }));
  const input = screen.getByLabelText("코드 2");
  await user.type(input, "BE");
  expect(input).not.toHaveAttribute("readonly");
  await user.clear(input);
  await user.type(input, "TEST1");
  expect(input).toHaveValue("TEST1");
});

it("다른 그룹 조회 실패 시 이전 그룹 코드를 숨기고 저장과 추가를 막음", async () => {
  const user = userEvent.setup();
  const other = { ...job, cd_grp_id: "POSITION", cd_grp_nm: "직급" };
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string) =>
      path.includes("/POSITION/") ? failure() : page([code]),
    ),
  );
  render(
    <CodeEditor
      groups={[job, other]}
      guard={(a) => a()}
      setDirty={vi.fn()}
      toast={vi.fn()}
    />,
  );
  await screen.findByLabelText("코드 1");
  await user.click(screen.getByRole("button", { name: /직급/ }));
  expect(await screen.findByRole("alert")).toHaveTextContent("조회 실패");
  expect(screen.queryByDisplayValue("BE")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "코드 추가" })).toBeDisabled();
});

it("마감 조회 실패 후 창·값을 보존하고 재시도로 허용월과 저장을 복구", async () => {
  const user = userEvent.setup();
  let attempts = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string) => {
      if (path.startsWith("/api/month-close"))
        return ++attempts === 1
          ? failure()
          : reply([{ month: "2026-09", status: "CLOSED" }]);
      return page([param]);
    }),
  );
  render(<Parameters toast={vi.fn()} />);
  await user.click(await screen.findByRole("button", { name: "값 변경" }));
  const dialog = screen.getByRole("dialog");
  expect(await within(dialog).findByRole("alert")).toHaveTextContent(
    "조회 실패",
  );
  expect(within(dialog).getByRole("button", { name: "저장" })).toBeDisabled();
  await user.click(within(dialog).getByRole("button", { name: "다시 시도" }));
  await waitFor(() =>
    expect(within(dialog).getByRole("button", { name: "저장" })).toBeEnabled(),
  );
  expect(within(dialog).getByLabelText(/적용 시작월/)).toHaveAttribute(
    "min",
    "2026-10",
  );
  expect(within(dialog).getByLabelText(/새 값/)).toHaveValue(10);
});

it("빠르게 다른 기준값 이력을 고르면 늦은 이전 응답이 현재 결과를 덮지 않음", async () => {
  const user = userEvent.setup();
  const second = {
    ...param,
    oprt_std_id: "TARGET_UTIL_RATE",
    oprt_std_nm: "목표 가동률",
    num_val: 80,
  };
  let resolveFirst!: (r: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string) => {
      if (path.includes("/VAT_RATE/versions"))
        return new Promise<Response>((resolve) => {
          resolveFirst = resolve;
        });
      if (path.includes("/TARGET_UTIL_RATE/versions")) return page([second]);
      return page([param, second]);
    }),
  );
  render(<Parameters toast={vi.fn()} />);
  await screen.findByText("부가세율");
  const buttons = screen.getAllByRole("button", { name: "이력 보기" });
  await user.click(buttons[0]);
  await user.click(buttons[1]);
  await waitFor(() =>
    expect(screen.queryByLabelText("불러오는 중")).not.toBeInTheDocument(),
  );
  await act(async () => {
    resolveFirst(page([param]));
  });
  const region = screen
    .getByRole("heading", { name: /목표 가동률 적용 이력/ })
    .closest("section")!;
  expect(within(region).getByRole("cell", { name: "80 %" })).toBeVisible();
});
