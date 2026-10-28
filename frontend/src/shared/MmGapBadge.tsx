import { Badge } from "./Controls";
import { MmGapJudge } from "./MmGapJudge";

export function MmGapBadge({
  planMm,
  actualMm,
  tolerance,
}: {
  planMm: number;
  actualMm: number;
  tolerance: number;
}) {
  const gap = MmGapJudge(planMm, actualMm, tolerance);
  const difference =
    Math.abs(Math.round(actualMm * 100) - Math.round(planMm * 100)) / 100;
  return (
    <Badge
      tone={
        gap === "MATCH" ? "success" : gap === "SHORT" ? "danger" : "warning"
      }
    >
      {gap === "MATCH"
        ? "맞음"
        : `${gap === "SHORT" ? "부족" : "초과"} ${difference.toFixed(2)}`}
    </Badge>
  );
}
