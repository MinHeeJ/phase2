import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI33Screen from "./UI33Screen";
import {
  candidateWorkspaceState,
  type CandidateDetail,
  type CandidateInput,
} from "./client";

const fixture = (): CandidateDetail => ({
  cand_id: 1,
  cand_no: "CAND-0001",
  cand_nm: "가 후보",
  cand_se_cd: "FL",
  vndr_ptnr_id: null,
  job_cd: "BE",
  grd_cd: "SENIOR",
  skill: ["JAVA"],
  hope_untprc_amt: 3000000,
  avail_dt: "2026-11-01",
  cntct_stts_cd: "NONE",
  tel_no: "010-1234-5678",
  source: "REFERRAL",
  rmrk_desc: "메모",
  use_yn: "Y",
  dev_id: null,
  last_cntct_dt: null,
  created_at: "2026-09-01T00:00:00",
  contacts: [],
  matching_slots: [],
});
let rows: CandidateDetail[],
  requests: { url: string; method: string; body?: Record<string, unknown> }[],
  failList: boolean,
  failWrite: boolean,
  failDetail: boolean;
const codeRows: Record<string, { code: string; name: string }[]> = {
  JOB: [
    { code: "BE", name: "백엔드" },
    { code: "FE", name: "프런트엔드" },
  ],
  GRADE: [{ code: "SENIOR", name: "고급" }],
  SKILL: [{ code: "JAVA", name: "자바" }],
  EMP_TYPE: [
    { code: "FL", name: "프리랜서" },
    { code: "OS", name: "외주" },
  ],
  CONTACT_STATUS: [
    { code: "NONE", name: "연락 전" },
    { code: "DONE", name: "연락함" },
    { code: "INTV", name: "면접 예정" },
    { code: "HOLD", name: "보류" },
    { code: "SIGNED", name: "계약 완료" },
  ],
  CANDIDATE_SOURCE: [{ code: "REFERRAL", name: "추천" }],
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failure = (field: string | null) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code: "INVALID_INPUT",
      message: "저장에 실패했습니다.",
      field,
      requestId: "candidate-request",
    }),
    { status: 400, headers: { "Content-Type": "application/json" } },
  );
beforeEach(() => {
  localStorage.clear();
  vi.stubGlobal("innerWidth", 1280);
  rows = [fixture()];
  requests = [];
  failList = false;
  failWrite = false;
  failDetail = false;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string, init?: RequestInit) => {
      const method = init?.method ?? "GET",
        body = init?.body ? JSON.parse(String(init.body)) : undefined;
      requests.push({ url: path, method, body });
      if (path.startsWith("/api/codes/"))
        return response(codeRows[path.split("/").at(-1)!] ?? []);
      if (path.startsWith("/api/partners/options"))
        return response([{ id: "9", no: "V-9", name: "협력사" }]);
      if (path === "/api/candidates/shortages")
        return response({
          items: [
            {
              job_cd: "BE",
              grd_cd: "SENIOR",
              first_shortage_month: "2026-12",
              candidate_count: 1,
            },
          ],
          page: 0,
          size: 50,
          total: 1,
        });
      if (path.startsWith("/api/candidates?")) {
        if (failList) return failure(null);
        return response({
          items: rows,
          page: 0,
          size: 50,
          total: rows.length,
          kpi: {
            candidate_count: rows.length,
            interview_count: 0,
            available_by_next_month_count: rows.length,
            uncontacted_over_30_days_count: rows.length,
          },
        });
      }
      if (path === "/api/candidates" && method === "POST") {
        if (failWrite) return failure("cand_nm");
        const row = { ...fixture(), ...body, cand_id: 2, cand_no: "CAND-0002" };
        rows.push(row);
        return response(row);
      }
      const found = path.match(
        /^\/api\/candidates\/(\d+)(?:\/(contacts|convert))?$/,
      );
      if (found) {
        const row = rows.find((r) => r.cand_id === Number(found[1]))!;
        if (method === "GET") return failDetail ? failure(null) : response(row);
        if (failWrite) return failure("cand_nm");
        if (found[2] === "contacts") {
          row.contacts.push({
            ...body,
            user_id: null,
            created_at: "2026-10-22T12:00:00",
          } as CandidateDetail["contacts"][number]);
          row.cntct_stts_cd = String(body.cntct_stts_cd);
          row.last_cntct_dt = String(body.cntct_dt);
          return response(row);
        }
        if (found[2] === "convert") {
          row.dev_id = 8;
          row.cntct_stts_cd = "SIGNED";
          return response({ candidate: row, developer: { dev_id: 8 } });
        }
        Object.assign(row, body);
        return response(row);
      }
      throw new Error(`Unexpected API ${path}`);
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const show = (filters = {}) =>
  render(
    <MemoryRouter
      initialEntries={[
        { pathname: "/UI-33", state: candidateWorkspaceState(filters) },
      ]}
    >
      <UI33Screen />
    </MemoryRouter>,
  );
const change = (testid: string, value: string) =>
  fireEvent.change(screen.getByTestId(testid), { target: { value } });
const openDetail = async () => {
  fireEvent.click(await screen.findByTestId("candidate-row-1"));
  return screen.findByRole("dialog", { name: "후보 상세" });
};
const writeRequests = () => requests.filter((r) => r.method !== "GET");

describe("candidate workspace", () => {
  it("renders database-backed KPI, specified columns, shared pool table and status colours", async () => {
    show();
    const row = await screen.findByTestId("candidate-row-1");
    expect(screen.getAllByRole("columnheader")).toHaveLength(12);
    expect(within(row).getByText("CAND-0001")).toBeVisible();
    expect(within(row).getByText("프리랜서")).toBeVisible();
    expect(within(row).getByText("연락 전").className).toContain("neutral");
    expect(row.closest("table")?.className).toContain("table");
    expect(screen.getByText("다음 달까지 가용")).toBeVisible();
    expect(writeRequests()).toHaveLength(0);
    expect(requests.some((r) => r.url.includes("sort=cand_id%2Casc"))).toBe(
      true,
    );
  });
  it.each([
    ["INTV", "warning"],
    ["SIGNED", "success"],
    ["DONE", "info"],
    ["HOLD", "locked"],
  ])("uses distinct %s state badge", async (state, tone) => {
    rows[0].cntct_stts_cd = state;
    show();
    const row = await screen.findByTestId("candidate-row-1");
    expect(
      within(row).getByText(
        codeRows.CONTACT_STATUS.find((c) => c.code === state)!.name,
      ).className,
    ).toContain(tone);
  });
  it("retains shortage filters from workspace navigation and while opening/closing details", async () => {
    show({ job: "BE", grade: "SENIOR", shortage_month: "2026-12" });
    await openDetail();
    fireEvent.click(screen.getByTestId("candidate-detail-close"));
    expect(screen.getByLabelText("조회 직무")).toHaveValue("BE");
    expect(screen.getByTestId("candidate-filter-month")).toHaveValue("2026-12");
    expect(
      requests.some(
        (r) =>
          r.url.includes("grade=SENIOR") &&
          r.url.includes("shortage_month=2026-12"),
      ),
    ).toBe(true);
  });
  it("shortage shortcut sets exact job grade and available-month scope", async () => {
    show();
    fireEvent.click(await screen.findByTestId("candidate-shortcut-0"));
    await waitFor(() =>
      expect(
        requests.some(
          (r) =>
            r.url.includes("job=BE&grade=SENIOR") &&
            r.url.includes("shortage_month=2026-12"),
        ),
      ).toBe(true),
    );
  });
  it("submits filters AND, sorts and paginates with zero-based API", async () => {
    show();
    await screen.findByTestId("candidate-row-1");
    change("candidate-filter-job", "BE");
    change("candidate-filter-name", "가");
    fireEvent.click(screen.getByTestId("candidate-query"));
    await waitFor(() =>
      expect(requests.some((r) => r.url.includes("q=%EA%B0%80&job=BE"))).toBe(
        true,
      ),
    );
    fireEvent.click(screen.getByTestId("candidate-sort-cand-nm"));
    await waitFor(() =>
      expect(requests.some((r) => r.url.includes("sort=cand_nm%2Casc"))).toBe(
        true,
      ),
    );
    fireEvent.change(screen.getByLabelText("목록 건수"), {
      target: { value: "20" },
    });
    await waitFor(() =>
      expect(requests.some((r) => r.url.includes("size=20"))).toBe(true),
    );
  });
  it("shows meaningful empty/reset state without business writes", async () => {
    rows = [];
    show();
    expect(
      await screen.findByText(/조건에 맞는 결과가 없습니다/),
    ).toBeVisible();
    fireEvent.click(screen.getByTestId("candidate-empty-reset"));
    expect(writeRequests()).toHaveLength(0);
  });
  it("shows failed list and retry without stale data", async () => {
    failList = true;
    show();
    expect(await screen.findByText("저장에 실패했습니다.")).toBeVisible();
    expect(screen.queryByTestId("candidate-row-1")).not.toBeInTheDocument();
    failList = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("candidate-row-1")).toBeVisible();
  });
  it("shows loading state until API resolves", async () => {
    const original = globalThis.fetch;
    let resolve!: (v: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn((path: string, init?: RequestInit) =>
        path.startsWith("/api/candidates?")
          ? new Promise<Response>((r) => {
              resolve = r;
            })
          : original(path, init),
      ),
    );
    show();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    resolve(
      response({
        items: [],
        total: 0,
        page: 0,
        size: 50,
        kpi: {
          candidate_count: 0,
          interview_count: 0,
          available_by_next_month_count: 0,
          uncontacted_over_30_days_count: 0,
        },
      }),
    );
    expect(await screen.findByTestId("candidate-empty-reset")).toBeVisible();
  });
  it("registers a valid FL with only writable payload and re-queries saved record", async () => {
    show();
    await screen.findByTestId("candidate-row-1");
    fireEvent.click(screen.getByTestId("candidate-create"));
    await screen.findByRole("option", { name: "백엔드" });
    change("candidate-name", "새 후보");
    change("candidate-job", "BE");
    change("candidate-price", "0");
    change("candidate-available", "2026-12-01");
    fireEvent.click(screen.getByTestId("candidate-save"));
    await screen.findByRole("dialog", { name: "후보 상세" });
    const request = writeRequests()[0];
    expect(request.url).toBe("/api/candidates");
    expect(request.method).toBe("POST");
    expect(request.body).toMatchObject({
      cand_nm: "새 후보",
      cand_se_cd: "FL",
      vndr_ptnr_id: null,
      hope_untprc_amt: 0,
      skill: [],
    });
    expect(request.body).not.toHaveProperty("cand_id");
    expect(requests.some((r) => r.url === "/api/candidates/2")).toBe(true);
  });
  it("required validation prevents create without discarding input", async () => {
    show();
    await screen.findByTestId("candidate-row-1");
    fireEvent.click(screen.getByTestId("candidate-create"));
    fireEvent.click(screen.getByTestId("candidate-save"));
    expect(await screen.findByText("이름을 입력해 주세요.")).toBeVisible();
    expect(writeRequests()).toHaveLength(0);
  });
  it.each([
    ["candidate-job", "", "직무를 골라 주세요."],
    ["candidate-available", "", "가능일을 입력해 주세요."],
    ["candidate-price", "-1", "희망단가는 0 이상의 정수로 입력하세요."],
    ["candidate-price", "1.5", "희망단가는 0 이상의 정수로 입력하세요."],
  ])(
    "validates %s before registering without losing the name",
    async (field, value, message) => {
      show();
      await screen.findByTestId("candidate-row-1");
      fireEvent.click(screen.getByTestId("candidate-create"));
      await screen.findByRole("option", { name: "백엔드" });
      change("candidate-name", "입력 보존");
      change("candidate-job", "BE");
      change("candidate-price", "0");
      change("candidate-available", "2026-12-01");
      change(field, value);
      fireEvent.click(screen.getByTestId("candidate-save"));
      expect(await screen.findByText(message)).toBeVisible();
      expect(screen.getByTestId("candidate-name")).toHaveValue("입력 보존");
      expect(writeRequests()).toHaveLength(0);
    },
  );
  it("rejects OS without a selected vendor before making a write", async () => {
    show();
    await screen.findByTestId("candidate-row-1");
    fireEvent.click(screen.getByTestId("candidate-create"));
    await screen.findByRole("option", { name: "외주" });
    change("candidate-name", "외주 후보");
    change("candidate-type", "OS");
    change("candidate-job", "BE");
    change("candidate-price", "0");
    change("candidate-available", "2026-12-01");
    fireEvent.click(screen.getByTestId("candidate-save"));
    expect(
      await screen.findByText("외주는 사용 중인 협력사를 골라 주세요."),
    ).toBeVisible();
    expect(writeRequests()).toHaveLength(0);
  });
  it("saves an edit to the selected candidate and refreshes list and detail", async () => {
    show();
    await openDetail();
    fireEvent.click(screen.getByTestId("candidate-edit"));
    change("candidate-name", "수정 완료");
    change("candidate-price", "4000000");
    fireEvent.click(screen.getByTestId("candidate-save"));
    const drawer = await screen.findByRole("dialog", { name: "후보 상세" });
    expect(
      await within(drawer).findByText("수정 완료 · CAND-0001"),
    ).toBeVisible();
    expect(screen.getByTestId("candidate-row-1")).toHaveTextContent(
      "수정 완료",
    );
    expect(writeRequests()).toHaveLength(1);
    expect(writeRequests()[0]).toMatchObject({
      url: "/api/candidates/1",
      method: "PUT",
      body: { cand_nm: "수정 완료", hope_untprc_amt: 4000000 },
    });
    expect(writeRequests()[0].body).not.toHaveProperty("cand_id");
    expect(writeRequests()[0].body).not.toHaveProperty("contacts");
  });
  it.each(["create", "update"])(
    "recovers failed %s readback without resending the committed write",
    async (operation) => {
      show();
      if (operation === "update") {
        await openDetail();
        fireEvent.click(screen.getByTestId("candidate-edit"));
      } else {
        await screen.findByTestId("candidate-row-1");
        fireEvent.click(screen.getByTestId("candidate-create"));
      }
      await screen.findByRole("option", { name: "백엔드" });
      change("candidate-name", "저장 완료 후보");
      change("candidate-job", "BE");
      change("candidate-price", "0");
      change("candidate-available", "2026-12-01");
      failDetail = true;
      fireEvent.click(screen.getByTestId("candidate-save"));
      expect(
        await screen.findByText(
          "저장 후 조회에 실패했습니다. 현재 상태를 확인하세요.",
        ),
      ).toBeVisible();
      expect(screen.getByTestId("candidate-save")).toBeDisabled();
      expect(screen.getByTestId("candidate-name")).toHaveValue(
        "저장 완료 후보",
      );
      expect(writeRequests()).toHaveLength(1);
      failDetail = false;
      fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
      const drawer = await screen.findByRole("dialog", { name: "후보 상세" });
      expect(
        await within(drawer).findByText(/저장 완료 후보 · CAND-/),
      ).toBeVisible();
      expect(writeRequests()).toHaveLength(1);
      expect(rows).toHaveLength(operation === "create" ? 2 : 1);
      expect(screen.queryByTestId("candidate-editor")).not.toBeInTheDocument();
    },
  );
  it("OS requires vendor and FL disables and clears it", async () => {
    show();
    await screen.findByTestId("candidate-row-1");
    fireEvent.click(screen.getByTestId("candidate-create"));
    await screen.findByRole("option", { name: "외주" });
    change("candidate-type", "OS");
    expect(screen.getByTestId("candidate-vendor")).not.toBeDisabled();
    change("candidate-vendor", "9");
    change("candidate-type", "FL");
    expect(screen.getByTestId("candidate-vendor")).toBeDisabled();
    expect(screen.getByTestId("candidate-vendor")).toHaveValue("");
  });
  it("edits existing candidate without identity fields and retains inputs on field error", async () => {
    failWrite = true;
    show();
    await openDetail();
    fireEvent.click(screen.getByTestId("candidate-edit"));
    await screen.findByTestId("candidate-form");
    change("candidate-name", "수정 입력");
    fireEvent.click(screen.getByTestId("candidate-save"));
    await screen.findByText("문의 번호 candidate-request");
    expect(screen.getByTestId("candidate-name")).toHaveValue("수정 입력");
    expect(writeRequests()[0].method).toBe("PUT");
    expect(writeRequests()[0].body).not.toHaveProperty("cand_no");
    expect(writeRequests()[0].body).not.toHaveProperty("dev_id");
  });
  it("latest contact locks status, converted candidate prevents duplicate conversion", async () => {
    rows[0].dev_id = 8;
    rows[0].cntct_stts_cd = "SIGNED";
    show();
    await openDetail();
    expect(screen.getByTestId("candidate-convert-open")).toBeDisabled();
    fireEvent.click(screen.getByTestId("candidate-edit"));
    expect(await screen.findByTestId("candidate-status")).toBeDisabled();
  });
  it("contact history also locks generic status without conversion", async () => {
    rows[0].contacts = [
      {
        cntct_dt: "2026-10-20",
        cntct_stts_cd: "INTV",
        memo: "면접",
        user_id: null,
        created_at: "2026-10-20T12:00:00",
      },
    ];
    show();
    await openDetail();
    fireEvent.click(screen.getByTestId("candidate-edit"));
    expect(await screen.findByTestId("candidate-status")).toBeDisabled();
  });
  it("warns before abandoning a dirty editor", async () => {
    show();
    await screen.findByTestId("candidate-row-1");
    fireEvent.click(screen.getByTestId("candidate-create"));
    change("candidate-name", "미저장");
    fireEvent.click(screen.getByTestId("candidate-cancel"));
    expect(
      await screen.findByRole("dialog", { name: "미저장 변경" }),
    ).toBeVisible();
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByTestId("candidate-name")).toHaveValue("미저장");
  });
  it("matching and contacts tabs have honest empty states and no assignment action", async () => {
    show();
    await openDetail();
    fireEvent.click(screen.getByRole("tab", { name: "맞는 자리" }));
    expect(screen.getByText("맞는 자리가 없습니다.")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "가배정" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "연락 기록" }));
    expect(screen.getByText("연락 기록이 없습니다.")).toBeVisible();
  });
  it("matching shows unknown margin as dash, estimated badge and server warning", async () => {
    rows[0].matching_slots = [
      {
        need_id: 4,
        oppt_id: 3,
        oppt_nm: "기회",
        job_cd: "BE",
        bgn_ym: "2026-12",
        monthly_price: 0,
        hope_untprc_amt: 3000000,
        team_expense: 0,
        margin_rate: null,
        estimated: true,
        warnings: ["월 단가 없음"],
      },
    ];
    show();
    await openDetail();
    fireEvent.click(screen.getByRole("tab", { name: "맞는 자리" }));
    const row = screen.getByTestId("candidate-slot-4");
    expect(within(row).getByText("추정")).toBeVisible();
    expect(within(row).getByText("월 단가 없음")).toBeVisible();
    expect(row).not.toHaveTextContent("0.00%");
  });
  it("detail failure is recoverable", async () => {
    failDetail = true;
    show();
    await openDetail();
    expect(await screen.findByText("저장에 실패했습니다.")).toBeVisible();
    failDetail = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("candidate-contact-open")).toBeVisible();
  });
  it("appends contact using selected candidate id and refreshes status", async () => {
    show();
    await openDetail();
    fireEvent.click(screen.getByTestId("candidate-contact-open"));
    await screen.findByRole("dialog", { name: "연락 기록 추가" });
    change("candidate-contact-date", "2020-01-01");
    change("candidate-contact-status", "INTV");
    change("candidate-contact-memo", "면접 약속");
    fireEvent.click(screen.getByTestId("candidate-action-save"));
    await waitFor(() =>
      expect(
        screen.queryByTestId("candidate-action-dialog"),
      ).not.toBeInTheDocument(),
    );
    expect(writeRequests()[0]).toMatchObject({
      url: "/api/candidates/1/contacts",
      body: {
        cntct_dt: "2020-01-01",
        cntct_stts_cd: "INTV",
        memo: "면접 약속",
      },
    });
  });
  it("rejects blank contact memo without a write", async () => {
    show();
    await openDetail();
    fireEvent.click(screen.getByTestId("candidate-contact-open"));
    change("candidate-contact-date", "2020-01-01");
    fireEvent.click(screen.getByTestId("candidate-action-save"));
    expect(
      await screen.findByText("메모는 1~1000자로 입력하세요."),
    ).toBeVisible();
    expect(writeRequests()).toHaveLength(0);
  });
  it("conversion uses candidate attributes not a name lookup and disables repeat", async () => {
    show();
    await openDetail();
    fireEvent.click(screen.getByTestId("candidate-convert-open"));
    await screen.findByRole("dialog", { name: "개발자로 등록" });
    change("candidate-developer-number", "NEW-CAND");
    fireEvent.click(screen.getByTestId("candidate-action-save"));
    await waitFor(() =>
      expect(
        screen.queryByTestId("candidate-action-dialog"),
      ).not.toBeInTheDocument(),
    );
    expect(writeRequests()[0]).toMatchObject({
      url: "/api/candidates/1/convert",
      body: {
        developer: {
          dev_nm: "가 후보",
          main_job_cd: "BE",
          emp_se_cd: "FL",
          skill: ["JAVA"],
          mng_no: "NEW-CAND",
        },
      },
    });
    expect(screen.getByTestId("candidate-convert-open")).toBeDisabled();
  });
});
