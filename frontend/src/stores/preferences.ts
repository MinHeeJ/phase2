import { create } from "zustand";
import { persist } from "zustand/middleware";
interface Preferences {
  favorites: string[];
  recent: string[];
  collapsed: boolean;
  size: number;
  dense: boolean;
  set: (patch: Partial<Preferences>) => void;
  visit: (id: string) => void;
  star: (id: string) => void;
  reorder: (from: string, to: string) => void;
}
export const usePreferences = create<Preferences>()(
  persist(
    (set, get) => ({
      favorites: [],
      recent: [],
      collapsed: false,
      size: 50,
      dense: false,
      set: (patch) => set(patch),
      visit: (id) =>
        set({
          recent: [id, ...get().recent.filter((x) => x !== id)].slice(0, 5),
        }),
      star: (id) =>
        set({
          favorites: get().favorites.includes(id)
            ? get().favorites.filter((x) => x !== id)
            : get().favorites.length < 10
              ? [...get().favorites, id]
              : get().favorites,
        }),
      reorder: (from, to) => {
        const items = get().favorites.filter((x) => x !== from);
        items.splice(items.indexOf(to), 0, from);
        set({ favorites: items });
      },
    }),
    {
      name: "si.preferences",
      partialize: (s) => ({
        favorites: s.favorites,
        recent: s.recent,
        collapsed: s.collapsed,
        size: s.size,
        dense: s.dense,
      }),
    },
  ),
);
