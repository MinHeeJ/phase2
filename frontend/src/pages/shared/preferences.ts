import { create } from "zustand";

interface Preferences {
  favorites: string[];
  recent: string[];
  collapsed: boolean;
  pageSize: 20 | 50 | 100;
  dense: boolean;
  toggleFavorite: (screenId: string) => boolean;
  moveFavorite: (screenId: string, before: string) => void;
  visit: (screenId: string) => void;
  configure: (
    value: Partial<Pick<Preferences, "collapsed" | "pageSize" | "dense">>,
  ) => void;
}
function read(key: string): unknown {
  try {
    return JSON.parse(localStorage.getItem(`si.${key}`) ?? "null");
  } catch {
    return null;
  }
}
function ids(value: unknown, limit: number): string[] {
  return Array.isArray(value)
    ? [
        ...new Set(
          value.filter(
            (id): id is string =>
              typeof id === "string" && /^UI-[0-9]{2}$/.test(id),
          ),
        ),
      ].slice(0, limit)
    : [];
}
function save(key: string, value: unknown) {
  try {
    localStorage.setItem(`si.${key}`, JSON.stringify(value));
  } catch {
    /* Preferences remain usable in memory. */
  }
}
export const useSharedPreferences = create<Preferences>((set, get) => ({
  favorites: ids(read("favorites"), 10),
  recent: ids(read("recent"), 5),
  collapsed: read("collapsed") === true,
  pageSize: [20, 50, 100].includes(Number(read("pageSize")))
    ? (Number(read("pageSize")) as 20 | 50 | 100)
    : 50,
  dense: read("dense") === true,
  toggleFavorite: (screenId) => {
    const old = get().favorites;
    if (!old.includes(screenId) && old.length >= 10) return false;
    const favorites = old.includes(screenId)
      ? old.filter((id) => id !== screenId)
      : [...old, screenId];
    save("favorites", favorites);
    set({ favorites });
    return true;
  },
  moveFavorite: (screenId, before) => {
    const old = get().favorites;
    if (screenId === before || !old.includes(screenId) || !old.includes(before))
      return;
    const favorites = old.filter((id) => id !== screenId);
    favorites.splice(favorites.indexOf(before), 0, screenId);
    save("favorites", favorites);
    set({ favorites });
  },
  visit: (screenId) => {
    const recent = [
      screenId,
      ...get().recent.filter((id) => id !== screenId),
    ].slice(0, 5);
    save("recent", recent);
    set({ recent });
  },
  configure: (value) => {
    for (const [key, setting] of Object.entries(value)) save(key, setting);
    set(value);
  },
}));
