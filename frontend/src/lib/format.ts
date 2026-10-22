export const missing = "–";
export function amount(value: number | null | undefined): string {
  if (value == null) return missing;
  return `${value < 0 ? "−" : ""}${new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 }).format(Math.abs(value))}`;
}
export function amountTone(value: number | null | undefined): string {
  return value != null && value < 0 ? "danger" : "";
}
export function rate(value: number | null | undefined): string {
  return value == null ? missing : `${value.toFixed(1)}%`;
}
export function date(
  value: string | null | undefined,
  mode: "date" | "month" | "datetime" = "date",
): string {
  if (!value) return missing;
  if (/^\d{4}-\d{2}$/.test(value)) return value;
  if (/^\d{4}-\d{2}-\d{2}$/.test(value))
    return mode === "month" ? value.slice(0, 7) : value;
  const local =
    /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}/.test(value) &&
    !/(Z|[+-]\d{2}:\d{2})$/.test(value)
      ? `${value.replace(" ", "T")}+09:00`
      : value;
  const d = new Date(local);
  if (Number.isNaN(d.getTime())) return missing;
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("sv-SE", {
      timeZone: "Asia/Seoul",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(d)
      .map((x) => [x.type, x.value]),
  );
  const day = `${parts.year}-${parts.month}-${parts.day}`;
  return mode === "month"
    ? day.slice(0, 7)
    : mode === "datetime"
      ? `${day} ${parts.hour}:${parts.minute}`
      : day;
}
export function parseAmount(raw: string): number | null | undefined {
  const clean = raw.replace(/[,\s원]/g, "");
  if (!clean) return null;
  if (!/^\d+$/.test(clean)) return undefined;
  const n = Number(clean);
  return n <= 999999999999 ? n : undefined;
}
