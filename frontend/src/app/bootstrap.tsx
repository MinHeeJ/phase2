import { createRoot } from "react-dom/client";
import type { ReactNode } from "react";
import "./tokens.css";

export function mountApplication(application: ReactNode) {
  const element = document.getElementById("root");
  if (!element) throw new Error("앱 표시 영역을 찾을 수 없습니다.");
  createRoot(element).render(application);
}
