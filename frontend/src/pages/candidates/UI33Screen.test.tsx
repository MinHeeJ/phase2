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
import type { CandidateDetail } from "./client";

let row: CandidateDetail;
let writes: { path: string; body: Record<string, unknown> }[];
let requests: string[];
let readError: boolean;
const json = (data: unknown, status = 200) =>
  new Response(
    JSON.stringify(status === 200 ? { success: true, meta: {}, data } : data),
    { status, headers: { "Content-Type": "application/json" } },
  );
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  writes = [];
  requests = [];
  readError = false;
  row = {
    cand_id: 73,
    cand_no: "CAND-0073",
    cand_nm: "시험 후보",
    cand_se_cd: "FL",
    vndr_ptnr_id: null,
    job_cd: "BE",
    grd_cd: null,
    skill: ["JAVA"],
    hope_untprc_amt: 5000000,
    avail_dt: "2027-01-01",
    cntct_stts_cd: "NONE",
    tel_no: "***",
    source: null,
    rmrk_desc: null,
    use_yn: "Y",
    dev_id: null,
    last_cntct_dt: null,
    created_at: "2026-10-01T12:00:00",
    contacts: [],
    matching_slots: [],
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: string, init?: RequestInit) => {
      const path = String(input);
      requests.push(path);
      if (init?.method && init.method !== "GET") {
        const body = JSON.parse(String(init.body));
        writes.push({ path, body });
        if (path.endsWith("/contacts")) {
          row = {
            ...row,
            cntct_stts_cd: body.cntct_stts_cd,
            last_cntct_dt: body.cntct_dt,
            contacts: [
              { ...body, user_id: null, created_at: "2026-10-23T12:00:00" },
            ],
          };
        } else if (path.endsWith("/convert")) {
          row = { ...row, dev_id: 91, cntct_stts_cd: "SIGNED" };
          return json({ candidate: row, developer: { dev_id: 91 } });
        } else row = { ...row, ...body };
        return json(row);
      }
      if (path.startsWith("/api/codes/")) {
        const group = path.split("/").at(-1);
        return json(
          group === "JOB"
            ? [
                {
                  code: "BE",
                  name: "백엔드",
                  attr1: null,
                  attr2: null,
                  attr3: null,
                },
              ]
            : group === "SKILL"
              ? [{ code: "JAVA", name: "자바" }]
              : [],
        );
      }
      if (
        path.startsWith("/api/partners/options") ||
        path.startsWith("/api/sales-parts/options")
      )
        return json([]);
      if (path.startsWith("/api/candidates/shortages"))
        return json({ items: [], page: 0, size: 50, total: 0 });
      if (path === "/api/candidates/73") return json(row);
      if (path.startsWith("/api/candidates?")) {
        if (readError)
          return json(
            {
              success: false,
              meta: {},
              code: "SERVER_ERROR",
              message: "조회 실패",
              requestId: "candidate-read",
            },
            500,
          );
        return json({
          items: [row],
          page: 0,
          size: 50,
          total: 1,
          kpi: {
            candidate_count: 1,
            interview_count: 0,
            available_by_next_month_count: 1,
            uncontacted_over_30_days_count: 0,
          },
        });
      }
      throw new Error(`Unexpected request: ${path}`);
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = (state?: unknown) =>
  render(
    <MemoryRouter initialEntries={[{ pathname: "/UI-33", state }]}>
      <UI33Screen />
    </MemoryRouter>,
  );

describe("external candidate workspace", () => {
  it("preserves incoming shortage filters and renders API-backed KPIs", async () => {
    mount({
      workspace: "candidates",
      candidateFilters: { job: "BE", shortage_month: "2027-03" },
    });
    expect(await screen.findByTestId("candidate-row-73")).toBeVisible();
    expect(screen.getByTestId("candidate-kpi")).toHaveTextContent(
      "후보 수 1명",
    );
    expect(
      requests.some(
        (p) => p.includes("job=BE") && p.includes("shortage_month=2027-03"),
      ),
    ).toBe(true);
    expect(writes).toHaveLength(0);
  });
  it("requires an outsourced vendor before saving and strips unrelated detail fields", async () => {
    mount();
    await screen.findByTestId("candidate-row-73");
    fireEvent.click(screen.getByTestId("candidate-create"));
    await screen.findByRole("option", { name: "백엔드" });
    fireEvent.change(screen.getByTestId("candidate-type"), {
      target: { value: "OS" },
    });
    expect(screen.getByTestId("candidate-vendor")).toBeRequired();
    fireEvent.change(screen.getByTestId("candidate-type"), {
      target: { value: "FL" },
    });
    fireEvent.change(screen.getByTestId("candidate-name"), {
      target: { value: "새 후보" },
    });
    fireEvent.change(screen.getByTestId("candidate-job"), {
      target: { value: "BE" },
    });
    fireEvent.change(screen.getByTestId("candidate-available"), {
      target: { value: "2027-01-01" },
    });
    fireEvent.submit(screen.getByTestId("candidate-editor"));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0].path).toBe("/api/candidates");
    expect(writes[0].body).toMatchObject({
      cand_nm: "새 후보",
      vndr_ptnr_id: null,
      job_cd: "BE",
    });
    expect(writes[0].body).not.toHaveProperty("cand_id");
    expect(writes[0].body).not.toHaveProperty("contacts");
  });
  it("adds contact using the selected candidate ID and re-reads list and detail", async () => {
    mount();
    await screen.findByTestId("candidate-row-73");
    fireEvent.click(screen.getByTestId("candidate-open-73"));
    await screen.findByTestId("candidate-contact");
    fireEvent.click(screen.getByTestId("candidate-contact"));
    fireEvent.change(screen.getByTestId("candidate-contact-date"), {
      target: { value: "2026-10-10" },
    });
    fireEvent.change(screen.getByTestId("candidate-contact-memo"), {
      target: { value: "연락 결과" },
    });
    fireEvent.submit(screen.getByTestId("candidate-contact-form"));
    await waitFor(() =>
      expect(writes[0]).toMatchObject({
        path: "/api/candidates/73/contacts",
        body: { cntct_dt: "2026-10-10", memo: "연락 결과" },
      }),
    );
    await waitFor(() =>
      expect(
        screen.queryByTestId("candidate-contact-form"),
      ).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId("candidate-tab-contacts"));
    expect(await screen.findByText("연락 결과")).toBeVisible();
  });
  it("disables editing contact-derived state and confirms abandonment without saving", async () => {
    row.contacts = [
      {
        cntct_dt: "2026-10-20",
        cntct_stts_cd: "INTV",
        memo: "면접",
        user_id: null,
        created_at: "2026-10-20T12:00:00",
      },
    ];
    row.cntct_stts_cd = "INTV";
    mount();
    await screen.findByTestId("candidate-row-73");
    fireEvent.click(screen.getByTestId("candidate-open-73"));
    fireEvent.click(await screen.findByTestId("candidate-edit"));
    expect(screen.getByTestId("candidate-status")).toBeDisabled();
    fireEvent.change(screen.getByTestId("candidate-name"), {
      target: { value: "변경 초안" },
    });
    fireEvent.click(screen.getByTestId("candidate-cancel"));
    const dialog = screen.getByRole("dialog", { name: "저장하지 않은 내용" });
    expect(dialog).toHaveTextContent("입력한 내용을 버릴까요?");
    fireEvent.click(within(dialog).getByRole("button", { name: "취소" }));
    expect(screen.getByTestId("candidate-name")).toHaveValue("변경 초안");
    expect(writes).toHaveLength(0);
  });
  it("shows request failures rather than stale data and retries", async () => {
    readError = true;
    mount();
    expect(await screen.findByText("조회 실패")).toBeVisible();
    expect(screen.queryByTestId("candidate-row-73")).not.toBeInTheDocument();
    readError = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByTestId("candidate-row-73")).toBeVisible();
  });
});
