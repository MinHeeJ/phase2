import { useCallback, useEffect, useState } from "react";
import { getCodeOptions, type CodeOption } from "./api";

export function useCodes(group: string | null | undefined) {
  const [codes, setCodes] = useState<CodeOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [revision, setRevision] = useState(0);
  const retry = useCallback(() => setRevision((value) => value + 1), []);
  useEffect(() => {
    let active = true;
    setCodes([]);
    setError(null);
    if (!group) {
      setLoading(false);
      return;
    }
    setLoading(true);
    getCodeOptions(group)
      .then((value) => {
        if (active) setCodes(value);
      })
      .catch((reason: Error) => {
        if (active) setError(reason);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [group, revision]);
  return { codes, loading, error, retry };
}
