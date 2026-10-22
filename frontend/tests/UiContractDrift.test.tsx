import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { PeriodBar } from "../src/pages/shared/PeriodBar";
import { ParamsTab } from "../src/pages/admin/ParamsTab";
import { CodeTab } from "../src/pages/admin/CodeTab";
import { AppRouter } from "../src/app/router";
import type { CodeGroup } from "../src/pages/admin/client";

const json = (data: unknown) =>
  new Response(JSON.stringify({ success: true, data }));
const page = (items: unknown[]) =>
  json({ items, total: items.length, page: 1, size: 100 });
const rejected = (field: string, message: string) =>
  new Response(
    JSON.stringify({
      success: false,
      code: "INVALID_INPUT",
      field,
      message,
      requestId: "contract-request",
    }),
    { status: 400 },
  );
const param = {
  oprt_std_id: "VAT_RATE",
  oprt_std_nm: "부가세",
  num_val: 10,
  txt_val: null,
  unit_nm: "%",
  aply_bgn_ym: "2026-10",
  use_plc_desc: "계산",
};
const group: CodeGroup = {
  cd_grp_id: "JOB",
  cd_grp_nm: "직무",
  cd_grp_se_cd: "BIZ",
  sort_seq: 1,
  use_yn: "Y",
  codeCount: 1,
  attr1_nm: "기본 단가(원)",
  attr2_nm: null,
  attr3_nm: null,
};
const code = {
  dtl_cd: "BE",
  cd_nm: "백엔드",
  sort_seq: 1,
  use_yn: "Y",
  attr1_val: "200000",
  attr2_val: null,
  attr3_val: null,
  aply_bgn_dt: null,
};
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
afterEach(() => vi.unstubAllGlobals());

async function openParam(fetcher: ReturnType<typeof vi.fn>) {
  vi.stubGlobal("fetch", fetcher);
  render(<ParamsTab />);
  fireEvent.click(await screen.findByRole("button", { name: "값 변경" }));
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
  );
}
const paramRead = (url: string) =>
  url.startsWith("/api/month-close") ? json([]) : page([param]);

describe("user-facing UI contract drift", () => {
  it("moves months across years, requeries status, and disables calendar endpoints", async () => {
    const fetcher = vi.fn(async (_url: string) => json([]));
    vi.stubGlobal("fetch", fetcher);
    render(<PeriodBar screenId="UI-19" />);
    change("조회월", "2026-12");
    fireEvent.click(screen.getByRole("button", { name: "다음 월" }));
    expect(screen.getByLabelText("조회월")).toHaveValue("2027-01");
    await waitFor(() =>
      expect(fetcher.mock.calls.at(-1)?.[0]).toBe(
        "/api/month-close?from=2027-01&to=2027-01",
      ),
    );
    fireEvent.click(screen.getByRole("button", { name: "이전 월" }));
    expect(screen.getByLabelText("조회월")).toHaveValue("2026-12");
    change("조회월", "0001-01");
    expect(screen.getByRole("button", { name: "이전 월" })).toBeDisabled();
    change("조회월", "9999-12");
    expect(screen.getByRole("button", { name: "다음 월" })).toBeDisabled();
  });

  it("retains the selected month on read failure and retries without moving it", async () => {
    let fail = true;
    const fetcher = vi.fn(async (url: string) => {
      if (url.includes("from=2027-01") && fail) {
        fail = false;
        throw new Error("offline");
      }
      return json([]);
    });
    vi.stubGlobal("fetch", fetcher);
    render(<PeriodBar screenId="UI-14" />);
    await screen.findByText("작업중");
    change("조회월", "2026-12");
    fireEvent.click(screen.getByRole("button", { name: "다음 월" }));
    await screen.findByText("다시 시도");
    expect(screen.getByLabelText("조회월")).toHaveValue("2027-01");
    fireEvent.click(screen.getByText("다시 시도"));
    await screen.findByText("작업중");
    expect(screen.getByLabelText("조회월")).toHaveValue("2027-01");
  });

  it("validates parameter values on blur and focuses the first invalid value on save", async () => {
    const fetcher = vi.fn(async (url: string, _init?: RequestInit) =>
      paramRead(url),
    );
    await openParam(fetcher);
    change("새 값", "101");
    fireEvent.blur(screen.getByLabelText("새 값"));
    expect(screen.getByLabelText("새 값")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(screen.getByText("올바른 값을 입력하세요.")).toBeVisible();
    change("적용 시작월", "2026-11");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(screen.getByLabelText("새 값")).toHaveFocus();
    expect(
      fetcher.mock.calls.every(
        (call) => !call[1]?.method || call[1]?.method === "GET",
      ),
    ).toBe(true);
  });

  it("shows a server month error beside the month input, focuses it, and preserves the form", async () => {
    const fetcher = vi.fn(async (url: string, init?: RequestInit) =>
      init?.method === "PUT"
        ? rejected("month", "마감된 월은 변경할 수 없습니다.")
        : paramRead(url),
    );
    await openParam(fetcher);
    change("새 값", "11");
    change("적용 시작월", "2026-11");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByText("문의 번호 contract-request");
    const month = screen.getByLabelText("적용 시작월");
    expect(month).toHaveAttribute("aria-invalid", "true");
    expect(month).toHaveAccessibleDescription(
      "마감된 월은 변경할 수 없습니다.",
    );
    expect(month).toHaveFocus();
    expect(screen.getByLabelText("새 값")).toHaveValue("11");
    expect(screen.getByRole("dialog")).toBeVisible();
  });

  it("focuses the server-rejected code field and keeps the edited draft", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init?: RequestInit) =>
        init?.method === "PUT"
          ? rejected("[0].cd_nm", "이름을 확인하세요.")
          : page([code]),
      ),
    );
    render(<CodeTab group={group} onDirtyChange={() => {}} />);
    await screen.findByLabelText("이름 1");
    change("이름 1", "수정");
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByText("문의 번호 contract-request");
    expect(screen.getByLabelText("이름 1")).toHaveFocus();
    expect(screen.getByLabelText("이름 1")).toHaveValue("수정");
    expect(screen.getByLabelText("이름 1")).toHaveAccessibleDescription(
      "이름을 확인하세요.",
    );
  });

  it.each(["BIZ", "SYSTEM"] as const)(
    "anonymous users see menus and actions with %s business restrictions only",
    async (kind) => {
      Object.defineProperty(window, "innerWidth", {
        configurable: true,
        value: 1280,
      });
      vi.stubGlobal(
        "fetch",
        vi.fn(async (url: string) => {
          if (url === "/api/menus")
            return json([
              {
                menuId: 1,
                name: "시스템",
                screenId: null,
                icon: null,
                mobile: true,
                children: [
                  {
                    menuId: 2,
                    name: "공통코드 관리",
                    screenId: "UI-27",
                    icon: null,
                    mobile: false,
                    children: [],
                  },
                ],
              },
            ]);
          return url.includes("/codes?")
            ? page([code])
            : page([{ ...group, cd_grp_se_cd: kind }]);
        }),
      );
      render(
        <MemoryRouter initialEntries={["/UI-27"]}>
          <AppRouter />
        </MemoryRouter>,
      );
      await screen.findByLabelText("이름 1");
      expect(
        within(screen.getByLabelText("업무 메뉴")).getAllByRole("button", {
          name: "공통코드 관리",
        }).length,
      ).toBeGreaterThan(0);
      expect(screen.getByRole("button", { name: "그룹 추가" })).toBeVisible();
      expect(screen.getByRole("button", { name: "저장" })).toBeEnabled();
      expect(screen.getByLabelText("코드 1")).toHaveAttribute("readonly");
      expect(screen.getByLabelText("이름 1")).not.toHaveAttribute("readonly");
      if (kind === "SYSTEM") {
        expect(
          screen.queryByRole("button", { name: "코드 추가" }),
        ).not.toBeInTheDocument();
        expect(screen.getByLabelText("사용 1")).toBeDisabled();
        expect(screen.getByLabelText("기본 단가(원) 1")).toHaveAttribute(
          "readonly",
        );
      } else {
        expect(screen.getByRole("button", { name: "코드 추가" })).toBeEnabled();
        expect(screen.getByLabelText("사용 1")).toBeEnabled();
        change("기본 단가(원) 1", "250000");
        expect(screen.getByLabelText("기본 단가(원) 1")).toHaveValue("250000");
      }
      expect(screen.queryByLabelText("비밀번호")).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "삭제" }),
      ).not.toBeInTheDocument();
    },
  );
});
