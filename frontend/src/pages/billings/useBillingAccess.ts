import { useAdminRead } from "../admin/useAdminResource";
import { readData } from "../../shared/api";
import type { MenuNode } from "../shared/api";

interface BillingPermissions {
  roles: { name: string }[];
  menus: MenuNode[];
  permissions: { menuId: number; inq: boolean; mdfcn: boolean }[];
}
const readBillingPermissions = () =>
  readData<BillingPermissions>("/api/me/permissions");
const readers = new Set(["시스템 관리자", "경영진", "재무 담당"]);
const editors = new Set(["시스템 관리자", "재무 담당"]);

export function useBillingAccess() {
  const state = useAdminRead(readBillingPermissions);
  const data = !state.error && !state.loading ? state.data : null;
  const menuIds = new Set<number>();
  const visit = (menus: BillingPermissions["menus"]) => {
    for (const menu of menus) {
      if (menu.screenId === "UI-13") menuIds.add(menu.menuId);
      visit(menu.children ?? []);
    }
  };
  if (data) visit(data.menus ?? []);
  const permissions =
    data?.permissions?.filter((p) => menuIds.has(p.menuId) && p.inq) ?? [];
  const canRead =
    !!data?.roles?.some((r) => readers.has(r.name)) && permissions.length > 0;
  const canWrite = canRead && !!data?.roles?.some((r) => editors.has(r.name));
  const canUpdate = canWrite && permissions.some((p) => p.mdfcn);
  return { ...state, canRead, canWrite, canUpdate };
}
