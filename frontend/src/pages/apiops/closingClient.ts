import { readData } from "../../shared/api";

export interface ClosingCheck {
  code: string;
  status: "OK" | "WARN" | "FAIL";
  count: number;
  description?: string;
}
export interface Closing {
  month: string;
  status: "OPEN" | "CLOSED";
  checks: ClosingCheck[];
  checkedAt?: string;
  canReopen: boolean;
  warningCount?: number;
  failureCount?: number;
  processedAt?: string;
  actorId?: number;
  actorName?: string;
}
export function getClosing(
  month: string,
  signal?: AbortSignal,
): Promise<Closing> {
  return readData(`/api/closing?${new URLSearchParams({ month })}`, signal);
}
