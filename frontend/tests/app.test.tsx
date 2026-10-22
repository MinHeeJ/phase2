import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { App } from "../src/app/App";
import { usePreferences } from "../src/stores/preferences";
import type { MenuNode, Group, CodeRow } from "../src/services/api";
const group: Group = {
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
  attr1_val: "6000000",
  attr2_val: null,
  attr3_val: null,
  aply_bgn_dt: null,
};
const menus: MenuNode[] = [
  {
    menuId: 1,
    name: "시스템",
    screenId: null,
    icon: "gear",
    mobile: true,
    children: [
      {
        menuId: 2,
        name: "공통코드",
        screenId: "UI-27",
        icon: null,
        mobile: false,
        children: [],
      },
      {
        menuId: 3,
        name: "대시보드",
        screenId: "UI-16",
        icon: null,
        mobile: true,
        children: [],
      },
    ],
  },
];
let saveCount = 0;
let savedBody: CodeRow[] = [];
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", { value: 1280, writable: true });
  saveCount = 0;
  savedBody = [];
  usePreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    size: 50,
    dense: false,
  });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string, init?: RequestInit) => {
      let data: unknown;
      if (path === "/api/menus") data = menus;
      else if (path.startsWith("/api/admin/code-groups?"))
        data = { items: [group], total: 1, page: 1, size: 100 };
      else if (
        path.startsWith("/api/admin/code-groups/JOB/codes") &&
        init?.method === "PUT"
      ) {
        saveCount++;
        savedBody = JSON.parse(String(init.body));
        data = { saved: true };
      } else if (path.startsWith("/api/admin/code-groups/JOB/codes"))
        data = { items: [code], total: 1, page: 1, size: 100 };
      else data = { items: [], total: 0, page: 1, size: 50 };
      return new Response(JSON.stringify({ success: true, meta: {}, data }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());
it("비인증 메뉴·BIZ 편집·전체 저장 payload와 성공", async () => {
  const user = userEvent.setup();
  render(
    <MemoryRouter initialEntries={["/UI-27"]}>
      <App />
    </MemoryRouter>,
  );
  const name = await screen.findByLabelText("이름 1");
  expect(screen.getByLabelText("코드 1")).toHaveAttribute("readonly");
  expect(screen.getByRole("button", { name: "코드 추가" })).toBeVisible();
  await user.clear(name);
  await user.type(name, "변경 이름");
  await user.click(screen.getByRole("button", { name: "저장" }));
  await waitFor(() => expect(saveCount).toBe(1));
  expect(savedBody[0].cd_nm).toBe("변경 이름");
  expect(Object.keys(savedBody[0])).not.toContain("created_by");
  expect(await screen.findByText("공통코드를 저장했습니다.")).toBeVisible();
});
it("미저장 이동은 계속 작성/나가기, 취소는 복원", async () => {
  const user = userEvent.setup();
  render(
    <MemoryRouter initialEntries={["/UI-27"]}>
      <App />
    </MemoryRouter>,
  );
  await user.type(await screen.findByLabelText("이름 1"), "수정");
  await user.click(screen.getByRole("tab", { name: "기준값" }));
  expect(screen.getByRole("dialog")).toHaveTextContent(
    "저장하지 않은 내용이 있습니다.",
  );
  await user.click(screen.getByRole("button", { name: "계속 작성" }));
  expect(screen.getByLabelText("이름 1")).toHaveValue("백엔드 개발수정");
  await user.click(screen.getByRole("button", { name: "취소" }));
  expect(screen.getByLabelText("이름 1")).toHaveValue("백엔드 개발");
});
it("모바일에서는 PC 전용 메뉴/편집 대신 PC 안내", async () => {
  Object.defineProperty(window, "innerWidth", { value: 375, writable: true });
  render(
    <MemoryRouter initialEntries={["/UI-27"]}>
      <App />
    </MemoryRouter>,
  );
  expect(await screen.findByText("PC에서 이용해 주세요")).toBeVisible();
  expect(
    screen.queryByRole("button", { name: "저장" }),
  ).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "대시보드로" })).toBeVisible();
});
it("검색 단축키와 알림, 설정이 동작", async () => {
  const user = userEvent.setup();
  render(
    <MemoryRouter initialEntries={["/UI-16"]}>
      <App />
    </MemoryRouter>,
  );
  await screen.findByText("이 화면은 다음 단계에서 만듭니다.");
  fireEvent.keyDown(window, { key: "k", ctrlKey: true });
  expect(screen.getByRole("dialog", { name: "화면 검색" })).toBeVisible();
  fireEvent.keyDown(window, { key: "Escape" });
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "알림" }));
  expect(screen.getByText("새 알림이 없습니다")).toBeVisible();
});
