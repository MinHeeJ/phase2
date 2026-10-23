import type { ReactNode } from "react";
import { can } from "./types";

export function Can({ perm, children }: { perm: string; children: ReactNode }) {
  return can(perm) ? <>{children}</> : null;
}
