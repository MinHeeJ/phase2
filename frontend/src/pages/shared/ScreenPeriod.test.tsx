import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PeriodBar } from "./PeriodBar";
import { useScreenPeriod } from "./useScreenPeriod";

function QueryPeriod({ screenId }: { screenId: string }) {
  const [period, setPeriod] = useScreenPeriod(screenId);
  return (
    <section data-testid="period-query-panel">
      <output data-testid="period-query-value">{period}</output>
      <button
        data-testid="period-query-update"
        onClick={() => setPeriod("2026-08")}
      >
        기간 변경
      </button>
      <button
        data-testid="period-query-invalid"
        onClick={() => setPeriod("2026-13")}
      >
        잘못된 기간
      </button>
    </section>
  );
}

beforeEach(() => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockImplementation(() =>
        Promise.resolve(
          new Response(JSON.stringify({ success: true, meta: {}, data: [] })),
        ),
      ),
  );
});
afterEach(() => vi.unstubAllGlobals());

describe("shared business period binding", () => {
  it("query and visible month update together in both directions", async () => {
    render(
      <>
        <PeriodBar screenId="UI-19" />
        <QueryPeriod screenId="UI-19" />
      </>,
    );
    fireEvent.change(screen.getByLabelText("조회월"), {
      target: { value: "2026-07" },
    });
    expect(screen.getByTestId("period-query-value")).toHaveTextContent(
      "2026-07",
    );
    fireEvent.click(screen.getByText("기간 변경"));
    expect(screen.getByLabelText("조회월")).toHaveValue("2026-08");
    await waitFor(() => expect(screen.getByText("작업중")).toBeInTheDocument());
  });
  it("screen identity changes never leak another screens period", () => {
    const { rerender } = render(
      <>
        <PeriodBar screenId="UI-19" />
        <QueryPeriod screenId="UI-19" />
      </>,
    );
    fireEvent.change(screen.getByLabelText("조회월"), {
      target: { value: "2026-06" },
    });
    rerender(
      <>
        <PeriodBar screenId="UI-14" />
        <QueryPeriod screenId="UI-14" />
      </>,
    );
    fireEvent.change(screen.getByLabelText("조회월"), {
      target: { value: "2026-05" },
    });
    expect(screen.getByTestId("period-query-value")).toHaveTextContent(
      "2026-05",
    );
    rerender(
      <>
        <PeriodBar screenId="UI-19" />
        <QueryPeriod screenId="UI-19" />
      </>,
    );
    expect(screen.getByLabelText("조회월")).toHaveValue("2026-06");
    expect(screen.getByTestId("period-query-value")).toHaveTextContent(
      "2026-06",
    );
  });
  it("invalid external period updates cannot desynchronize month controls", () => {
    render(
      <>
        <PeriodBar screenId="UI-19" />
        <QueryPeriod screenId="UI-19" />
      </>,
    );
    fireEvent.click(screen.getByText("기간 변경"));
    fireEvent.click(screen.getByText("잘못된 기간"));
    expect(screen.getByLabelText("조회월")).toHaveValue("2026-08");
    expect(screen.getByTestId("period-query-value")).toHaveTextContent(
      "2026-08",
    );
  });
  it("annual controls publish the same year while remembering the month", () => {
    render(
      <>
        <PeriodBar screenId="UI-03" />
        <QueryPeriod screenId="UI-03" />
      </>,
    );
    fireEvent.click(screen.getByText("기간 변경"));
    fireEvent.click(screen.getByLabelText("다음 연도"));
    expect(screen.getByTestId("period-query-value")).toHaveTextContent(
      "2027-08",
    );
    expect(screen.getByText("2027년")).toBeInTheDocument();
  });
});
