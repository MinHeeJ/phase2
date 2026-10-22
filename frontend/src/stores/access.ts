import { create } from "zustand";
import type { DataScope } from "../services/types";

type AccessState = { scope: DataScope; can: (key: string) => boolean };
export const useAccess = create<AccessState>(() => ({
  scope: "ALL",
  can: () => true,
}));
