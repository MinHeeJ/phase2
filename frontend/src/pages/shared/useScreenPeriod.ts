import { useCallback, useState, useSyncExternalStore } from "react";

const remembered = new Map<string, string>();
const listeners = new Set<() => void>();

export function seoulMonth(now = new Date()): string {
  const parts = new Intl.DateTimeFormat("en", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
  }).formatToParts(now);
  return `${parts.find((part) => part.type === "year")!.value}-${parts.find((part) => part.type === "month")!.value}`;
}

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

// PeriodBar and the business query subscribe to the same per-screen period.
export function useScreenPeriod(screenId: string) {
  const [initial] = useState(() => seoulMonth());
  const snapshot = useCallback(
    () => remembered.get(screenId) ?? initial,
    [screenId, initial],
  );
  const period = useSyncExternalStore(subscribe, snapshot, snapshot);
  const setPeriod = useCallback(
    (value: string) => {
      if (!/^(?!0000)[0-9]{4}-(0[1-9]|1[0-2])$/.test(value)) return;
      remembered.set(screenId, value);
      listeners.forEach((listener) => listener());
    },
    [screenId],
  );
  return [period, setPeriod] as const;
}
