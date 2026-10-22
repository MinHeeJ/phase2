import { describe, expect, it } from "vitest";
import {
  formatMoney,
  formatRatio,
  formatDate,
  formatMonth,
  formatDateTime,
  isDate,
  isMonth,
  numericClass,
  parseMoneyDraft,
} from "../src/lib/format";

describe("contract formatting", () => {
  it.each([
    [1500000, "1,500,000"],
    [-1320000, "−1,320,000"],
    [0, "0"],
    [null, "–"],
  ] as const)("formats won %s", (input, expected) =>
    expect(formatMoney(input)).toBe(expected),
  );
  it("marks negatives without parentheses", () => {
    expect(numericClass(-1)).toContain("danger");
    expect(formatMoney(-1)).not.toMatch(/[()]/);
    expect(numericClass(0)).not.toContain("danger");
  });
  it.each([
    ["10", "10.0%"],
    ["1.15", "1.2%"],
    ["1.05", "1.1%"],
    ["-1.15", "−1.2%"],
  ])("uses decimal half up %s", (input, expected) =>
    expect(formatRatio(input)).toBe(expected),
  );
  it("keeps strict calendar strings unchanged", () => {
    expect(formatDate("2026-10-01")).toBe("2026-10-01");
    expect(formatMonth("2026-10")).toBe("2026-10");
    expect(formatDate(null)).toBe("–");
  });
  it.each(["2026-02-29", "2026-04-31", "2026-2-01", "0000-01-01"])(
    "rejects invalid date %s",
    (input) => {
      expect(isDate(input)).toBe(false);
      expect(() => formatDate(input)).toThrow();
    },
  );
  it("recognizes leap years", () => {
    expect(isDate("2024-02-29")).toBe(true);
    expect(isDate("1900-02-29")).toBe(false);
    expect(isDate("2000-02-29")).toBe(true);
  });
  it.each(["2026-00", "2026-13", "0000-01", "2026-1"])(
    "rejects invalid month %s",
    (input) => {
      expect(isMonth(input)).toBe(false);
      expect(() => formatMonth(input)).toThrow();
    },
  );
  it("treats zoneless timestamps as Seoul wall time", () =>
    expect(formatDateTime("2026-10-01 00:05:45")).toBe("2026-10-01 00:05"));
  it("converts explicit offsets to Seoul including date rollover", () =>
    expect(formatDateTime("2026-09-30T16:05:45Z")).toBe("2026-10-01 01:05"));
  it.each(["2026-02-29T12:00:00Z", "2026-10-01T24:00:00", "nonsense"])(
    "does not normalize invalid datetime %s",
    (input) => expect(() => formatDateTime(input)).toThrow(),
  );
  it("parses pasted money without fabricating zero", () => {
    expect(parseMoneyDraft("1,500,000원")).toEqual({
      valid: true,
      value: 1500000,
      text: "1,500,000",
    });
    expect(parseMoneyDraft("  ")).toEqual({
      valid: true,
      value: null,
      text: "",
    });
  });
  it.each(["0", "999999999999"])("accepts boundary %s", (input) =>
    expect(parseMoneyDraft(input).valid).toBe(true),
  );
  it.each(["-1", "1000000000000", "1.5", "=1+1", "text"])(
    "rejects without clamping %s",
    (input) => expect(parseMoneyDraft(input).valid).toBe(false),
  );
});
