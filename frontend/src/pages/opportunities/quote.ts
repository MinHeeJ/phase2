import type { OpportunityNeedInput } from "./client";

const cents = (value: number) => {
  const [whole, fraction = ""] = String(value).split(".");
  return BigInt(whole) * 100n + BigInt((fraction + "00").slice(0, 2));
};
export function opportunityRevenue(
  needs: readonly OpportunityNeedInput[],
): bigint | null {
  if (
    needs.some(
      (n) =>
        !Number.isInteger(n.psn_cnt) ||
        !Number.isInteger(n.mth_cnt) ||
        !Number.isFinite(n.mth_mm_qty) ||
        n.expc_untprc_amt == null ||
        !Number.isSafeInteger(n.expc_untprc_amt),
    )
  )
    return null;
  return (
    needs.reduce(
      (sum, n) =>
        sum +
        BigInt(n.psn_cnt) *
          BigInt(n.mth_cnt) *
          cents(n.mth_mm_qty) *
          BigInt(n.expc_untprc_amt!),
      0n,
    ) / 100n
  );
}
export function opportunityDifferenceRate(
  difference: bigint,
  amount: number,
): string {
  if (!amount) return "N/A";
  const negative = difference < 0n;
  const numerator = (negative ? -difference : difference) * 10000n;
  const denominator = BigInt(amount);
  const rounded = (numerator * 2n + denominator) / (denominator * 2n);
  return `${negative ? "-" : ""}${rounded / 100n}.${String(rounded % 100n).padStart(2, "0")}%`;
}
export function opportunityMonthEnd(start: string, count: number) {
  const [year, month] = start.split("-").map(Number);
  if (!year || !month || count < 1 || count > 120) return "";
  const index = year * 12 + month - 1 + count - 1;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, "0")}`;
}
