import { create } from "zustand";

function read<T>(key: string, fallback: T): T {
  try {
    const value = localStorage.getItem(`si.${key}`);
    return value == null ? fallback : (JSON.parse(value) as T);
  } catch {
    return fallback;
  }
}
const ids = (value: unknown): string[] =>
  Array.isArray(value)
    ? value.filter(
        (id): id is string => typeof id === "string" && /^UI-\d{2}$/.test(id),
      )
    : [];
type SharedPreferences = {
  favorites: string[];
  recent: string[];
  collapsed: boolean;
  pageSize: 20 | 50 | 100;
  dense: boolean;
  toggleFavorite: (id: string) => boolean;
  moveFavorite: (id: string, before: string) => void;
  visit: (id: string) => void;
  setCollapsed: (value: boolean) => void;
  setPageSize: (value: 20 | 50 | 100) => void;
  setDense: (value: boolean) => void;
};
function save(key: string, value: unknown) {
  try {
    localStorage.setItem(`si.${key}`, JSON.stringify(value));
  } catch {
    /* Preferences remain available in memory. */
  }
}
const storedSize = read<number>("pageSize", 50);
export const useSharedPreferences = create<SharedPreferences>((set, get) => ({
  favorites: ids(read("favorites", [])).slice(0, 10),
  recent: ids(read("recent", [])).slice(0, 5),
  collapsed: read<boolean>("collapsed", false) === true,
  pageSize: storedSize === 20 || storedSize === 100 ? storedSize : 50,
  dense: read<boolean>("dense", false) === true,
  toggleFavorite: (id) => {
    const current = get().favorites;
    if (!current.includes(id) && current.length >= 10) return false;
    const favorites = current.includes(id)
      ? current.filter((value) => value !== id)
      : [...current, id];
    save("favorites", favorites);
    set({ favorites });
    return true;
  },
  moveFavorite: (id, before) => {
    const current = get().favorites;
    if (id === before || !current.includes(id) || !current.includes(before))
      return;
    const favorites = current.filter((value) => value !== id);
    favorites.splice(favorites.indexOf(before), 0, id);
    save("favorites", favorites);
    set({ favorites });
  },
  visit: (id) => {
    const recent = [id, ...get().recent.filter((value) => value !== id)].slice(
      0,
      5,
    );
    save("recent", recent);
    set({ recent });
  },
  setCollapsed: (collapsed) => {
    save("collapsed", collapsed);
    set({ collapsed });
  },
  setPageSize: (pageSize) => {
    save("pageSize", pageSize);
    set({ pageSize });
  },
  setDense: (dense) => {
    save("dense", dense);
    set({ dense });
  },
}));
