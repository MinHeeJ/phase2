import { useState } from "react";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  CandidateContactsPanel,
  CandidateConversionAction,
  CandidateContactStatus,
  candidateSeoulToday,
} from "./CandidateContactsPanel";
import { getContactCandidate, type CandidateContactDetail } from "./client";
import {
  developerFixture,
  developerServer,
  response,
  failure,
} from "../developers/DeveloperTestSupport";

let candidate: CandidateContactDetail;
let fetcher: ReturnType<typeof vi.fn>;
let writeError: Response | null;
let failRead: boolean;
const changed = vi.fn(),
  developer = vi.fn();
const codes = [
  { code: "NONE", name: "연락 전" },
  { code: "DONE", name: "연락함" },
  { code: "INTV", name: "면접 예정" },
  { code: "HOLD", name: "보류" },
  { code: "SIGNED", name: "계약 완료" },
];
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  candidate = {
    cand_id: 7,
    cand_no: "CAND-0007",
    cand_nm: "후보",
    cand_se_cd: "FL",
    vndr_ptnr_id: null,
    job_cd: "BE",
    grd_cd: "SR",
    skill: ["JAVA"],
    hope_untprc_amt: 6000000,
    avail_dt: "2026-11-01",
    cntct_stts_cd: "NONE",
    dev_id: null,
    last_cntct_dt: null,
    tel_no: null,
    use_yn: "Y",
    contacts: [],
  };
  writeError = null;
  failRead = false;
  changed.mockReset();
  developer.mockReset();
  const existing = developerServer();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/codes/CONTACT_STATUS") return response(codes);
    if (url === "/api/candidates/7" && !init?.method) {
      if (failRead) throw new Error("offline");
      return response(candidate);
    }
    if (url === "/api/candidates/7/contacts") {
      if (writeError) return writeError.clone();
      const b = JSON.parse(init!.body as string);
      const contact = {
        ...b,
        user_id: null,
        created_at: `2026-10-23T10:00:0${candidate.contacts.length}`,
      };
      const contacts = [...candidate.contacts, contact].sort(
        (a, b) =>
          b.cntct_dt.localeCompare(a.cntct_dt) ||
          b.created_at.localeCompare(a.created_at),
      );
      candidate = {
        ...candidate,
        contacts,
        cntct_stts_cd: candidate.dev_id ? "SIGNED" : contacts[0].cntct_stts_cd,
        last_cntct_dt: contacts[0].cntct_dt,
      };
      return response(candidate);
    }
    if (url === "/api/candidates/7/convert") {
      if (writeError) return writeError.clone();
      candidate = { ...candidate, dev_id: 42, cntct_stts_cd: "SIGNED" };
      return response({
        candidate,
        developer: { ...developerFixture, dev_id: 42 },
      });
    }
    return existing.fetcher(url, init);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const writes = () => fetcher.mock.calls.filter((c) => c[1]?.method === "POST");
function Harness({ conversion = false }: { conversion?: boolean }) {
  const [row, setRow] = useState(candidate);
  const reload = async () => {
    const next = await getContactCandidate(row.cand_id);
    setRow(next);
    changed();
  };
  return conversion ? (
    <CandidateConversionAction
      candidate={row}
      onChanged={reload}
      onDeveloper={developer}
    />
  ) : (
    <CandidateContactsPanel candidate={row} onChanged={reload} />
  );
}
async function openContact() {
  fireEvent.click(screen.getByTestId("candidate-contact-add"));
  await screen.findByRole("option", { name: "면접 예정" });
}
function fillContact(date = "2026-10-20", status = "INTV", memo = "연락 내용") {
  fireEvent.change(screen.getByLabelText("연락일"), {
    target: { value: date },
  });
  fireEvent.change(screen.getByLabelText("연락 상태"), {
    target: { value: status },
  });
  fireEvent.change(screen.getByLabelText("메모"), { target: { value: memo } });
}
const saveContact = () =>
  fireEvent.click(screen.getByTestId("candidate-contact-save"));
async function openConversion() {
  fireEvent.click(screen.getByTestId("candidate-convert"));
  await waitFor(() =>
    expect(screen.getByTestId("developer-save")).toBeEnabled(),
  );
}

describe("candidate contact tab", () => {
  it("renders append-only empty history without edit or delete", async () => {
    render(<Harness />);
    await screen.findByText("연락 기록이 없습니다.");
    expect(
      screen.queryByRole("button", { name: "수정" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "삭제" }),
    ).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("saves selected candidate contact and refreshes latest state and history", async () => {
    render(<Harness />);
    await openContact();
    fillContact();
    saveContact();
    expect(await screen.findByText("연락 기록을 저장했습니다.")).toBeVisible();
    expect(screen.getByTestId("candidate-contact-row-0")).toHaveTextContent(
      "연락 내용",
    );
    expect(changed).toHaveBeenCalledOnce();
    expect(JSON.parse(writes()[0][1]!.body as string)).toEqual({
      cntct_dt: "2026-10-20",
      cntct_stts_cd: "INTV",
      memo: "연락 내용",
    });
    expect(fetcher.mock.calls.some((c) => c[0] === "/api/candidates/7")).toBe(
      true,
    );
  });
  it("backdated contact keeps latest status while retaining original history", async () => {
    candidate = {
      ...candidate,
      cntct_stts_cd: "INTV",
      last_cntct_dt: "2026-10-20",
      contacts: [
        {
          cntct_dt: "2026-10-20",
          cntct_stts_cd: "INTV",
          memo: "기존 내용",
          user_id: null,
          created_at: "2026-10-21T10:00:00",
        },
      ],
    };
    render(<Harness />);
    await openContact();
    fillContact("2026-10-10", "DONE", "과거 내용");
    saveContact();
    await screen.findByText("연락 기록을 저장했습니다.");
    expect(candidate.cntct_stts_cd).toBe("INTV");
    expect(
      within(screen.getByTestId("candidate-contact-row-0")).getByText(
        "기존 내용",
      ),
    ).toBeVisible();
    expect(screen.getByTestId("candidate-contact-row-1")).toHaveTextContent(
      "과거 내용",
    );
  });
  it.each([
    ["2099-01-01", "내용", "연락일"],
    ["2026-10-20", " ", "메모"],
  ])("validates date or memo before POST", async (date, memo, label) => {
    render(<Harness />);
    await openContact();
    fillContact(date, "DONE", memo);
    saveContact();
    expect(screen.getByLabelText(label)).toHaveFocus();
    expect(writes()).toHaveLength(0);
  });
  it("requires status and caps memo at 1000 characters", async () => {
    render(<Harness />);
    await openContact();
    fillContact("2026-10-20", "", "가".repeat(1000));
    saveContact();
    expect(screen.getByLabelText("연락 상태")).toHaveFocus();
    expect(screen.getByLabelText("메모")).toHaveAttribute("maxlength", "1000");
    expect(writes()).toHaveLength(0);
  });
  it("preserves input and exposes singular server field and requestId", async () => {
    writeError = failure("memo");
    render(<Harness />);
    await openContact();
    fillContact();
    saveContact();
    await screen.findByText("문의 번호 developer-test");
    expect(screen.getByLabelText("메모")).toHaveValue("연락 내용");
    expect(screen.getByLabelText("메모")).toHaveFocus();
    expect(changed).not.toHaveBeenCalled();
  });
  it("blocks resubmission after uncertain write until read recovery", async () => {
    writeError = failure(null, 500);
    render(<Harness />);
    await openContact();
    fillContact();
    saveContact();
    await screen.findByText(
      "저장 결과를 다시 조회한 뒤 확인하고 직접 저장해 주세요.",
    );
    expect(screen.getByTestId("candidate-contact-save")).toBeDisabled();
    expect(writes()).toHaveLength(1);
    writeError = null;
    fireEvent.click(screen.getByText("다시 시도"));
    await waitFor(() =>
      expect(screen.getByTestId("candidate-contact-save")).toBeEnabled(),
    );
    expect(writes()).toHaveLength(1);
  });
  it("does not repeat committed contact when readback fails", async () => {
    failRead = true;
    render(<Harness />);
    await openContact();
    fillContact();
    saveContact();
    await screen.findByText(
      "저장 후 조회에 실패했습니다. 현재 상태를 확인하세요.",
    );
    expect(writes()).toHaveLength(1);
    expect(screen.getByTestId("candidate-contact-save")).toBeDisabled();
  });
  it("mobile is read-only and has no contact form", async () => {
    vi.stubGlobal("innerWidth", 767);
    render(<Harness />);
    await screen.findByText("연락 기록이 없습니다.");
    expect(
      screen.queryByTestId("candidate-contact-add"),
    ).not.toBeInTheDocument();
  });
  it("uses separate established badge tones for all contact states", () => {
    const { container } = render(
      <>
        {codes.map((c) => (
          <CandidateContactStatus key={c.code} status={c.code} label={c.name} />
        ))}
      </>,
    );
    for (const [i, tone] of [
      "neutral",
      "info",
      "warning",
      "locked",
      "success",
    ].entries())
      expect(container.children[i].className).toContain(tone);
  });
  it("derives Seoul date across UTC midnight", () => {
    expect(candidateSeoulToday(new Date("2026-10-22T15:01:00Z"))).toBe(
      "2026-10-23",
    );
  });
});
describe("candidate developer conversion", () => {
  it("reuses developer fields and locks candidate-owned identity, affiliation, job, grade and skills", async () => {
    render(<Harness conversion />);
    await openConversion();
    expect(screen.getByLabelText("이름")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("직원구분")).toBeDisabled();
    expect(screen.getByLabelText("주 직무")).toBeDisabled();
    expect(screen.getByLabelText("등급")).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: "Java" })).toBeDisabled();
    expect(screen.getByLabelText("영업 파트")).toBeEnabled();
    expect(screen.getByLabelText("입사일")).toBeEnabled();
    expect(screen.queryByLabelText("월 급여")).not.toBeInTheDocument();
  });
  it("posts conversion only, rereads candidate and enables linked developer navigation", async () => {
    render(<Harness conversion />);
    await openConversion();
    fireEvent.click(screen.getByTestId("developer-save"));
    fireEvent.click(await screen.findByTestId("candidate-linked-developer"));
    expect(developer).toHaveBeenCalledWith(42);
    expect(writes()).toHaveLength(1);
    expect(writes()[0][0]).toBe("/api/candidates/7/convert");
    expect(JSON.parse(writes()[0][1]!.body as string).developer).toMatchObject({
      dev_nm: "후보",
      emp_se_cd: "FL",
      main_job_cd: "BE",
      grd_cd: "SR",
      skill: ["JAVA"],
    });
    expect(screen.queryByTestId("candidate-convert")).not.toBeInTheDocument();
  });
  it("existing conversion has no duplicate registration action", () => {
    candidate.dev_id = 42;
    render(<Harness conversion />);
    expect(screen.queryByTestId("candidate-convert")).not.toBeInTheDocument();
    expect(screen.getByTestId("candidate-linked-developer")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("OS requires candidate vendor and cannot substitute an arbitrary vendor", async () => {
    candidate.cand_se_cd = "OS";
    render(<Harness conversion />);
    await openConversion();
    fireEvent.click(screen.getByTestId("developer-save"));
    expect(
      await screen.findByText("외주는 협력사를 골라 주세요."),
    ).toBeVisible();
    expect(screen.getByLabelText("소속")).toBeDisabled();
    expect(writes()).toHaveLength(0);
  });
  it("conflict leaves supplemental input and server error visible", async () => {
    writeError = failure(null, 409);
    render(<Harness conversion />);
    await openConversion();
    fireEvent.change(screen.getByLabelText("비고"), {
      target: { value: "보완 내용" },
    });
    fireEvent.click(screen.getByTestId("developer-save"));
    await screen.findByText("문의 번호 developer-test");
    expect(screen.getByLabelText("비고")).toHaveValue("보완 내용");
    expect(changed).not.toHaveBeenCalled();
  });
  it("dirty cancellation asks for confirmation without sending a write", async () => {
    render(<Harness conversion />);
    await openConversion();
    fireEvent.change(screen.getByLabelText("비고"), {
      target: { value: "draft" },
    });
    fireEvent.click(screen.getByTestId("developer-cancel"));
    expect(await screen.findByText("입력을 취소할까요?")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("mobile hides conversion action", () => {
    vi.stubGlobal("innerWidth", 767);
    render(<Harness conversion />);
    expect(screen.queryByTestId("candidate-convert")).not.toBeInTheDocument();
  });
});
