import { describe, expect, it } from "vitest";
import {
  amountClass,
  formatAmount,
  formatDate,
  formatDateTime,
  formatMonth,
  formatRatio,
  isValidDate,
} from "../src/lib/format";

describe("표시 계약", () => {
  it.each([
    [1500000, "1,500,000"],
    [-1320000, "−1,320,000"],
    [0, "0"],
    [null, "–"],
  ] as const)("금액 %s", (value, expected) => {
    expect(formatAmount(value)).toBe(expected);
    expect(formatAmount(value)).not.toContain("(");
  });
  it("음수만 위험 클래스", () => {
    expect(amountClass(-1)).toContain("negative");
    expect(amountClass(0)).not.toContain("negative");
  });
  it.each([
    ["10", "10.0%"],
    ["1.25", "1.3%"],
    ["-1.25", "−1.3%"],
    ["1.05", "1.1%"],
    ["0", "0.0%"],
  ])("decimal half-up %s", (value, expected) =>
    expect(formatRatio(value)).toBe(expected),
  );
  it("비율 결측", () => expect(formatRatio(null)).toBe("–"));
  it("서울 날짜와 연월", () => {
    expect(formatDate("2026-10-01")).toBe("2026-10-01");
    expect(formatMonth("2026-10")).toBe("2026-10");
    expect(formatMonth("2026-09")).toBe("2026-09");
  });
  it("offset 없는 timestamp는 서울 로컬", () =>
    expect(formatDateTime("2026-10-01 00:01:59")).toBe("2026-10-01 00:01"));
  it("offset timestamp는 서울로 변환", () => {
    expect(formatDateTime("2026-09-30T15:01:00Z")).toBe("2026-10-01 00:01");
    expect(formatDate(new Date("2026-09-30T15:00:00Z"))).toBe("2026-10-01");
  });
  it.each(["2026-02-29", "2026-04-31", "0000-01-01", "2026-13-01"])(
    "유효하지 않은 달력 %s",
    (value) => {
      expect(isValidDate(value)).toBe(false);
      expect(() => formatDate(value)).toThrow();
    },
  );
  it("윤년 날짜", () => expect(isValidDate("2024-02-29")).toBe(true));
  it("날짜 자동 rollover 거부", () =>
    expect(() => formatDateTime("2026-02-30T00:00:00Z")).toThrow());
  it("금액 비정수 거부", () => expect(() => formatAmount(1.5)).toThrow());
});
