import { describe, it, expect } from "vitest";
import { amount, amountTone, rate, date, parseAmount } from "../src/lib/format";
describe("표시와 입력 정본", () => {
  it("금액·음수·0·결측값", () => {
    expect(amount(1500000)).toBe("1,500,000");
    expect(amount(-1320000)).toBe("−1,320,000");
    expect(amountTone(-1)).toBe("danger");
    expect(amount(0)).toBe("0");
    expect(amount(null)).toBe("–");
    expect(rate(10)).toBe("10.0%");
  });
  it("서울 날짜와 월", () => {
    expect(date("2026-10")).toBe("2026-10");
    expect(date("2026-10-01T00:00:00Z", "datetime")).toBe("2026-10-01 09:00");
    expect(date("2026-10-01 00:00:00", "datetime")).toBe("2026-10-01 00:00");
  });
  it("붙여넣기와 경계", () => {
    expect(parseAmount("1,500,000원")).toBe(1500000);
    expect(parseAmount("0")).toBe(0);
    expect(parseAmount("999999999999")).toBe(999999999999);
    expect(parseAmount("1000000000000")).toBeUndefined();
    expect(parseAmount("-1")).toBeUndefined();
  });
});
