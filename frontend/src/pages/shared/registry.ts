import type { MenuNode } from "./api";

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
export type ScreenId = (typeof screenIds)[number] | "UI-22";
const year = new Set<string>([
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
const month = new Set<string>(["UI-19", "UI-20", "UI-14"]);
const cumulative = new Set<string>(["UI-07", "UI-08", "UI-09", "UI-18"]);
export function periodType(screenId: string): PeriodType {
  return year.has(screenId)
    ? "YEAR"
    : month.has(screenId)
      ? "MONTH"
      : cumulative.has(screenId)
        ? "YEAR_CUM"
        : "NONE";
}
export const sharedRoutes = [
  ...screenIds.filter((id) => id !== "UI-27"),
  "UI-22",
].map((id) => `/${id}`);
export function isScreenId(value: string): value is ScreenId {
  return value === "UI-22" || screenIds.some((id) => id === value);
}
export function locateScreen(
  menus: readonly MenuNode[],
  screenId: string,
): { screen: MenuNode; group: MenuNode | null } | null {
  for (const group of menus) {
    if (group.screenId === screenId) return { screen: group, group: null };
    const screen = group.children.find((child) => child.screenId === screenId);
    if (screen) return { screen, group };
  }
  return null;
}
