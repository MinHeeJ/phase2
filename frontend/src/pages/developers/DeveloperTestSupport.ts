import { vi } from "vitest";
import { emptyDeveloper, type DeveloperDetail } from "./client";

export const developerFixture: DeveloperDetail = {
  ...emptyDeveloper,
  dev_id: 1,
  mng_no: "K-0001",
  dev_nm: "가 개발자",
  emp_se_cd: "FT",
  main_job_cd: "BE",
  affiliation_name: "자사",
  emp_type_name: "정규직",
  grade_name: null,
  main_job_name: "백엔드",
  status: "대기",
  current_project_name: "프로젝트 A",
  current_assignment_count: 2,
  current_month_mm: 1.2,
  over_one_mm: true,
  latest_assignment_end_dt: "2026-12-31",
  use_yn: "Y",
  rsgn_dt: null,
  latest_salary: null,
  updated_at: null,
  code_names: {
    "JOB:BE": "백엔드",
    "JOB:FE": "프론트엔드",
    "SKILL:JAVA": "Java",
    "WORK_TYPE:DIRECT": "직접",
  },
};
export const codeFixtures: Record<string, { code: string; name: string }[]> = {
  EMP_TYPE: [
    { code: "FT", name: "정규직" },
    { code: "OS", name: "외주" },
  ],
  POSITION: [{ code: "TL", name: "팀장" }],
  WORK_TYPE: [
    { code: "DIRECT", name: "직접" },
    { code: "INDIRECT", name: "간접" },
  ],
  GRADE: [
    { code: "JR", name: "초급" },
    { code: "SR", name: "고급" },
  ],
  JOB: [
    { code: "BE", name: "백엔드" },
    { code: "FE", name: "프론트엔드" },
    { code: "UX", name: "디자인" },
  ],
  SKILL: [
    { code: "JAVA", name: "Java" },
    { code: "REACT", name: "React" },
  ],
};
export const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
export const failure = (field: string | null = null, status = 400) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      field,
      message: "입력 오류",
      requestId: "developer-test",
      code: "INVALID_INPUT",
    }),
    { status },
  );
export function developerServer() {
  const state = {
    rows: [{ ...developerFixture }],
    failList: false,
    failWrite: false,
    failCodes: false,
    failDetail: false,
  };
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/codes/"))
      return state.failCodes
        ? failure()
        : response(codeFixtures[url.split("/").at(-1)!] ?? []);
    if (url === "/api/partners/options?kind=VEND")
      return response([{ id: "2", no: "C-002", name: "협력사" }]);
    if (url === "/api/sales-parts/options")
      return response([{ id: "1", name: "본사", hq: true }]);
    if (url.endsWith("/enable")) {
      const id = Number(url.split("/").at(-2));
      state.rows = state.rows.map((r) =>
        r.dev_id === id ? { ...r, use_yn: "Y" } : r,
      );
      return response(state.rows.find((r) => r.dev_id === id));
    }
    if (init?.method === "POST" && url === "/api/developers") {
      if (state.failWrite) return failure("dev_nm");
      const row: DeveloperDetail = {
        ...developerFixture,
        ...JSON.parse(init.body as string),
        dev_id: 3,
        mng_no: "K-0003",
      };
      state.rows.push(row);
      return response(row);
    }
    if (init?.method === "PUT") {
      if (state.failWrite) return failure("vndr_ptnr_id");
      const id = Number(url.split("/").at(-1)),
        body = JSON.parse(init.body as string);
      state.rows = state.rows.map((r) =>
        r.dev_id === id
          ? {
              ...r,
              ...body,
              grade_name: body.grd_cd === "SR" ? "고급" : r.grade_name,
            }
          : r,
      );
      return response({
        developer: state.rows.find((r) => r.dev_id === id),
        changed: true,
      });
    }
    if (url.startsWith("/api/developers?")) {
      if (state.failList) return failure(null, 500);
      const q = new URLSearchParams(url.split("?")[1]),
        term = q.get("q");
      let rows = state.rows.filter(
        (r) =>
          r.use_yn === (q.get("use_yn") ?? "Y") &&
          (!term || r.dev_nm.includes(term) || r.mng_no.includes(term)),
      );
      rows = [...rows].sort((a, b) => a.dev_nm.localeCompare(b.dev_nm, "ko"));
      if (q.get("sort") === "dev_nm,desc") rows.reverse();
      const size = Number(q.get("size") ?? 50),
        page = Number(q.get("page") ?? 0);
      return response({
        items: rows.slice(page * size, (page + 1) * size),
        total: rows.length,
        page,
        size,
      });
    }
    if (url.startsWith("/api/developers/"))
      return state.failDetail
        ? failure(null, 500)
        : response(
            state.rows.find((r) => r.dev_id === Number(url.split("/").at(-1))),
          );
    throw new Error(`Unexpected developer test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
  return {
    state,
    fetcher,
    writes: () =>
      fetcher.mock.calls.filter((c) => c[1]?.method && c[1].method !== "GET"),
  };
}
