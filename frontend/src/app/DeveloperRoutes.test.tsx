import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppRouter } from "./router";
import {
  developerFixture,
  developerServer,
  response,
} from "../pages/developers/DeveloperTestSupport";
import { useSharedPreferences } from "../pages/shared/preferences";
import type { MenuNode } from "../pages/shared/api";

const menus: MenuNode[] = [
  {
    menuId: 1,
    name: "기준정보",
    screenId: null,
    icon: "db",
    mobile: true,
    children: [
      {
        menuId: 2,
        name: "대시보드",
        screenId: "UI-16",
        icon: null,
        mobile: true,
        children: [],
      },
      {
        menuId: 3,
        name: "개발자 풀",
        screenId: "UI-02",
        icon: null,
        mobile: true,
        children: [],
      },
      {
        menuId: 4,
        name: "단가 재협상 대상",
        screenId: "UI-32",
        icon: null,
        mobile: true,
        children: [],
      },
    ],
  },
];
let server: ReturnType<typeof developerServer>;
let fetcher: ReturnType<typeof vi.fn>;
let applicable: boolean;
let resultFails: boolean;
let formType: string;
function Location() {
  return <output aria-label="현재 주소">{useLocation().pathname}</output>;
}
function mount(path = "/UI-02") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Location />
      <AppRouter />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  localStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  server = developerServer();
  applicable = true;
  resultFails = false;
  formType = "B.8";
  fetcher = vi.fn(
    async (url: string, init?: RequestInit): Promise<Response> => {
      if (url === "/api/menus") return response(menus);
      if (url.startsWith("/api/month-close?")) return response([]);
      if (
        /^\/api\/developers\/1\/(assignments|monthly-costs|history|work-status)\?/.test(
          url,
        )
      )
        return response({ items: [], total: 0, page: 0, size: 50 });
      if (url === "/api/developers/disable") {
        server.state.rows = server.state.rows.map((row) => ({
          ...row,
          use_yn: "N",
        }));
        return response({
          disabled_ids: [1],
          blocked: [],
          disabled_count: 1,
          blocked_count: 0,
        });
      }
      if (url === "/api/uploads/validate") {
        formType = String((init!.body as FormData).get("form_type"));
        return response({
          upload_id: 101,
          form_type: formType,
          month: formType === "B.1" ? "2026-10" : null,
          row_count: 1,
          error_count: 0,
          warning_count: 0,
          can_apply: true,
        });
      }
      if (url === "/api/uploads/101")
        return resultFails
          ? new Response(
              JSON.stringify({
                success: false,
                meta: {},
                code: "VALIDATION_EXPIRED",
                message: "다시 올려 주세요.",
                requestId: "review-request",
              }),
              { status: 410 },
            )
          : response({
              upload_id: 101,
              form_type: formType,
              month: formType === "B.1" ? "2026-10" : null,
              row_count: 1,
              error_count: 0,
              warning_count: 0,
              can_apply: applicable,
              existing_salary_count: 0,
              overwrite_notice: null,
            });
      if (/^\/api\/uploads\/101\/(issues|rows)\?/.test(url))
        return response({ items: [], total: 0, page: 0, size: 50 });
      if (url === "/api/uploads/101/apply") {
        server.state.rows = server.state.rows.map((row) => ({
          ...row,
          latest_salary: { month: "2026-10", slry_amt: 4000000 },
        }));
        return response({ upload_id: 101, applied_count: 1 });
      }
      if (url.startsWith("/api/developers/export?"))
        return new Response(
          JSON.stringify({
            success: false,
            message: "파일 준비 실패",
            requestId: "export-request",
          }),
          { status: 500 },
        );
      return server.fetcher(url, init);
    },
  );
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const tabs = () => within(screen.getByLabelText("열린 화면"));
const uploadFile = () =>
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [new File(["xlsx"], "인력.xlsx")] },
  });

describe("developer route composition", () => {
  it("preserves list and registration drafts under one developer tab", async () => {
    mount();
    await screen.findByTestId("developer-row-1");
    fireEvent.change(screen.getByLabelText("이름·관리번호"), {
      target: { value: "조회 초안" },
    });
    fireEvent.click(screen.getByTestId("developer-create"));
    await screen.findByTestId("developer-registration");
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-32");
    expect(tabs().queryByText("단가 재협상 대상")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("이름"), {
      target: { value: "등록 초안" },
    });
    await waitFor(() =>
      expect(screen.getByLabelText("열린 화면")).toHaveTextContent(
        "● 개발자 풀",
      ),
    );
    fireEvent.click(tabs().getByRole("button", { name: "대시보드" }));
    fireEvent.click(tabs().getByRole("button", { name: "● 개발자 풀" }));
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-32");
    expect(screen.getByLabelText("이름")).toHaveValue("등록 초안");
    fireEvent.click(screen.getByLabelText("개발자 풀 닫기"));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "저장하지 않은 내용이 있습니다.",
    );
    fireEvent.click(screen.getByText("계속 작성"));
    fireEvent.click(screen.getByTestId("developer-back"));
    fireEvent.click(screen.getByText("나가기"));
    await waitFor(() =>
      expect(
        screen.queryByTestId("developer-registration"),
      ).not.toBeInTheDocument(),
    );
    expect(screen.getByLabelText("이름·관리번호")).toHaveValue("조회 초안");
  });
  it("direct registration reload selects UI-02 and locally suppresses the legacy menu in navigation and search", async () => {
    mount("/UI-32");
    await screen.findByTestId("developer-registration");
    expect(tabs().getByRole("button", { name: "개발자 풀" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(useSharedPreferences.getState().recent).toContain("UI-02");
    expect(useSharedPreferences.getState().recent).not.toContain("UI-32");
    expect(menus[0].children[2].screenId).toBe("UI-32");
    expect(
      within(screen.getByLabelText("화면 탐색")).queryByText(
        "단가 재협상 대상",
      ),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("화면 검색"));
    fireEvent.change(screen.getByLabelText("화면 이름 또는 ID"), {
      target: { value: "UI-32" },
    });
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "검색 결과가 없습니다.",
    );
  });
  it("saves registration back to the same tab and highlights the server-returned developer", async () => {
    mount("/UI-32");
    await screen.findByRole("option", { name: "백엔드" });
    const registration = within(screen.getByTestId("developer-registration"));
    fireEvent.change(registration.getByLabelText("이름"), {
      target: { value: "등록 인력" },
    });
    fireEvent.change(registration.getByLabelText("직원구분"), {
      target: { value: "FT" },
    });
    fireEvent.change(registration.getByLabelText("주 직무"), {
      target: { value: "BE" },
    });
    fireEvent.click(screen.getByTestId("developer-save"));
    expect((await screen.findByTestId("developer-row-3")).className).toContain(
      "highlight",
    );
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-02");
    expect(tabs().queryByText("단가 재협상 대상")).not.toBeInTheDocument();
  });
  it("mounts only the selected lifecycle panel using the selected numeric developer id", async () => {
    mount();
    fireEvent.click(await screen.findByTestId("developer-open-1"));
    await screen.findByTestId("developer-edit");
    expect(screen.getAllByRole("tab")).toHaveLength(5);
    expect(
      fetcher.mock.calls.some(([url]) => String(url).includes("/assignments?")),
    ).toBe(false);
    for (const [label, empty] of [
      ["투입 이력", "투입 이력이 없습니다."],
      ["근무 상태 이력", "등록된 근무 상태가 없습니다."],
      ["월별 원가", "급여 업로드로 만든 월별 원가가 없습니다."],
      ["변경 이력", "아직 바꾼 기록이 없습니다."],
    ]) {
      fireEvent.click(screen.getByRole("tab", { name: label }));
      expect(await screen.findByText(empty)).toBeVisible();
    }
    expect(screen.queryByText("투입 이력이 없습니다.")).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.some(
        ([url]) => String(url) === "/api/developers/1/history?page=0&size=50",
      ),
    ).toBe(true);
  });
  it.each([
    ["developer-upload", "SCR-UI-02-M4"],
    ["developer-salary-upload", "SCR-UI-02-M5"],
  ])("connects %s to the existing local dialog", async (control, id) => {
    mount();
    fireEvent.click(await screen.findByTestId(control));
    expect(screen.getByTestId("upload-selection")).toHaveAttribute(
      "data-screen-id",
      id,
    );
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-02");
    fireEvent.click(screen.getByTestId("upload-cancel"));
    expect(screen.queryByTestId("upload-selection")).not.toBeInTheDocument();
  });
  it("disable refreshes both the filtered list and the open detail without discarding list input", async () => {
    server.state.rows = [{ ...developerFixture, current_assignment_count: 0 }];
    mount();
    fireEvent.click(await screen.findByTestId("developer-open-1"));
    await screen.findByTestId("developer-edit");
    fireEvent.change(screen.getByLabelText("이름·관리번호"), {
      target: { value: "조회 초안" },
    });
    fireEvent.click(screen.getByTestId("developer-more"));
    fireEvent.click(screen.getByRole("menuitem", { name: "사용 중지" }));
    fireEvent.click(screen.getByTestId("developer-disable-confirm"));
    expect(await screen.findByText("사용을 중지한 사람입니다.")).toBeVisible();
    await waitFor(() =>
      expect(screen.queryByTestId("developer-row-1")).not.toBeInTheDocument(),
    );
    expect(screen.getByLabelText("이름·관리번호")).toHaveValue("조회 초안");
  });
  it("salary apply refreshes the open developer and reports the applied count", async () => {
    mount();
    fireEvent.click(await screen.findByTestId("developer-open-1"));
    await screen.findByTestId("developer-edit");
    fireEvent.click(screen.getByTestId("developer-salary-upload"));
    await waitFor(() => expect(screen.getByLabelText("연월")).toBeEnabled());
    fireEvent.change(screen.getByLabelText("연월"), {
      target: { value: "2026-10" },
    });
    uploadFile();
    fireEvent.click(screen.getByTestId("upload-submit"));
    await waitFor(() =>
      expect(screen.getByTestId("upload-apply")).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId("upload-apply"));
    expect(await screen.findByText("급여 1명분을 반영했습니다.")).toBeVisible();
    expect(
      await screen.findByText("최근 월 급여 2026-10 4,000,000 원"),
    ).toBeVisible();
  });
  it("authoritative review can_apply false blocks application even if validation allowed it", async () => {
    applicable = false;
    mount();
    fireEvent.click(await screen.findByTestId("developer-upload"));
    uploadFile();
    fireEvent.click(screen.getByTestId("upload-submit"));
    expect(await screen.findByTestId("upload-apply")).toBeDisabled();
    expect(fetcher.mock.calls.some(([url]) => url === "/api/uploads/101")).toBe(
      true,
    );
    fireEvent.click(screen.getByTestId("upload-apply"));
    expect(
      fetcher.mock.calls.some(([url]) => url === "/api/uploads/101/apply"),
    ).toBe(false);
  });
  it("expired review preserves server inquiry information and exposes no apply action", async () => {
    resultFails = true;
    mount();
    fireEvent.click(await screen.findByTestId("developer-upload"));
    uploadFile();
    fireEvent.click(screen.getByTestId("upload-submit"));
    expect(await screen.findByText("다시 올려 주세요.")).toBeVisible();
    expect(screen.getByText(/review-request/)).toBeVisible();
    expect(screen.queryByTestId("upload-apply")).not.toBeInTheDocument();
  });
  it("exports only supported business filters, reports failures, and does not forward paging", async () => {
    mount();
    await screen.findByTestId("developer-row-1");
    fireEvent.click(screen.getByTestId("developer-export"));
    expect(await screen.findByText("파일 준비 실패")).toBeVisible();
    const url = fetcher.mock.calls.find(([url]) =>
      String(url).startsWith("/api/developers/export?"),
    )![0];
    expect(url).toBe("/api/developers/export?use_yn=Y");
  });
  it("mobile direct registration redirects to read-only list and work-status panel", async () => {
    vi.stubGlobal("innerWidth", 767);
    mount("/UI-32");
    await waitFor(() =>
      expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-02"),
    );
    fireEvent.click(await screen.findByTestId("developer-open-1"));
    fireEvent.click(await screen.findByRole("tab", { name: "근무 상태 이력" }));
    await screen.findByText("등록된 근무 상태가 없습니다.");
    for (const id of [
      "developer-create",
      "developer-upload",
      "developer-salary-upload",
      "developer-template",
      "developer-edit",
      "developer-enable",
      "work-status-add",
    ])
      expect(screen.queryByTestId(id)).not.toBeInTheDocument();
  });
});
