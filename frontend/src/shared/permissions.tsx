import type { PropsWithChildren } from "react";

export function Can({ perm, children }: PropsWithChildren<{ perm: string }>) {
  return <span data-permission={perm}>{children}</span>;
}
export interface Column<T> {
  key: string;
  title: string;
  kind?: "number" | "text" | "code" | "status" | "date";
  sensitive?: boolean;
  render: (row: T) => React.ReactNode;
}
