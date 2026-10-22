import { useCallback, useEffect, useRef, useState } from "react";
import { ApiRequestError } from "../../services/api";
import { ConfirmDialog, ErrorBanner, Skeleton } from "../../shared";

export function useAdminLoad<T>(
  load: () => Promise<T>,
  dependencies: unknown[] = [],
) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<Error>();
  const [loading, setLoading] = useState(true);
  const loader = useRef(load);
  loader.current = load;
  const generation = useRef(0);
  const reload = useCallback(async () => {
    const current = ++generation.current;
    setLoading(true);
    setError(undefined);
    try {
      const result = await loader.current();
      if (generation.current === current) setData(result);
    } catch (e) {
      if (generation.current === current)
        setError(e instanceof Error ? e : new Error("조회에 실패했습니다."));
    } finally {
      if (generation.current === current) setLoading(false);
    }
  }, []);
  useEffect(() => {
    void reload();
    return () => {
      generation.current++;
    };
  }, dependencies);
  return { data, error, loading, reload };
}
export function AdminLoadState({
  loading,
  error,
  retry,
}: {
  loading: boolean;
  error?: Error;
  retry: () => void;
}) {
  return (
    <>
      {loading && <Skeleton />}
      {error && <ErrorBanner error={error} onRetry={retry} />}
    </>
  );
}
export function useAdminMutation(
  onSuccess: () => Promise<unknown> | void,
  onUncertain: () => Promise<unknown> | void,
) {
  const [pending, setPending] = useState(false);
  const lock = useRef(false);
  const [error, setError] = useState<Error>();
  async function run(write: () => Promise<unknown>) {
    if (lock.current) return;
    lock.current = true;
    setPending(true);
    setError(undefined);
    try {
      await write();
      await onSuccess();
    } catch (e) {
      setError(e instanceof Error ? e : new Error("저장에 실패했습니다."));
      if (
        !(e instanceof ApiRequestError) ||
        e.status === null ||
        e.status >= 500
      ) {
        try {
          await onUncertain();
        } catch {
          /* Keep the original write error and the draft until an explicit retry. */
        }
      }
      if (e instanceof ApiRequestError && e.field)
        requestAnimationFrame(() => {
          const input = Array.from(
            document.querySelectorAll<HTMLElement>("[name]"),
          ).find((element) => element.getAttribute("name") === e.field);
          input?.focus();
        });
    } finally {
      lock.current = false;
      setPending(false);
    }
  }
  return { pending, error, run, clear: () => setError(undefined) };
}
export function adminFieldError(error: Error | undefined, field: string) {
  return error instanceof ApiRequestError && error.field === field
    ? error.message
    : undefined;
}
export function useAdminDiscard(dirty: boolean) {
  const [next, setNext] = useState<(() => void) | null>(null);
  useEffect(() => {
    const prevent = (event: BeforeUnloadEvent) => {
      if (dirty) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", prevent);
    return () => window.removeEventListener("beforeunload", prevent);
  }, [dirty]);
  const request = (action: () => void) => {
    if (dirty) setNext(() => action);
    else action();
  };
  return {
    request,
    dialog: next && (
      <ConfirmDialog
        message="저장하지 않은 내용이 있습니다. 나가면 사라집니다."
        cancelLabel="계속 작성"
        confirmLabel="나가기"
        onCancel={() => setNext(null)}
        onConfirm={() => {
          const action = next;
          setNext(null);
          action();
        }}
      />
    ),
  };
}
