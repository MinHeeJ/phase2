const missing = "–";
export const SEOUL_TIME_ZONE = "Asia/Seoul";

export function formatAmount(value: number | null | undefined): string {
  if (value == null) return missing;
  if (!Number.isSafeInteger(value))
    throw new RangeError("유효하지 않은 금액입니다.");
  return `${value < 0 ? "−" : ""}${Math.abs(value).toLocaleString("en-US")}`;
}
export const formatMoney = formatAmount;
export function amountClass(value: number | null | undefined): string {
  return value != null && value < 0 ? "numeric negative" : "numeric";
}

function decimalParts(value: number | string): {
  negative: boolean;
  digits: string;
  scale: number;
} {
  const match = /^(-?)(\d+)(?:\.(\d+))?(?:e([+-]?\d+))?$/i.exec(String(value));
  if (!match) throw new RangeError("유효하지 않은 숫자입니다.");
  return {
    negative: match[1] === "-",
    digits: match[2] + (match[3] ?? ""),
    scale: (match[3]?.length ?? 0) - Number(match[4] ?? 0),
  };
}
export function formatRatio(value: number | string | null | undefined): string {
  if (value == null) return missing;
  const { negative, digits, scale } = decimalParts(value);
  const shift = scale - 1;
  let tenths: bigint;
  if (shift <= 0) tenths = BigInt(digits) * 10n ** BigInt(-shift);
  else {
    const divisor = 10n ** BigInt(shift);
    const raw = BigInt(digits);
    tenths = raw / divisor + ((raw % divisor) * 2n >= divisor ? 1n : 0n);
  }
  return `${negative && tenths !== 0n ? "−" : ""}${tenths / 10n}.${tenths % 10n}%`;
}

export function isValidMonth(value: string): boolean {
  return /^(?!0000)\d{4}-(0[1-9]|1[0-2])$/.test(value);
}
export function isValidDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || !isValidMonth(value.slice(0, 7)))
    return false;
  const [year, month, day] = value.split("-").map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return day >= 1 && day <= days[month - 1];
}
function seoulParts(value: string | Date): { date: string; time: string } {
  if (typeof value === "string") {
    const match =
      /^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})?$/.exec(
        value,
      );
    if (
      !match ||
      !isValidDate(match[1]) ||
      Number(match[2]) > 23 ||
      Number(match[3]) > 59 ||
      Number(match[4] ?? 0) > 59
    )
      throw new RangeError("유효하지 않은 일시입니다.");
    if (!match[5]) return { date: match[1], time: `${match[2]}:${match[3]}` };
    if (
      match[5] !== "Z" &&
      !/^[+-](?:0\d|1[0-3]):[0-5]\d$|^[+-]14:00$/.test(match[5])
    )
      throw new RangeError("유효하지 않은 시간대입니다.");
  }
  const date =
    value instanceof Date ? value : new Date(value.replace(" ", "T"));
  if (Number.isNaN(date.getTime()))
    throw new RangeError("유효하지 않은 일시입니다.");
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: SEOUL_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(date);
  const part = (name: string) => parts.find((p) => p.type === name)!.value;
  return {
    date: `${part("year")}-${part("month")}-${part("day")}`,
    time: `${part("hour")}:${part("minute")}`,
  };
}
export function formatDate(value: string | Date | null | undefined): string {
  if (value == null) return missing;
  if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)) {
    if (!isValidDate(value)) throw new RangeError("유효하지 않은 날짜입니다.");
    return value;
  }
  return seoulParts(value).date;
}
export function formatMonth(value: string | null | undefined): string {
  if (value == null) return missing;
  if (!isValidMonth(value)) throw new RangeError("유효하지 않은 연월입니다.");
  return value;
}
export function formatDateTime(
  value: string | Date | null | undefined,
): string {
  if (value == null) return missing;
  const parts = seoulParts(value);
  return `${parts.date} ${parts.time}`;
}
