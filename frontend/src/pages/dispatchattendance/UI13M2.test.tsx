import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { UI13M2, type UI13M2Props } from "./UI13M2";
import type { DispatchAttendanceResult } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let data: DispatchAttendanceResult;
let fetcher: ReturnType<typeof vi.fn>;
let writeFailure: Response | null;
let readFailure: boolean;
let closed: boolean;
let method: string;
const changed = vi.fn().mockResolvedValue(undefined);
const month = new Intl.DateTimeFormat("sv", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "2-digit",
}).format(new Date());
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  changed.mockClear();
  writeFailure = null;
  readFailure = false;
  closed = false;
  method = "DAY";
  data = {
    month,
    project_id: 17,
    warnings: [],
    items: [
      {
        asgn_id: 21,
        dev_id: 11,
        work_day_qty: 1,
        rglr_tm_qty: 8,
        ovtm_tm_qty: 0,
        mth_work_day_qty: 8,
        calculated_mm: 0.13,
        assignment_mm: 0.75,
        price_available: false,
        warnings: ["단가 미입력: 근태 저장은 가능하나 청구 생성은 차단됩니다."],
      },
    ],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/projects/options")
      return response([
        { id: 17, no: "P-0017", name: "파견 프로젝트", type: "DP" },
      ]);
    if (url === "/api/projects/17")
      return response({
        prj_id: 17,
        prj_nm: "파견 프로젝트",
        stlm_mthd_cd: method,
      });
    if (url === "/api/projects/17/assignments")
      return response([{ asgn_id: 21, name: "담당 인력" }]);
    if (url.startsWith("/api/month-close?"))
      return response([
        { month: data.month, status: closed ? "CLOSED" : "OPEN" },
      ]);
    if (url.startsWith("/api/dispatch-attendance?")) {
      if (init?.method === "PUT") {
        if (writeFailure) return writeFailure.clone();
        const input = JSON.parse(init.body as string).items[0];
        data = {
          ...data,
          items: [{ ...data.items[0], ...input, calculated_mm: 0 }],
        };
        return response(data);
      }
      if (readFailure) throw new Error("offline");
      return response(data);
    }
    throw new Error(`Unexpected ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = (props: Partial<UI13M2Props> = {}) =>
  render(
    <UI13M2
      month={month}
      projectId={17}
      onCancel={() => {}}
      onSaved={changed}
      {...props}
    />,
  );
const loaded = () => screen.findByTestId("attendance-row-21");
const input = (name = "근무일수") =>
  screen.getByRole("textbox", { name: `21 ${name}` });
const edit = (value: string, name = "근무일수") =>
  fireEvent.change(input(name), { target: { value } });
const save = () =>
  fireEvent.click(screen.getByRole("button", { name: "저장" }));
const writes = () => fetcher.mock.calls.filter((c) => c[1]?.method === "PUT");
const failure = (code: string, field: string | null, status = 409) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code,
      field,
      message: "근태 입력을 확인하세요.",
      requestId: "attendance-request",
    }),
    { status },
  );

describe("파견 근태 수동 입력", () => {
  it("renders contract columns, source MM comparison, names and price warning", async () => {
    panel();
    await loaded();
    for (const name of [
      "인력",
      "근무일수",
      "정규 근무시간",
      "초과 근무시간",
      "해당 월 근무일수",
      "산출 MM",
      "⑦ 투입 MM",
      "단가·근태 경고",
    ])
      expect(screen.getByRole("columnheader", { name })).toBeVisible();
    expect(screen.getByText("담당 인력")).toBeVisible();
    expect(screen.getByText("0.13")).toBeVisible();
    expect(screen.getByText("0.75")).toBeVisible();
    expect(screen.getByText("단가 미입력")).toBeVisible();
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    expect(writes()).toHaveLength(0);
  });
  it("anonymous zero-day save sends only editable quantities and refreshes", async () => {
    panel();
    await loaded();
    edit("0");
    save();
    await screen.findByText("근태를 저장했습니다.");
    expect(JSON.parse(writes()[0][1]!.body as string)).toEqual({
      items: [
        {
          asgn_id: 21,
          dev_id: 11,
          work_day_qty: 0,
          rglr_tm_qty: 8,
          ovtm_tm_qty: 0,
          mth_work_day_qty: 8,
        },
      ],
    });
    expect(writes()[0][0]).toBe(
      `/api/dispatch-attendance?month=${month}&projectId=17`,
    );
    expect(input()).toHaveValue("0");
    expect(changed).toHaveBeenCalledTimes(1);
  });
  it.each(["0", "-1", "32", "1.11", ""])(
    "rejects denominator %s before API",
    async (value) => {
      panel();
      await loaded();
      edit(value, "해당 월 근무일수");
      save();
      expect(input("해당 월 근무일수")).toHaveAttribute("aria-invalid", "true");
      expect(writes()).toHaveLength(0);
    },
  );
  it("requires DAY work days", async () => {
    panel();
    await loaded();
    edit("");
    save();
    expect(input()).toHaveAttribute("aria-invalid", "true");
    expect(writes()).toHaveLength(0);
  });
  it("requires HOUR regular time", async () => {
    method = "HOUR";
    panel();
    await loaded();
    edit("", "정규 근무시간");
    save();
    expect(input("정규 근무시간")).toHaveAttribute("aria-invalid", "true");
    expect(writes()).toHaveLength(0);
  });
  it("allows optional HOUR days absent and zero time", async () => {
    method = "HOUR";
    panel();
    await loaded();
    edit("");
    edit("0", "정규 근무시간");
    save();
    await screen.findByText("근태를 저장했습니다.");
    expect(
      JSON.parse(writes()[0][1]!.body as string).items[0].work_day_qty,
    ).toBeNull();
  });
  it("server field error stays inline and retains input", async () => {
    writeFailure = failure("VALIDATION_ERROR", "items[0].work_day_qty", 400);
    panel();
    await loaded();
    edit("2");
    save();
    await waitFor(() =>
      expect(input()).toHaveAttribute("aria-invalid", "true"),
    );
    expect(input()).toHaveValue("2");
    expect(screen.getByText("문의 번호 attendance-request")).toBeVisible();
    expect(changed).not.toHaveBeenCalled();
  });
  it.each(["CLOSED_MONTH", "ATTENDANCE_BILLED"])(
    "keeps draft after %s conflict",
    async (code) => {
      writeFailure = failure(code, null);
      panel();
      await loaded();
      edit("2");
      save();
      await screen.findByText("근태 입력을 확인하세요.");
      expect(input()).toHaveValue("2");
      expect(writes()).toHaveLength(1);
    },
  );
  it("closed month hides saving and uses readonly inputs", async () => {
    closed = true;
    panel();
    await loaded();
    expect(input()).toHaveAttribute("readonly");
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("past year hides saving", async () => {
    data.month = "2000-10";
    panel({ month: "2000-10" });
    await loaded();
    expect(input()).toHaveAttribute("readonly");
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("mobile hides all attendance writes and marks upload integration readonly", async () => {
    window.innerWidth = 767;
    const upload = vi.fn(() => null);
    panel({ renderUpload: upload });
    await loaded();
    expect(input()).toHaveAttribute("readonly");
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
    expect(upload).toHaveBeenLastCalledWith(
      expect.objectContaining({ readOnly: true }),
    );
  });
  it.each([
    "확정 청구 연결 근태입니다. 수정할 수 없습니다.",
    "동일 인력의 배정이 중복됩니다. 배정을 명시적으로 정리해 주세요.",
  ])("locks row for %s", async (warning) => {
    data.items[0].warnings = [warning];
    panel();
    await loaded();
    expect(input()).toHaveAttribute("readonly");
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("empty response has no save action", async () => {
    data.items = [];
    panel();
    await screen.findByText("조건에 맞는 결과가 없습니다");
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("failed request can retry without a write", async () => {
    readFailure = true;
    panel();
    await screen.findByRole("alert");
    readFailure = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await loaded();
    expect(writes()).toHaveLength(0);
  });
  it("loading state never exposes stale inputs", () => {
    fetcher.mockImplementation(() => new Promise(() => {}));
    panel();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("scope is required before querying and cancel calls parent", async () => {
    const cancel = vi.fn();
    panel({ projectId: undefined, onCancel: cancel });
    await screen.findByRole("option", { name: /파견 프로젝트/ });
    fireEvent.click(screen.getByRole("button", { name: "조회" }));
    expect(screen.getByText("월과 프로젝트를 선택하세요.")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "닫기" }));
    expect(cancel).toHaveBeenCalledOnce();
  });
});
