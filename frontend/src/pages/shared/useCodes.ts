import { useCallback, useEffect, useState } from "react";
import { ApiFailure } from "../../shared/api";
import { getCodeOptions, type CodeOption } from "./api";

export function useCodes(group: string | null | undefined) {
  const [codes, setCodes] = useState<CodeOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [revision, setRevision] = useState(0);
  const retry = useCallback(() => setRevision((value) => value + 1), []);
  useEffect(() => {
    if (!group) {
      setCodes([]);
      setError(null);
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setCodes([]);
    setError(null);
    getCodeOptions(group, controller.signal)
      .then((rows) => {
        if (!controller.signal.aborted) setCodes(rows);
      })
      .catch((failure: ApiFailure) => {
        if (!controller.signal.aborted) setError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [group, revision]);
  return { codes, loading, error, retry };
}
