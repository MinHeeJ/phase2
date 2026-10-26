import { useRef, useState } from "react";
import { ApiFailure } from "../../shared/http";
import { focusField } from "../../shared/inputs";

export function useAdminMutation() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const lock = useRef(false);
  async function run(
    action: () => Promise<void>,
    form?: HTMLFormElement | null,
  ) {
    if (lock.current) return false;
    lock.current = true;
    setPending(true);
    setError(null);
    try {
      await action();
      return true;
    } catch (failure) {
      const error =
        failure instanceof Error
          ? failure
          : new Error("처리 중 오류가 발생했습니다.");
      setError(error);
      if (form && error instanceof ApiFailure)
        setTimeout(() => focusField(form, error.field), 0);
      return false;
    } finally {
      lock.current = false;
      setPending(false);
    }
  }
  const fieldError = (name: string) =>
    error instanceof ApiFailure && error.field === name
      ? error.message
      : undefined;
  return { run, pending, error, fieldError, clear: () => setError(null) };
}
