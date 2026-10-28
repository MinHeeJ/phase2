import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  DispatchExpiryCell,
  DispatchSoonFilter,
  type DispatchExpiryResult,
} from "./DispatchExpiryCell";

const warning: DispatchExpiryResult = {
  firstStart: "2024-11-20",
  expiry: "2026-11-19",
  daysRemaining: 36,
  alertDate: "2026-09-20",
  urgentDate: "2026-10-20",
  display: "WARNING",
};

describe("SCR-UI-05 dispatch expiry", () => {
  it("shows the warning and all dates in its tooltip", () => {
    render(<DispatchExpiryCell value={warning} />);
    expect(screen.getByText("파견 만료 D-36")).toBeInTheDocument();
    expect(
      screen.getByTitle(
        "최초 투입일 2024-11-20 · 만료일 2026-11-19 · 알림일 2026-09-20, 2026-10-20",
      ),
    ).toBeInTheDocument();
  });
  it("shows exceeded rather than remaining days", () => {
    render(<DispatchExpiryCell value={{ ...warning, display: "EXCEEDED" }} />);
    expect(screen.getByText("파견 기간 초과")).toBeInTheDocument();
  });
  it("shows only date for a completed assignment", () => {
    render(<DispatchExpiryCell value={{ ...warning, display: "DATE" }} />);
    expect(screen.getByText("2026-11-19")).toBeInTheDocument();
    expect(screen.queryByText("파견 기간 초과")).not.toBeInTheDocument();
  });
  it("shows a dash for a non-dispatch project", () => {
    render(
      <DispatchExpiryCell
        value={{ ...warning, display: "NONE", expiry: null }}
      />,
    );
    expect(screen.getByText("–")).toBeInTheDocument();
  });
  it("passes checkbox changes to the filter owner", () => {
    const onChange = vi.fn();
    render(<DispatchSoonFilter checked={false} onChange={onChange} />);
    fireEvent.click(
      screen.getByRole("checkbox", { name: "파견 만료 60일 안" }),
    );
    expect(onChange).toHaveBeenCalledWith(true);
  });
});
