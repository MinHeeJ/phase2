export type MmGap = "SHORT" | "OVER" | "MATCH";

export function MmGapJudge(
  planMm: number,
  actualMm: number,
  tolerance: number,
): MmGap {
  if (![planMm, actualMm, tolerance].every(Number.isFinite) || tolerance < 0)
    throw new RangeError("MM과 허용 범위를 확인하세요.");
  const delta = Math.round(actualMm * 100) - Math.round(planMm * 100);
  if (Math.abs(delta) <= Math.round(tolerance * 100)) return "MATCH";
  return delta < 0 ? "SHORT" : "OVER";
}
