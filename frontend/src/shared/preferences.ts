import { create } from "zustand";

export interface Preferences {
  pageSize: 20 | 50 | 100;
  dense: boolean;
  collapsed: boolean;
}
function readPreferences(): Preferences {
  try {
    const saved = JSON.parse(
      localStorage.getItem("si.preferences") ?? "{}",
    ) as Partial<Preferences>;
    return {
      pageSize: [20, 50, 100].includes(saved.pageSize ?? 0)
        ? saved.pageSize!
        : 50,
      dense: saved.dense === true,
      collapsed: saved.collapsed === true,
    };
  } catch {
    return { pageSize: 50, dense: false, collapsed: false };
  }
}
export const usePreferences = create<
  Preferences & { update: (value: Partial<Preferences>) => void }
>((set) => ({
  ...readPreferences(),
  update: (value) =>
    set((current) => {
      const next = { ...current, ...value };
      try {
        localStorage.setItem(
          "si.preferences",
          JSON.stringify({
            pageSize: next.pageSize,
            dense: next.dense,
            collapsed: next.collapsed,
          }),
        );
      } catch {
        /* Storage may be unavailable; current-session preferences remain usable. */
      }
      return next;
    }),
}));
