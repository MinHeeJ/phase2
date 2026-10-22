import { useCallback, useEffect, useState } from "react";
import { ApiFailure } from "../../shared/api";
import { getMenus, type MenuNode } from "./api";

export function useMenus() {
  const [menus, setMenus] = useState<MenuNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [revision, setRevision] = useState(0);
  const retry = useCallback(() => setRevision((value) => value + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    getMenus(controller.signal)
      .then((rows) => {
        if (!controller.signal.aborted) setMenus(rows);
      })
      .catch((failure: ApiFailure) => {
        if (!controller.signal.aborted) setError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [revision]);
  return { menus, loading, error, retry };
}
