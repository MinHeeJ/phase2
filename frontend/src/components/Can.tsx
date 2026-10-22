import type { ReactNode } from "react";

export function Can({ perm, children }: { perm: string; children: ReactNode }) {
  return <span data-permission={perm}>{children}</span>;
}
