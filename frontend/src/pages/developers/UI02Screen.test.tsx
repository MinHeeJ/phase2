import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI02Screen from "./UI02Screen";
import { DeveloperDrawer } from "./DeveloperDrawer";
import { developerFixture, developerServer } from "./DeveloperTestSupport";
import type { DeveloperDetail } from "./client";

let server: ReturnType<typeof developerServer>;
beforeEach(() => {
  localStorage.clear();
  vi.stubGlobal("innerWidth", 1280);
  server = developerServer();
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const show = () =>
  render(
    <MemoryRouter initialEntries={["/UI-02"]}>
      <UI02Screen />
    </MemoryRouter>,
  );
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const drawer = (row: DeveloperDetail = developerFixture, close = vi.fn()) => {
  server.state.rows = [row];
  return render(
    <DeveloperDrawer
      id={row.dev_id}
      onClose={close}
      onSaved={async () => {}}
    />,
  );
};

describe("developer pool", () => {
  it("renders the real eleven-column pool and zero-based API defaults", async () => {
    show();
    const row = await screen.findByTestId("developer-row-1");
    expect(screen.getAllByRole("columnheader")).toHaveLength(11);
    expect(within(row).getByText("프로젝트 A 외 1건").tagName).toBe("SPAN");
    expect(within(row).getByText("1.20 MM")).toBeVisible();
    expect(within(row).getByText("1.0 초과")).toBeVisible();
    expect(screen.getByTestId("developer-template")).toHaveAttribute(
      "href",
      "/api/uploads/templates/B.8",
    );
    expect(
      server.fetcher.mock.calls.some((c) =>
        c[0].includes("page=0&size=50&sort=dev_nm%2Casc"),
      ),
    ).toBe(true);
  });
  it("submits AND filters and resets scope and search", async () => {
    show();
    await screen.findByTestId("developer-row-1");
    await screen.findByRole("option", { name: "외주" });
    change("조회 직원구분", "OS");
    change("조회 직무", "BE");
    change("조회 상태", "퇴사");
    change("조회 범위", "N");
    change("이름·관리번호", "K-0003");
    fireEvent.click(screen.getByTestId("developer-query"));
    await waitFor(() =>
      expect(
        server.fetcher.mock.calls.some(
          (c) =>
            c[0].includes("empType=OS&job=BE&status=") &&
            c[0].includes("q=K-0003&use_yn=N"),
        ),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId("developer-reset"));
    expect(screen.getByLabelText("조회 범위")).toHaveValue("Y");
    expect(screen.getByLabelText("이름·관리번호")).toHaveValue("");
  });
  it("shows exact empty state and reset action", async () => {
    server.state.rows = [];
    show();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeVisible();
    fireEvent.click(screen.getByText("조건 초기화"));
    expect(server.writes()).toHaveLength(0);
  });
  it("server sorting reverses rows and page sizes adapt to zero-based API", async () => {
    server.state.rows.push({
      ...developerFixture,
      dev_id: 2,
      mng_no: "K-0002",
      dev_nm: "나 개발자",
    });
    show();
    await screen.findByTestId("developer-row-2");
    fireEvent.click(screen.getByTestId("developer-sort-dev-nm"));
    await waitFor(() =>
      expect(screen.getAllByTestId(/^developer-row-/)[0]).toHaveAttribute(
        "data-testid",
        "developer-row-2",
      ),
    );
    change("목록 건수", "20");
    await waitFor(() =>
      expect(
        server.fetcher.mock.calls.some((c) => c[0].includes("size=20")),
      ).toBe(true),
    );
  });
  it("row opens right drawer while project stays unlinked", async () => {
    show();
    fireEvent.click(await screen.findByTestId("developer-row-1"));
    expect(
      await screen.findByRole("dialog", { name: "개발자 상세" }),
    ).toBeVisible();
    expect(screen.getByTestId("developer-drawer").className).toContain(
      "drawer",
    );
    expect(
      screen.queryByRole("link", { name: /프로젝트 A/ }),
    ).not.toBeInTheDocument();
  });
  it.each([
    ["투입중", "info"],
    ["철수예정", "warning"],
    ["대기", "danger"],
    ["휴직", "locked"],
    ["퇴사", "danger"],
  ])("%s has the reference badge tone", async (status, tone) => {
    server.state.rows = [{ ...developerFixture, status }];
    show();
    const row = await screen.findByTestId("developer-row-1");
    expect(within(row).getByText(status).className).toContain(tone);
    expect(within(row).getByText("정규직").className).toContain("neutral");
  });
  it("read failure exposes retry and keeps search values", async () => {
    server.state.failList = true;
    show();
    await screen.findByText("입력 오류");
    change("이름·관리번호", "보존");
    server.state.failList = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await screen.findByTestId("developer-row-1");
    expect(screen.getByLabelText("이름·관리번호")).toHaveValue("보존");
    expect(server.writes()).toHaveLength(0);
  });
  it("disabled retirees are discoverable and enable retains retirement", async () => {
    server.state.rows = [
      {
        ...developerFixture,
        use_yn: "N",
        status: "퇴사",
        rsgn_dt: "2026-10-01",
      },
    ];
    show();
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    change("조회 범위", "N");
    fireEvent.click(screen.getByTestId("developer-query"));
    fireEvent.click(await screen.findByTestId("developer-row-1"));
    await screen.findByText("사용을 중지한 사람입니다.");
    expect(
      screen.getByText(
        "퇴사한 사람입니다(퇴사일 2026-10-01). 새 투입에 고를 수 없습니다.",
      ),
    ).toBeVisible();
    fireEvent.click(screen.getByTestId("developer-more"));
    fireEvent.click(screen.getByText("다시 사용"));
    await waitFor(() => expect(server.state.rows[0].use_yn).toBe("Y"));
    expect(server.state.rows[0].rsgn_dt).toBe("2026-10-01");
  });
  it("mobile keeps list and detail but removes every mutation and template control", async () => {
    vi.stubGlobal("innerWidth", 767);
    show();
    fireEvent.click(await screen.findByTestId("developer-row-1"));
    await screen.findByRole("dialog");
    for (const text of [
      "+ 등록",
      "엑셀 업로드",
      "급여 업로드",
      "양식 내려받기",
      "수정",
      "다시 사용",
    ])
      expect(screen.queryByText(text)).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("developer-select-all"),
    ).not.toBeInTheDocument();
    expect(server.writes()).toHaveLength(0);
  });
  it("desktop boundary exposes mutation controls", async () => {
    vi.stubGlobal("innerWidth", 768);
    show();
    await screen.findByTestId("developer-row-1");
    expect(screen.getByText("+ 등록")).toBeVisible();
  });
  it("external panels render only selected tab and receive readonly boundary", async () => {
    const renderPanel = vi.fn(() => <div>투입 응답 영역</div>);
    render(
      <DeveloperDrawer
        id={1}
        onClose={() => {}}
        onSaved={async () => {}}
        tabs={[{ key: "assignments", label: "투입 이력", render: renderPanel }]}
      />,
    );
    await screen.findByText("K-0001");
    expect(renderPanel).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("tab", { name: "투입 이력" }));
    expect(screen.getByText("투입 응답 영역")).toBeVisible();
    expect(screen.queryByText("최근 월 급여 –")).not.toBeInTheDocument();
  });
});

describe("developer detail edits", () => {
  it("shows skills, all registration fields, and latest salary without masking", async () => {
    drawer({
      ...developerFixture,
      skill: ["JAVA"],
      latest_salary: { month: "2026-10", slry_amt: 4000000 },
    });
    expect(
      await screen.findByText("최근 월 급여 2026-10 4,000,000 원"),
    ).toBeVisible();
    expect(screen.getByText("Java")).toBeVisible();
    for (const text of [
      "소속",
      "직위",
      "업무 구분",
      "영업 파트",
      "입사일",
      "연락처",
      "이메일",
      "비고",
      "월 단가",
    ])
      expect(screen.getByText(text)).toBeVisible();
  });
  it("salary absent displays dash", async () => {
    drawer();
    expect(await screen.findByText("최근 월 급여 –")).toBeVisible();
  });
  it("edit stays in same drawer with readonly management number and no resignation control", async () => {
    drawer();
    fireEvent.click(await screen.findByTestId("developer-edit"));
    await screen.findByLabelText("이름");
    expect(screen.getByText("수정 중 · K-0001")).toBeVisible();
    expect(screen.getByLabelText("관리번호")).toHaveAttribute("readonly");
    expect(
      screen.getByText("다른 자료와 연결되어 있어 바꿀 수 없습니다."),
    ).toBeVisible();
    expect(screen.queryByLabelText("퇴사일")).not.toBeInTheDocument();
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
  });
  it("grade save refreshes detail and returns to read mode with success", async () => {
    const done = vi.fn().mockResolvedValue(undefined);
    render(<DeveloperDrawer id={1} onClose={() => {}} onSaved={done} />);
    fireEvent.click(await screen.findByTestId("developer-edit"));
    await screen.findByRole("option", { name: "고급" });
    change("등급", "SR");
    fireEvent.click(screen.getByTestId("developer-save"));
    await waitFor(() => expect(done).toHaveBeenCalledWith("저장했습니다."));
    expect(await screen.findByText("고급")).toBeVisible();
    expect(screen.queryByLabelText("이름")).not.toBeInTheDocument();
    const body = JSON.parse(server.writes()[0][1]!.body as string);
    expect(body).not.toHaveProperty("mng_no");
    expect(body).not.toHaveProperty("rsgn_dt");
  });
  it("unchanged save is zero requests and returns viewing message", async () => {
    const done = vi.fn().mockResolvedValue(undefined);
    render(<DeveloperDrawer id={1} onClose={() => {}} onSaved={done} />);
    fireEvent.click(await screen.findByTestId("developer-edit"));
    await screen.findByRole("option", { name: "고급" });
    fireEvent.click(screen.getByTestId("developer-save"));
    await waitFor(() =>
      expect(done).toHaveBeenCalledWith("바뀐 내용이 없습니다."),
    );
    expect(server.writes()).toHaveLength(0);
  });
  it("dirty close uses exact prompt and continue keeps input", async () => {
    const close = vi.fn();
    drawer(developerFixture, close);
    fireEvent.click(await screen.findByTestId("developer-edit"));
    change("이름", "수정 보존");
    fireEvent.click(screen.getByTestId("developer-drawer-close"));
    expect(screen.getByText("저장하지 않은 내용이 있습니다")).toBeVisible();
    expect(
      screen.getByText("창을 닫으면 고친 내용이 사라집니다."),
    ).toBeVisible();
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByLabelText("이름")).toHaveValue("수정 보존");
    expect(close).not.toHaveBeenCalled();
  });
  it("OS missing affiliation preserves draft and focuses first error", async () => {
    drawer();
    fireEvent.click(await screen.findByTestId("developer-edit"));
    await screen.findByRole("option", { name: "외주" });
    change("직원구분", "OS");
    fireEvent.click(screen.getByTestId("developer-save"));
    expect(
      await screen.findByText("외주는 협력사를 골라 주세요."),
    ).toBeVisible();
    expect(screen.getByLabelText("소속")).toHaveFocus();
    expect(screen.getByLabelText("이름")).toHaveValue("가 개발자");
    expect(server.writes()).toHaveLength(0);
  });
  it("cancel restores original detail without saving", async () => {
    drawer();
    fireEvent.click(await screen.findByTestId("developer-edit"));
    change("이름", "폐기");
    fireEvent.click(screen.getByTestId("developer-cancel"));
    expect(screen.queryByLabelText("이름")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "가 개발자" })).toBeVisible();
    expect(server.writes()).toHaveLength(0);
  });
});
