import { useCallback, useEffect, useState } from "react";
import { query, request } from "../../shared/http";

export interface SharedMenuNode {
  menuId: number;
  name: string;
  screenId: string | null;
  icon: string | null;
  mobile: boolean;
  children: SharedMenuNode[];
}
export interface SharedCodeOption {
  code: string;
  name: string;
  attr1: string | null;
  attr2: string | null;
  attr3: string | null;
}
export interface SharedMonthClose {
  month: string;
  status: "CLOSED" | "OPEN";
}
export interface SharedEffectiveParam {
  oprt_std_id: string;
  aply_bgn_ym: string;
  oprt_std_nm: string;
  num_val: number | null;
  txt_val: string | null;
  unit_nm: string | null;
  use_plc_desc: string | null;
}
export const getMenus = () => query<SharedMenuNode[]>("/api/menus");
export const getCodeOptions = (group: string) =>
  query<SharedCodeOption[]>(`/api/codes/${encodeURIComponent(group)}`);
export const getMonthClose = (from: string, to: string) =>
  query<SharedMonthClose[]>(
    `/api/month-close?${new URLSearchParams({ from, to })}`,
  );
export const getEffectiveParam = (key: string, month: string) =>
  query<SharedEffectiveParam>(
    `/api/params/${encodeURIComponent(key)}?${new URLSearchParams({ month })}`,
  );
export const getHealth = () => request("/api/health");

export function useSharedRead<T>(load: () => Promise<T>, enabled = true) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(enabled);
  const [generation, setGeneration] = useState(0);
  const retry = useCallback(() => setGeneration((value) => value + 1), []);
  useEffect(() => {
    if (!enabled) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    load()
      .then((value) => {
        if (!cancelled) setData(value);
      })
      .catch((failure: unknown) => {
        if (!cancelled)
          setError(
            failure instanceof Error
              ? failure
              : new Error("처리 중 오류가 발생했습니다."),
          );
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [load, generation, enabled]);
  return { data, error, loading, retry };
}
export function useCodes(group: string | null) {
  const load = useCallback(
    async () => ({ group, items: await getCodeOptions(group!) }),
    [group],
  );
  const state = useSharedRead(load, !!group);
  return {
    ...state,
    data: group && state.data?.group === group ? state.data.items : undefined,
  };
}
