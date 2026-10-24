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
import UI32Screen from "./UI32Screen";
import { DeveloperForm } from "./DeveloperForm";
import { developerFixture, developerServer } from "./DeveloperTestSupport";

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
const change = (label: string, value: string) =>
  fireEvent.change(
    within(screen.getByTestId("developer-form")).getByLabelText(label),
    { target: { value } },
  );
function form() {
  return render(
    <DeveloperForm
      onCancel={() => {}}
      onSaved={() => {}}
      onDirtyChange={() => {}}
      reload={async () => {}}
    />,
  );
}
async function ready() {
  await waitFor(() =>
    expect(screen.getByTestId("developer-save")).toBeEnabled(),
  );
}
async function valid() {
  await ready();
  change("이름", "새 개발자");
  change("직원구분", "FT");
  change("주 직무", "BE");
}

describe("developer registration", () => {
  it("provides all required limits, direct duty and active headquarters, money only monthly rate", async () => {
    form();
    await ready();
    expect(screen.getByLabelText("이름")).toHaveAttribute("maxlength", "50");
    expect(screen.getByLabelText("연락처")).toHaveAttribute("maxlength", "30");
    expect(screen.getByLabelText("이메일")).toHaveAttribute("maxlength", "100");
    expect(screen.getByLabelText("비고")).toHaveAttribute("maxlength", "500");
    expect(screen.getByLabelText("업무 구분")).toHaveValue("DIRECT");
    expect(screen.getByRole("option", { name: "본사" })).toBeInTheDocument();
    expect(screen.getByLabelText("월 단가")).toBeVisible();
    expect(screen.queryByLabelText("월 급여")).not.toBeInTheDocument();
  });
  it("name prevents the fifty-first character", async () => {
    form();
    await ready();
    change("이름", "가".repeat(50));
    change("이름", "가".repeat(51));
    expect(screen.getByLabelText("이름")).toHaveValue("가".repeat(50));
  });
  it.each([
    ["이름", "이름을 입력해 주세요."],
    ["직원구분", "직원구분을 골라 주세요."],
    ["주 직무", "주 직무를 골라 주세요."],
  ])("requires %s with field focus", async (label, message) => {
    form();
    await ready();
    if (label !== "이름") change("이름", "등록");
    if (label === "주 직무") change("직원구분", "FT");
    fireEvent.click(screen.getByTestId("developer-save"));
    expect(await screen.findByText(message)).toBeVisible();
    expect(screen.getByLabelText(label)).toHaveFocus();
    expect(server.writes()).toHaveLength(0);
  });
  it("employee transitions clear affiliation and return to readonly own company", async () => {
    form();
    await ready();
    change("직원구분", "OS");
    expect(screen.getByLabelText("소속")).toHaveValue("");
    change("소속", "2");
    change("직원구분", "FT");
    expect(screen.getByLabelText("소속")).toHaveValue("자사");
    expect(screen.getByLabelText("소속")).toHaveAttribute("readonly");
    change("직원구분", "OS");
    expect(screen.getByLabelText("소속")).toHaveValue("");
  });
  it("invalid email keeps input and focuses email", async () => {
    form();
    await valid();
    change("이메일", "bad");
    fireEvent.click(screen.getByTestId("developer-save"));
    expect(
      await screen.findByText("이메일 형식이 맞지 않습니다."),
    ).toBeVisible();
    expect(screen.getByLabelText("이메일")).toHaveFocus();
    expect(screen.getByLabelText("이름")).toHaveValue("새 개발자");
  });
  it("invalid management number focuses identity", async () => {
    form();
    await valid();
    change("관리번호", "lowercase");
    fireEvent.click(screen.getByTestId("developer-save"));
    expect(await screen.findByText(/관리번호는 영문 대문자/)).toBeVisible();
    expect(screen.getByLabelText("관리번호")).toHaveFocus();
  });
  it("save preserves server field error and complete draft", async () => {
    server.state.failWrite = true;
    form();
    await valid();
    fireEvent.click(screen.getByTestId("developer-save"));
    await waitFor(() => expect(screen.getByLabelText("이름")).toHaveFocus());
    expect(screen.getByLabelText("이름")).toHaveValue("새 개발자");
    expect(screen.getAllByText("입력 오류").length).toBeGreaterThan(0);
  });
  it("selected jobs and skills use chip labels and exclude main job", async () => {
    form();
    await valid();
    expect(
      screen.queryByTestId("developer-sub-job-be"),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("developer-sub-job-fe"));
    fireEvent.click(screen.getByTestId("developer-skill-java"));
    fireEvent.click(screen.getByTestId("developer-save"));
    await waitFor(() => expect(server.writes()).toHaveLength(1));
    const body = JSON.parse(server.writes()[0][1]!.body as string);
    expect(body.sub_job).toEqual(["FE"]);
    expect(body.skill).toEqual(["JAVA"]);
    expect(body).not.toHaveProperty("slry_amt");
  });
  it("same-tab child registration saves then pins actual created id without altering server sorting", async () => {
    render(
      <MemoryRouter initialEntries={["/UI-02"]}>
        <UI02Screen />
      </MemoryRouter>,
    );
    await screen.findByTestId("developer-row-1");
    fireEvent.click(screen.getByTestId("developer-create"));
    await valid();
    fireEvent.click(screen.getByTestId("developer-save"));
    const row = await screen.findByTestId("developer-row-3");
    expect(row.className).toContain("highlight");
    expect(screen.getAllByTestId(/^developer-row-/)[0]).toBe(row);
    expect(await screen.findByText("저장했습니다.")).toBeVisible();
  });
  it("direct child reload displays registration", async () => {
    render(
      <MemoryRouter initialEntries={["/UI-32"]}>
        <UI02Screen />
      </MemoryRouter>,
    );
    expect(await screen.findByTestId("developer-registration")).toBeVisible();
    await ready();
  });
  it("registration cancel asks before discarding and keeps entered draft on continue", async () => {
    render(
      <MemoryRouter initialEntries={["/UI-32"]}>
        <UI32Screen />
      </MemoryRouter>,
    );
    await ready();
    change("이름", "보존");
    fireEvent.click(screen.getByTestId("developer-cancel"));
    expect(screen.getByText("저장하지 않은 내용이 있습니다")).toBeVisible();
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByLabelText("이름")).toHaveValue("보존");
  });
  it("mobile direct registration redirects to the list and cannot mutate", async () => {
    vi.stubGlobal("innerWidth", 767);
    render(
      <MemoryRouter initialEntries={["/UI-32"]}>
        <UI02Screen />
      </MemoryRouter>,
    );
    expect(await screen.findByTestId("developer-row-1")).toBeVisible();
    expect(
      screen.queryByTestId("developer-registration"),
    ).not.toBeInTheDocument();
    expect(screen.queryByLabelText("이름")).not.toBeInTheDocument();
    expect(server.writes()).toHaveLength(0);
  });
  it("inactive retained code keeps its label in edit mode", async () => {
    render(
      <DeveloperForm
        initial={{
          ...developerFixture,
          grd_cd: "OLD",
          grade_name: "예전 등급",
          code_names: { "GRADE:OLD": "예전 등급" },
        }}
        onCancel={() => {}}
        onSaved={() => {}}
        onDirtyChange={() => {}}
        reload={async () => {}}
      />,
    );
    await ready();
    expect(screen.getByRole("option", { name: "예전 등급" })).toHaveValue(
      "OLD",
    );
    expect(screen.getByLabelText("등급")).toHaveValue("OLD");
  });
  it("failed options block saving and retry without losing input", async () => {
    server.state.failCodes = true;
    form();
    await screen.findAllByText("입력 오류");
    change("이름", "입력 유지");
    expect(screen.getByTestId("developer-save")).toBeDisabled();
    server.state.failCodes = false;
    fireEvent.click(screen.getAllByText("다시 시도")[0]);
    expect(screen.getByLabelText("이름")).toHaveValue("입력 유지");
  });
});
