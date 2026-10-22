export type PeriodType = "YEAR" | "MONTH" | "YEAR_CUM" | "NONE";
const year = new Set([
  "UI-01",
  "UI-12",
  "UI-05",
  "UI-11",
  "UI-28",
  "UI-17",
  "UI-13",
  "UI-03",
  "UI-04",
  "UI-10",
  "UI-21",
]);
const month = new Set(["UI-19", "UI-20", "UI-14"]);
const cumulative = new Set(["UI-07", "UI-08", "UI-09", "UI-18"]);
export function periodType(id: string): PeriodType {
  return year.has(id)
    ? "YEAR"
    : month.has(id)
      ? "MONTH"
      : cumulative.has(id)
        ? "YEAR_CUM"
        : "NONE";
}
export function todayMonth(): string {
  return new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
  }).format(new Date());
}
