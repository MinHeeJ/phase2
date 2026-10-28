import { Badge } from "./Controls";
import { mmGapJudge } from "./mmGapJudge";

export function MmGapBadge({
  plan,
  actual,
  tolerance,
}: {
  plan: number;
  actual: number;
  tolerance: number;
}) {
  const result = mmGapJudge(plan, actual, tolerance);
  return (
    <Badge
      tone={
        result.status === "MATCH"
          ? "success"
          : result.status === "SHORT"
            ? "danger"
            : "warning"
      }
    >
      {result.text}
    </Badge>
  );
}
