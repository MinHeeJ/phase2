import { useEffect, useState } from "react";
import { readData } from "../../shared/api";
import type { MenuNode } from "../shared/api";
import {
  notificationPermissionEvent,
  notificationSessionEvent,
} from "./NotificationsScreen";

interface OpportunityPermission {
  menuId: number;
  reg: boolean;
  mdfcn: boolean;
  del: boolean;
  dwnld: boolean;
  cnfrm: boolean;
}
export function useOpportunityPermissions() {
  const [actions, setActions] = useState<Partial<OpportunityPermission>>({});
  useEffect(() => {
    let current = true,
      generation = 0;
    const load = async () => {
      const version = ++generation;
      setActions({});
      try {
        const data = await readData<{
          menus: MenuNode[];
          permissions: OpportunityPermission[];
        }>("/api/me/permissions");
        const flatten = (menus: MenuNode[]): MenuNode[] =>
          menus.flatMap((menu) => [menu, ...flatten(menu.children ?? [])]);
        const ids = flatten(data.menus)
          .filter((menu) => menu.screenId === "UI-29")
          .map((menu) => menu.menuId);
        const grants = data.permissions.filter((permission) =>
          ids.includes(permission.menuId),
        );
        if (current && version === generation)
          setActions({
            reg: grants.some((grant) => grant.reg),
            mdfcn: grants.some((grant) => grant.mdfcn),
            del: grants.some((grant) => grant.del),
            dwnld: grants.some((grant) => grant.dwnld),
            cnfrm: grants.some((grant) => grant.cnfrm),
          });
      } catch {
        if (current && version === generation) setActions({});
      }
    };
    const refresh = () => {
      void load();
    };
    const clear = () => {
      generation++;
      setActions({});
    };
    refresh();
    window.addEventListener(notificationPermissionEvent, refresh);
    window.addEventListener(notificationSessionEvent, clear);
    return () => {
      current = false;
      generation++;
      window.removeEventListener(notificationPermissionEvent, refresh);
      window.removeEventListener(notificationSessionEvent, clear);
    };
  }, []);
  return actions;
}
