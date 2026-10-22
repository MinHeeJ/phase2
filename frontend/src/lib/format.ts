const EMPTY = "–";
export const numericClass = (value: number | bigint | null | undefined) =>
  value != null && value < 0 ? "numeric danger" : "numeric";

export function formatMoney(value: number | bigint | null | undefined): string {
  if (value == null) return EMPTY;
  if (typeof value === "number" && !Number.isSafeInteger(value))
    throw new RangeError("원 단위 정수가 필요합니다.");
  const text = value.toString();
  return (
    (text.startsWith("-") ? "−" : "") +
    text.replace("-", "").replace(/\B(?=(\d{3})+(?!\d))/g, ",")
  );
}

export function formatRatio(value: number | string | null | undefined): string {
  if (value == null) return EMPTY;
  const source = String(value);
  if (!/^-?\d+(?:\.\d+)?$/.test(source))
    throw new RangeError("비율을 확인하세요.");
  const negative = source.startsWith("-");
  const [whole, fraction = ""] = source.replace("-", "").split(".");
  let tenth = BigInt(whole) * 10n + BigInt((fraction + "0")[0]);
  if (Number((fraction + "00")[1]) >= 5) tenth += 1n;
  return (
    (negative && tenth !== 0n ? "−" : "") + `${tenth / 10n}.${tenth % 10n}%`
  );
}

export function isMonth(value: string): boolean {
  return /^(?!0000)\d{4}-(0[1-9]|1[0-2])$/.test(value);
}
export function isDate(value: string): boolean {
  if (!/^(?!0000)\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1];
}
export function formatDate(value: string | null | undefined): string {
  if (value == null) return EMPTY;
  if (!isDate(value)) throw new RangeError("날짜를 확인하세요.");
  return value;
}
export function formatMonth(value: string | null | undefined): string {
  if (value == null) return EMPTY;
  if (!isMonth(value)) throw new RangeError("연월을 확인하세요.");
  return value;
}
export function formatDateTime(value: string | null | undefined): string {
  if (value == null) return EMPTY;
  const found =
    /^(\d{4}-\d{2}-\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})?$/.exec(
      value,
    );
  if (
    !found ||
    !isDate(found[1]) ||
    +found[2] > 23 ||
    +found[3] > 59 ||
    +(found[4] ?? 0) > 59
  )
    throw new RangeError("일시를 확인하세요.");
  if (!found[5]) return `${found[1]} ${found[2]}:${found[3]}`;
  const date = new Date(value.replace(" ", "T"));
  if (Number.isNaN(date.valueOf())) throw new RangeError("일시를 확인하세요.");
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(date);
  const part = (key: string) => parts.find((p) => p.type === key)!.value;
  return `${part("year")}-${part("month")}-${part("day")} ${part("hour")}:${part("minute")}`;
}

export type MoneyDraft =
  | { valid: true; value: number | null; text: string }
  | { valid: false; message: string };
export function parseMoneyDraft(input: string): MoneyDraft {
  const text = input.replace(/[,\s원]/g, "");
  if (text === "") return { valid: true, value: null, text: "" };
  if (!/^\d+$/.test(text) || text.length > 12 || BigInt(text) > 999999999999n)
    return {
      valid: false,
      message: "0~999,999,999,999 사이의 정수를 입력하세요.",
    };
  const value = Number(text);
  return { valid: true, value, text: formatMoney(value) };
}
