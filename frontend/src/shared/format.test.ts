import { describe, expect, it } from "vitest";
import {
  formatMoney,
  moneyClass,
  formatRatio,
  formatDate,
  formatMonth,
  formatDateTime,
  normalizeMoney,
  validMoney,
} from "../lib/format";

describe("표시 규칙", () => {
  it("금액은 쉼표, 유니코드 음수, 0, 없음으로 구분한다", () => {
    expect(formatMoney(1500000)).toBe("1,500,000");
    expect(formatMoney(-1320000)).toBe("−1,320,000");
    expect(formatMoney(-1320000)).not.toContain("(");
    expect(moneyClass(-1)).toContain("danger");
    expect(formatMoney(0)).toBe("0");
    expect(formatMoney(null)).toBe("–");
  });
  it("비율은 십진 half-up 한 자리로 반올림한다", () => {
    expect(formatRatio("1.25")).toBe("1.3%");
    expect(formatRatio("-1.25")).toBe("−1.3%");
    expect(formatRatio(10)).toBe("10.0%");
    expect(formatRatio(0)).toBe("0.0%");
  });
  it("날짜와 연월은 달력값을 유지한다", () => {
    expect(formatDate("2026-10-01")).toBe("2026-10-01");
    expect(formatMonth("2026-10")).toBe("2026-10");
  });
  it("오프셋 시각은 서울, DB TIMESTAMP는 서울 벽시각이다", () => {
    expect(formatDateTime("2026-09-30T16:05:30Z")).toBe("2026-10-01 01:05");
    expect(formatDateTime("2026-10-01 01:05:30")).toBe("2026-10-01 01:05");
    expect(formatMonth(new Date("2026-09-30T16:00:00Z"))).toBe("2026-10");
  });
  it("모호하거나 잘못된 날짜는 허용하지 않는다", () => {
    for (const date of [
      "2026-02-29",
      "2026-13-01",
      "10/01/2026",
      "2026-10-01T25:00:00Z",
    ])
      expect(formatDate(date)).toBe("–");
    expect(formatDate("2024-02-29")).toBe("2024-02-29");
  });
  it("금액 정규화는 쉼표 공백 원만 제거하고 범위를 검사한다", () => {
    expect(normalizeMoney("1,500,000 원")).toBe("1500000");
    expect(normalizeMoney("12x원")).toBe("12x");
    expect(validMoney("0")).toBe(true);
    expect(validMoney("999999999999")).toBe(true);
    for (const value of ["1000000000000", "-1", "1.1", "12x"])
      expect(validMoney(value)).toBe(false);
  });
});
