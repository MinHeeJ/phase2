import { useEffect, useState } from "react";

export function useDeveloperReadOnly() {
  const [readOnly, setReadOnly] = useState(() => window.innerWidth <= 767);
  useEffect(() => {
    const resize = () => setReadOnly(window.innerWidth <= 767);
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);
  return readOnly;
}
