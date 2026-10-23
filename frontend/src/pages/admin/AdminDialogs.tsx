import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { ConfirmDialog, Modal, Button } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";

export function UnsavedDialog({
  onLeave,
  onContinue,
}: {
  onLeave: () => void;
  onContinue: () => void;
}) {
  return (
    <ConfirmDialog
      title="저장하지 않은 내용이 있습니다."
      onConfirm={onLeave}
      onCancel={onContinue}
      confirmLabel="나가기"
      cancelLabel="계속 작성"
    >
      저장하지 않은 내용이 있습니다. 나가면 사라집니다.
    </ConfirmDialog>
  );
}
export function TabOverflowDialog({
  candidate,
  onConfirm,
  onCancel,
}: {
  candidate: string;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <ConfirmDialog
      title="탭 초과"
      onConfirm={onConfirm}
      onCancel={onCancel}
      confirmLabel="닫기 확인"
    >
      가장 오래 안 본 탭을 닫고 새 화면을 여나요? 후보: {candidate}
    </ConfirmDialog>
  );
}
export function ScreenSearchDialog({
  menus,
  onSelect,
  onCancel,
}: {
  menus: readonly { screenId: string; name: string }[];
  onSelect: (id: string) => void;
  onCancel: () => void;
}) {
  const [query, setQuery] = useState("");
  return (
    <Modal title="화면 검색" onCancel={onCancel}>
      <input
        data-testid="screen-search-input"
        aria-label="화면 검색"
        autoFocus
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      <ul>
        {menus
          .filter((m) =>
            `${m.screenId} ${m.name}`
              .toLocaleLowerCase()
              .includes(query.toLocaleLowerCase()),
          )
          .map((m) => (
            <li
              data-testid={`screen-search-item-${m.screenId.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
              key={m.screenId}
            >
              <Button onClick={() => onSelect(m.screenId)}>
                {m.name} {m.screenId}
              </Button>
            </li>
          ))}
      </ul>
      <Button onClick={onCancel}>닫기</Button>
    </Modal>
  );
}
export function useAdminGuard(dirty: boolean) {
  const [leave, setLeave] = useState<(() => void) | null>(null);
  const request = useCallback(
    (action: () => void) => {
      if (dirty) setLeave(() => action);
      else action();
    },
    [dirty],
  );
  useEffect(() => {
    const unload = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", unload);
    return () => window.removeEventListener("beforeunload", unload);
  }, [dirty]);
  return {
    request,
    confirmation: leave ? (
      <UnsavedDialog
        onContinue={() => setLeave(null)}
        onLeave={() => {
          setLeave(null);
          leave();
        }}
      />
    ) : null,
  };
}
export function AdminWorkDialog({
  title,
  children,
  onCancel,
  onSave,
  dirty,
  pending,
  error,
  onRetry,
  blocked = false,
  onDirtyChange,
}: {
  title: string;
  children: ReactNode;
  onCancel: () => void;
  onSave: () => void;
  dirty: boolean;
  pending: boolean;
  error: ApiFailure | null;
  onRetry: () => void;
  blocked?: boolean;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const guard = useAdminGuard(dirty),
    form = useRef<HTMLFormElement>(null);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    if (error?.field && !pending)
      Array.from(form.current?.querySelectorAll<HTMLElement>("[name]") ?? [])
        .find((el) => el.getAttribute("name") === error.field)
        ?.focus();
  }, [error, pending]);
  return (
    <>
      <Modal
        title={title}
        onCancel={() => {
          if (!pending) guard.request(onCancel);
        }}
      >
        <ErrorBanner error={error} onRetry={onRetry} pending={pending} />
        <form
          ref={form}
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            const invalid =
              form.current?.querySelector<HTMLInputElement>(":invalid");
            if (invalid) {
              invalid.dispatchEvent(new Event("invalid", { bubbles: false }));
              invalid.focus();
              return;
            }
            if (!pending && !blocked) onSave();
          }}
        >
          {children}
          <div>
            <Button
              disabled={pending}
              onClick={() => guard.request(onCancel)}
              type="button"
            >
              취소
            </Button>
            <Button
              type="submit"
              variant="primary"
              pending={pending}
              disabled={blocked}
            >
              저장
            </Button>
          </div>
        </form>
      </Modal>
      {guard.confirmation}
    </>
  );
}
