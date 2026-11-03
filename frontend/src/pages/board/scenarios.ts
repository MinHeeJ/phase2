import type { BoardOpportunity, BoardRow, WorkforceMonth } from "./client";

export interface BoardScenario {
  id: number;
  name: string;
  opportunityIds: number[];
  high: number;
  medium: number;
  low: number;
  job: string;
  grade: string;
  people: number;
}
export const boardScenarioDefaults = (id: number): BoardScenario => ({
  id,
  name: `시나리오 ${id}`,
  opportunityIds: [],
  high: 80,
  medium: 50,
  low: 0,
  job: "",
  grade: "",
  people: 0,
});
const cents = (value: number) => BigInt(Math.round(value * 100));
const decimal = (value: bigint) => Number(value) / 100;
const monthIndex = (value: string) =>
  Number(value.slice(0, 4)) * 12 + Number(value.slice(5, 7)) - 1;
const applies = (month: string, start: string, count: number) =>
  monthIndex(month) >= monthIndex(start) &&
  monthIndex(month) < monthIndex(start) + count;
export function compareBoardScenario(
  base: WorkforceMonth,
  scenario: BoardScenario,
  opportunities: BoardOpportunity[],
  people: BoardRow[],
) {
  let demandDelta = 0n,
    revenueDelta = 0n;
  for (const opportunity of opportunities.filter((o) =>
    scenario.opportunityIds.includes(o.oppt_id),
  )) {
    if (opportunity.oppt_stts_cd !== "OPEN") continue;
    const probabilityDelta = 10000n - cents(opportunity.win_prblt_rt);
    for (const need of opportunity.needs) {
      if (
        need.job_cd === base.job_cd &&
        (need.grd_cd ?? "") === (base.grd_cd ?? "") &&
        applies(base.month, need.bgn_ym, need.mth_cnt)
      )
        demandDelta +=
          (BigInt(need.psn_cnt) * cents(need.mth_mm_qty) * probabilityDelta) /
          10000n;
    }
    if (applies(base.month, opportunity.bgn_ym, opportunity.mth_cnt)) {
      // Revenue is an opportunity total, never duplicated across job/grade buckets.
      const bucket = opportunity.needs.find((n) =>
        applies(base.month, n.bgn_ym, n.mth_cnt),
      );
      if (
        bucket &&
        bucket.job_cd === base.job_cd &&
        (bucket.grd_cd ?? "") === (base.grd_cd ?? "")
      ) {
        const monthly =
          BigInt(opportunity.expc_amt) / BigInt(opportunity.mth_cnt);
        revenueDelta +=
          monthly - (monthly * cents(opportunity.win_prblt_rt)) / 10000n;
      }
    }
  }
  let extensionDelta = 0;
  const warnings = [...base.warnings];
  for (const person of people.filter(
    (p) => p.job_cd === base.job_cd && (p.grd_cd ?? "") === (base.grd_cd ?? ""),
  )) {
    const unique = new Map(
      person.months.flatMap((c) => c.assignments).map((a) => [a.asgn_id, a]),
    );
    for (const assignment of unique.values()) {
      const endMonth = assignment.end_dt.slice(0, 7);
      if (endMonth >= base.month || !assignment.ext_psblty_cd) continue;
      if (
        [...unique.values()].some(
          (a) =>
            a.asgn_id !== assignment.asgn_id &&
            a.bgn_dt.slice(0, 7) > endMonth &&
            a.bgn_dt.slice(0, 7) <= base.month,
        )
      )
        continue;
      const endCell = person.months.find((c) => c.month === endMonth);
      const end = endCell?.assignments.find(
        (a) => a.asgn_id === assignment.asgn_id,
      );
      if (!end) {
        warnings.push("종료월 MM 원천 미확인: 연장 가정 제외");
        continue;
      }
      const probability =
        assignment.ext_psblty_cd === "H"
          ? scenario.high
          : assignment.ext_psblty_cd === "M"
            ? scenario.medium
            : scenario.low;
      const original =
        assignment.ext_psblty_cd === "H"
          ? 80
          : assignment.ext_psblty_cd === "M"
            ? 50
            : 0;
      extensionDelta +=
        (end.current_month_mm_qty * (probability - original)) / 100;
    }
  }
  const headcount =
    scenario.job === base.job_cd && scenario.grade === (base.grd_cd ?? "")
      ? scenario.people
      : 0;
  const laborDelta = BigInt(headcount) * BigInt(base.average_labor_cost);
  if (headcount !== 0 && base.average_labor_cost === 0)
    warnings.push("원가 미확인 · 가정 인건비 0원");
  const supply = Math.max(base.supply_mm + headcount - extensionDelta, 0);
  const demand = base.demand_mm + decimal(demandDelta);
  const operatingProfit =
    base.baseline_operating_profit == null
      ? null
      : Number(
          BigInt(base.baseline_operating_profit) + revenueDelta - laborDelta,
        );
  if (operatingProfit === null)
    warnings.push("기존 예상 영업이익 원천 미확인 · 증감만 표시");
  return {
    supply,
    demand,
    remaining: supply - demand,
    idleCost: Math.max(
      base.idle_cost + Math.max(headcount, 0) * base.average_labor_cost,
      0,
    ),
    revenueDelta: Number(revenueDelta),
    laborDelta: Number(laborDelta),
    profitDelta: Number(revenueDelta - laborDelta),
    operatingProfit,
    warnings: [...new Set(warnings)],
  };
}
