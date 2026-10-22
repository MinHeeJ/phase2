export type PeriodType = "YEAR" | "MONTH" | "YEAR_CUM" | "NONE";
export const screenIds = [
  "UI-01",
  "UI-02",
  "UI-03",
  "UI-04",
  "UI-05",
  "UI-07",
  "UI-08",
  "UI-09",
  "UI-10",
  "UI-11",
  "UI-12",
  "UI-13",
  "UI-14",
  "UI-15",
  "UI-16",
  "UI-17",
  "UI-18",
  "UI-19",
  "UI-20",
  "UI-21",
  "UI-22",
  "UI-23",
  "UI-24",
  "UI-25",
  "UI-26",
  "UI-27",
  "UI-28",
  "UI-29",
  "UI-30",
  "UI-31",
  "UI-32",
  "UI-33",
] as const;
export type ScreenId = (typeof screenIds)[number];
const years: readonly string[] = [
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
];
const months: readonly string[] = ["UI-19", "UI-20", "UI-14"];
const cumulative: readonly string[] = ["UI-07", "UI-08", "UI-09", "UI-18"];
export const periodType = (id: string): PeriodType =>
  years.includes(id)
    ? "YEAR"
    : months.includes(id)
      ? "MONTH"
      : cumulative.includes(id)
        ? "YEAR_CUM"
        : "NONE";
export const placeholderRoutes = screenIds
  .filter((id) => id !== "UI-27")
  .map((id) => `/${id}`);
