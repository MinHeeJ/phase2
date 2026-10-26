export const EMPTY_VALUE = "–";
export type CalendarInput = string | Date | null | undefined;
const formatter = new Intl.NumberFormat("ko-KR", {
  useGrouping: true,
  maximumFractionDigits: 20,
});
export function formatMoney(value: number | bigint | null | undefined): string {
  if (value == null || (typeof value === "number" && !Number.isFinite(value)))
    return EMPTY_VALUE;
  return (value < 0 ? "−" : "") + formatter.format(value < 0 ? -value : value);
}
export function moneyClass(value: number | bigint | null | undefined): string {
  return value != null && value < 0 ? "numeric danger" : "numeric";
}
// Decimal half-up, including negative ties; exponential numeric strings are expanded first.
export function formatRatio(value: number | string | null | undefined): string {
  if (value == null || value === "") return EMPTY_VALUE;
  let text = String(value);
  if (!/^-?\d+(?:\.\d+)?$/.test(text)) {
    if (!Number.isFinite(Number(text))) return EMPTY_VALUE;
    text = Number(text).toLocaleString("en-US", {
      useGrouping: false,
      maximumFractionDigits: 20,
    });
  }
  if (!/^-?\d+(?:\.\d+)?$/.test(text)) return EMPTY_VALUE;
  const negative = text.startsWith("-");
  const [whole, fraction = ""] = text.replace(/^-/, "").split(".");
  let tenths = BigInt(whole) * 10n + BigInt((fraction + "0")[0]);
  if (Number((fraction + "00")[1]) >= 5) tenths += 1n;
  return `${negative && tenths !== 0n ? "−" : ""}${tenths / 10n}.${tenths % 10n}%`;
}
function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [y, m, d] = value.split("-").map(Number);
  const leap = y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0);
  return (
    y > 0 &&
    m >= 1 &&
    m <= 12 &&
    d >= 1 &&
    d <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1]
  );
}
function calendar(value: CalendarInput): string | null {
  if (value == null || value === "") return null;
  if (typeof value === "string") {
    if (/^\d{4}-\d{2}$/.test(value))
      return validDate(`${value}-01`) ? value : null;
    if (/^\d{4}-\d{2}-\d{2}$/.test(value))
      return validDate(value) ? value : null;
    const match =
      /^(\d{4}-\d{2}-\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})?$/.exec(
        value,
      );
    if (
      !match ||
      !validDate(match[1]) ||
      Number(match[2]) > 23 ||
      Number(match[3]) > 59 ||
      Number(match[4] ?? 0) > 59
    )
      return null;
    if (!match[6]) return `${match[1]} ${match[2]}:${match[3]}`;
    if (match[6] !== "Z") {
      const [h, m] = match[6].slice(1).split(":").map(Number);
      if (h > 23 || m > 59) return null;
    }
    value = new Date(value.replace(" ", "T"));
  }
  if (Number.isNaN(value.getTime())) return null;
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(value);
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value;
  return `${get("year")}-${get("month")}-${get("day")} ${get("hour")}:${get("minute")}`;
}
export function formatDate(value: CalendarInput): string {
  const text = calendar(value);
  return text && text.length >= 10 ? text.slice(0, 10) : EMPTY_VALUE;
}
export function formatMonth(value: CalendarInput): string {
  return calendar(value)?.slice(0, 7) ?? EMPTY_VALUE;
}
export function formatDateTime(value: CalendarInput): string {
  const text = calendar(value);
  return text?.length === 16 ? text : EMPTY_VALUE;
}
export function normalizeMoney(value: string): string {
  return value.replace(/[,\s원]/g, "");
}
export function validMoney(value: string): boolean {
  return /^\d+$/.test(value) && BigInt(value) <= 999999999999n;
}
