export type ClosingCheckCode =
  | "SALARY_UPLOAD"
  | "MM_OVER"
  | "DISPATCH_ATT"
  | "BILL_UNCONFIRMED"
  | "TAX_INVOICE_NO"
  | "FIN_ENTRY"
  | "PART_SETTLE"
  | "PAYMENT"
  | "BILL_MISSING";

const checks = new Set<ClosingCheckCode>([
  "SALARY_UPLOAD", "MM_OVER", "DISPATCH_ATT", "BILL_UNCONFIRMED",
  "TAX_INVOICE_NO", "FIN_ENTRY", "PART_SETTLE", "PAYMENT", "BILL_MISSING",
]);

export function closingNavigation(state: unknown): {
  month?: string;
  check?: ClosingCheckCode;
} {
  if (state === null || typeof state !== "object") return {};
  const { closingMonth, closingCheck } = state as {
    closingMonth?: unknown;
    closingCheck?: unknown;
  };
  const month = typeof closingMonth === "string" &&
    /^(?!0000)\d{4}-(0[1-9]|1[0-2])$/.test(closingMonth)
    ? closingMonth : undefined;
  const check = typeof closingCheck === "string" &&
    checks.has(closingCheck as ClosingCheckCode)
    ? closingCheck as ClosingCheckCode : undefined;
  return { month, check: month ? check : undefined };
}
