import { useEffect, useState } from "react";
import { ApiFailure } from "../../shared/api";

export function usePartnerRead<T>(
  key: string,
  read: (signal: AbortSignal) => Promise<T>,
) {
  const [data, setData] = useState<T | null>(null),
    [error, setError] = useState<ApiFailure | null>(null),
    [loading, setLoading] = useState(true),
    [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setData(null);
    read(controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setData(value);
      })
      .catch((e) => {
        if (!controller.signal.aborted)
          setError(
            e instanceof ApiFailure
              ? e
              : new ApiFailure("요청을 처리할 수 없습니다.", null, null),
          );
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
    // key describes every parameter; the caller's read closure is intentionally not a dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, revision]);
  return { data, error, loading, retry: () => setRevision((r) => r + 1) };
}
