import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CandidateShortagesPanel } from "./CandidateShortagesPanel";
import { type CandidateShortagePage } from "./client";
import {
  response,
  failure,
  codeFixtures,
} from "../developers/DeveloperTestSupport";

let data: CandidateShortagePage,
  failed: boolean,
  fetcher: ReturnType<typeof vi.fn>;
const select = vi.fn(),
  filtersChanged = vi.fn();
const filters = {
  from: "2026-11",
  months: 6,
  grade: "SR",
  page: 0,
  size: 20 as const,
};
beforeEach(() => {
  data = {
    items: [
      {
        job_cd: "BE",
        grd_cd: "SR",
        maximum_shortage_mm: 1.01,
        maximum_shortage_people: 2,
        first_shortage_month: "2026-11",
        recommended_hiring_month: "2026-09",
        review_immediately: true,
        candidate_count: 3,
      },
    ],
    page: 0,
    size: 20,
    total: 1,
  };
  failed = false;
  select.mockReset();
  filtersChanged.mockReset();
  fetcher = vi.fn(async (url: string) => {
    if (url.startsWith("/api/codes/"))
      return response(codeFixtures[url.split("/").at(-1)!] ?? []);
    if (url.startsWith("/api/candidates/shortages?"))
      return failed ? failure(null, 500) : response(data);
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = () =>
  render(
    <CandidateShortagesPanel
      filters={filters}
      onFilters={filtersChanged}
      onCandidates={select}
    />,
  );
describe("candidate shortage shortcuts", () => {
  it("shows loading then API counts, raw MM and immediate hiring recommendation", async () => {
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    await screen.findByText("1.01 MM");
    expect(screen.getByText("2명")).toBeVisible();
    expect(screen.getByText("3명")).toBeVisible();
    expect(screen.getByText("채용 권장: 즉시 검토")).toBeVisible();
    expect(
      screen.getAllByRole("columnheader").map((cell) => cell.textContent),
    ).toEqual([
      "직무",
      "부족 시작",
      "가장 많이 부족",
      "그때까지 투입 가능한 후보",
    ]);
    expect(screen.getByText("1.01 MM").className).toContain("shortage");
    const url = fetcher.mock.calls.find((c) => c[0].includes("shortages?"))![0];
    const q = new URLSearchParams(url.split("?")[1]);
    expect(Object.fromEntries(q)).toEqual({
      from: "2026-11",
      months: "6",
      grade: "SR",
      page: "0",
      size: "20",
    });
  });
  it("passes selected job grade and shortage month to the candidate context without replacing direct project registration", async () => {
    panel();
    fireEvent.click(await screen.findByRole("button", { name: "후보 보기" }));
    expect(select).toHaveBeenCalledWith({
      job: "BE",
      grade: "SR",
      shortage_month: "2026-11",
    });
    expect(fetcher.mock.calls.every((c) => c[0].startsWith("/api/"))).toBe(
      true,
    );
  });
  it("retains shortage filters while paging with zero-based API indexes", async () => {
    data.total = 45;
    panel();
    await screen.findByText("1.01 MM");
    fireEvent.click(screen.getByLabelText("다음 페이지"));
    expect(filtersChanged).toHaveBeenCalledWith({ ...filters, page: 1 });
    fireEvent.change(screen.getByLabelText("목록 건수"), {
      target: { value: "50" },
    });
    expect(filtersChanged).toHaveBeenLastCalledWith({
      ...filters,
      page: 0,
      size: 50,
    });
  });
  it("empty result is meaningful and has no candidate navigation action", async () => {
    data = { ...data, items: [], total: 0 };
    panel();
    expect(
      await screen.findByText("조건에 맞는 부족 직무가 없습니다."),
    ).toBeVisible();
    expect(screen.queryByText("후보 보기")).not.toBeInTheDocument();
  });
  it("failed request renders retry instead of misleading empty table", async () => {
    failed = true;
    panel();
    expect(await screen.findByText("문의 번호 developer-test")).toBeVisible();
    expect(
      screen.queryByText("조건에 맞는 부족 직무가 없습니다."),
    ).not.toBeInTheDocument();
    failed = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await screen.findByText("1.01 MM");
    await waitFor(() =>
      expect(screen.queryByRole("alert")).not.toBeInTheDocument(),
    );
  });
  it("future recommendation displays actual month instead of immediate label", async () => {
    data.items[0].review_immediately = false;
    data.items[0].recommended_hiring_month = "2026-12";
    panel();
    expect(await screen.findByText("채용 권장: 2026-12")).toBeVisible();
    expect(screen.queryByText("채용 권장: 즉시 검토")).not.toBeInTheDocument();
  });
});
