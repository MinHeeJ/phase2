import { closingNavigation } from "../shared/closingNavigation";

export function billingNavigationMonth(state: unknown): string | undefined {
  return closingNavigation(state).month;
}
