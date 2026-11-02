export function billingNavigationMonth(state: unknown): string | undefined {
  if (state === null || typeof state !== "object") return undefined;
  const month = (state as { closingMonth?: unknown }).closingMonth;
  return typeof month === "string" &&
    /^(?!0000)\d{4}-(0[1-9]|1[0-2])$/.test(month)
    ? month
    : undefined;
}
