export type MmGapStatus = "SHORT" | "MATCH" | "OVER";

export function mmGapJudge(
  plan: number,
  actual: number,
  tolerance: number,
): { status: MmGapStatus; text: string } {
  const difference = Math.round(actual * 100) - Math.round(plan * 100);
  const limit = Math.round(tolerance * 100);
  if (Math.abs(difference) <= limit) return { status: "MATCH", text: "맞음" };
  const status = difference < 0 ? "SHORT" : "OVER";
  return {
    status,
    text: `${status === "SHORT" ? "부족" : "초과"} ${(Math.abs(difference) / 100).toFixed(2)}`,
  };
}
