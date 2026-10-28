import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MmGapBadge } from "./MmGapBadge";
import { MmGapJudge } from "./MmGapJudge";

describe("shared MM tolerance", () => {
  it.each([
    [3, 2.5, "SHORT", "부족 0.50", "danger"],
    [3, 3.2, "OVER", "초과 0.20", "warning"],
    [3, 3, "MATCH", "맞음", "success"],
    [3, 2.95, "MATCH", "맞음", "success"],
    [3, 2.9, "MATCH", "맞음", "success"],
    [3, 3.1, "MATCH", "맞음", "success"],
    [3, 2.89, "SHORT", "부족 0.11", "danger"],
    [3, 3.11, "OVER", "초과 0.11", "warning"],
    [4, 3.95, "MATCH", "맞음", "success"],
  ])(
    "judges and renders %s vs %s without floating-point drift",
    (plan, actual, gap, label, tone) => {
      expect(MmGapJudge(Number(plan), Number(actual), 0.1)).toBe(gap);
      render(
        <MmGapBadge
          planMm={Number(plan)}
          actualMm={Number(actual)}
          tolerance={0.1}
        />,
      );
      expect(screen.getByText(String(label)).className).toContain(tone);
    },
  );
  it("uses the supplied DB tolerance instead of a fixed constant", () => {
    expect(MmGapJudge(3, 3.11, 0.2)).toBe("MATCH");
    expect(MmGapJudge(3, 3.01, 0)).toBe("OVER");
  });
  it("rejects invalid parameters rather than assuming a fallback", () => {
    expect(() => MmGapJudge(1, 1, NaN)).toThrow(RangeError);
    expect(() => MmGapJudge(1, 1, -1)).toThrow(RangeError);
  });
});
