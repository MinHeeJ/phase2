import { useCallback, useEffect, useRef, useState } from "react";
import { ApiFailure } from "../../shared/api";
import { failure } from "./client";

export function useAdminRead<T>(read: () => Promise<T>) {
  const [data, setData] = useState<T | null>(null),
    [error, setError] = useState<ApiFailure | null>(null),
    [loading, setLoading] = useState(true);
  const serial = useRef(0);
  const reload = useCallback(async () => {
    const request = ++serial.current;
    setLoading(true);
    setError(null);
    try {
      const result = await read();
      if (request === serial.current) setData(result);
      return result;
    } catch (e) {
      if (request === serial.current) setError(failure(e));
      throw e;
    } finally {
      if (request === serial.current) setLoading(false);
    }
  }, [read]);
  useEffect(() => {
    void reload().catch(() => {});
    return () => {
      serial.current++;
    };
  }, [reload]);
  return { data, error, loading, reload };
}
export function useAdminWrite(requery: () => Promise<unknown>) {
  const [pending, setPending] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null),
    [uncertain, setUncertain] = useState(false);
  const busy = useRef(false);
  const save = async (write: () => Promise<unknown>, success: () => void) => {
    if (busy.current || uncertain) return;
    busy.current = true;
    setPending(true);
    setError(null);
    let committed = false;
    try {
      await write();
      committed = true;
      await requery();
      success();
    } catch (e) {
      const original = failure(e);
      const err = committed
        ? new ApiFailure(
            "저장 후 조회에 실패했습니다. 현재 상태를 확인하세요.",
            original.status,
            original.requestId,
            original.field,
            original.code,
            true,
          )
        : original;
      setError(err);
      if (err.uncertainWrite) setUncertain(true);
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  const recover = async () => {
    if (busy.current) return;
    busy.current = true;
    setPending(true);
    try {
      await requery();
      setUncertain(false);
      setError(null);
    } catch (e) {
      setError(failure(e));
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  return {
    pending,
    error,
    uncertain,
    save,
    recover,
    clear: () => setError(null),
  };
}
