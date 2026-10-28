import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  DispatchExpiryCell,
  DispatchExpiryFilter,
  dispatchQuery,
  type DispatchExpiryFields,
} from "./DispatchExpiry";
import controls from "../../shared/Controls.module.css";

const countdown: DispatchExpiryFields = {
  dispatch_first_dt: "2024-11-20",
  dispatch_expiry_dt: "2026-11-19",
  dispatch_display: "파견 만료 D-36",
  dispatch_tone: "warning",
  dispatch_alert_dates: ["2026-09-20", "2026-10-20"],
};

describe("SCR-UI-05 파견 만료 열", () => {
  it("shows the countdown with shared warning token and complete tooltip", () => {
    render(<DispatchExpiryCell assignmentId={7} value={countdown} />);
    expect(screen.getByText("파견 만료 D-36")).toHaveClass(
      controls.badge,
      controls.warning,
    );
    expect(screen.getByTestId("dispatch-expiry-7")).toHaveAttribute(
      "title",
      "최초 투입일 2024-11-20 · 만료일 2026-11-19 · 알림일 2026-09-20, 2026-10-20",
    );
    expect(screen.getByTestId("dispatch-expiry-7")).toHaveAttribute(
      "tabindex",
      "0",
    );
  });

  it("uses danger for overrun rather than a countdown", () => {
    render(
      <DispatchExpiryCell
        assignmentId={7}
        value={{
          ...countdown,
          dispatch_display: "파견 기간 초과",
          dispatch_tone: "danger",
        }}
      />,
    );
    expect(screen.getByText("파견 기간 초과")).toHaveClass(controls.danger);
    expect(screen.queryByText(/D-/)).not.toBeInTheDocument();
  });

  it.each(["파견 만료 D-0", "파견 만료 D-30"])(
    "uses danger at the lower threshold: %s",
    (text) => {
      render(
        <DispatchExpiryCell
          assignmentId={7}
          value={{
            ...countdown,
            dispatch_display: text,
            dispatch_tone: "danger",
          }}
        />,
      );
      expect(screen.getByText(text)).toHaveClass(controls.danger);
    },
  );

  it("renders ended or far-future expiry as a plain date, not a colored badge", () => {
    render(
      <DispatchExpiryCell
        assignmentId={7}
        value={{
          ...countdown,
          dispatch_display: "2026-11-19",
          dispatch_tone: "neutral",
        }}
      />,
    );
    expect(screen.getByText("2026-11-19")).not.toHaveClass(controls.badge);
  });

  it("reflects a server current-contract change to non-DP immediately", () => {
    const { rerender } = render(
      <DispatchExpiryCell assignmentId={7} value={countdown} />,
    );
    rerender(
      <DispatchExpiryCell
        assignmentId={7}
        value={{
          dispatch_first_dt: null,
          dispatch_expiry_dt: null,
          dispatch_display: "–",
          dispatch_tone: "neutral",
          dispatch_alert_dates: [],
        }}
      />,
    );
    expect(screen.getByText("–")).toBeInTheDocument();
    expect(screen.queryByText("파견 만료 D-36")).not.toBeInTheDocument();
    expect(screen.getByTestId("dispatch-expiry-7")).not.toHaveAttribute(
      "title",
    );
  });

  it("keeps row marks unique for multiple assignments", () => {
    render(
      <>
        <DispatchExpiryCell assignmentId={7} value={countdown} />
        <DispatchExpiryCell assignmentId={8} value={countdown} />
      </>,
    );
    expect(screen.getByTestId("dispatch-expiry-7")).toBeInTheDocument();
    expect(screen.getByTestId("dispatch-expiry-8")).toBeInTheDocument();
  });
});

describe("파견 만료 60일 안 query control", () => {
  it("reports a draft boolean without filtering the current page", () => {
    const change = vi.fn();
    render(<DispatchExpiryFilter checked={false} onChange={change} />);
    fireEvent.click(
      screen.getByRole("checkbox", { name: "파견 만료 60일 안" }),
    );
    expect(change).toHaveBeenCalledWith(true);
    expect(dispatchQuery(true)).toEqual({ dispatchWithin60: "true" });
  });

  it("reset clears the checkbox and omits the wire parameter", () => {
    const change = vi.fn();
    const { rerender } = render(
      <DispatchExpiryFilter checked onChange={change} />,
    );
    expect(screen.getByRole("checkbox")).toBeChecked();
    rerender(<DispatchExpiryFilter checked={false} onChange={change} />);
    expect(screen.getByRole("checkbox")).not.toBeChecked();
    expect(dispatchQuery(false)).toEqual({});
  });

  it("can be disabled during an in-flight operation without making a request", () => {
    const change = vi.fn();
    render(<DispatchExpiryFilter checked={false} onChange={change} disabled />);
    expect(screen.getByRole("checkbox")).toBeDisabled();
    expect(change).not.toHaveBeenCalled();
  });

  it("shows the API singular-field validation message next to the control", () => {
    render(
      <DispatchExpiryFilter
        checked={false}
        onChange={vi.fn()}
        error="파견 만료 조회 조건을 확인하세요."
      />,
    );
    expect(screen.getByRole("checkbox")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "파견 만료 조회 조건을 확인하세요.",
    );
  });
});
