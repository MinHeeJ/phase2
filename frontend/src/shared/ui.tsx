import {
  useEffect,
  useId,
  useRef,
  type ButtonHTMLAttributes,
  type ReactNode,
} from "react";
import { Inbox, LoaderCircle, X } from "lucide-react";
import styles from "./ui.module.css";

export function Button({
  pending = false,
  primary = false,
  danger = false,
  small = false,
  children,
  disabled,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  pending?: boolean;
  primary?: boolean;
  danger?: boolean;
  small?: boolean;
}) {
  return (
    <button
      {...props}
      type={props.type ?? "button"}
      disabled={disabled || pending}
      aria-busy={pending}
      className={[
        styles.button,
        primary ? styles.primary : "",
        danger ? styles.dangerButton : "",
        small ? styles.small : "",
        props.className ?? "",
      ].join(" ")}
    >
      {pending && (
        <LoaderCircle
          aria-label="처리 중"
          className={styles.spinner}
          size={16}
        />
      )}
      {children}
    </button>
  );
}
export function Actions({ children }: { children: ReactNode }) {
  return <div className={styles.actions}>{children}</div>;
}
export function Dialog({
  title,
  children,
  onClose,
  confirm = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  confirm?: boolean;
}) {
  const titleId = useId();
  const ref = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    ref.current?.focus();
    function key(event: KeyboardEvent) {
      if (event.key === "Escape") close.current();
      if (event.key === "Tab") {
        const nodes = ref.current?.querySelectorAll<HTMLElement>(
          'a[href],button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex="0"]',
        );
        if (!nodes?.length) {
          event.preventDefault();
          return;
        }
        const first = nodes[0],
          last = nodes[nodes.length - 1];
        if (
          event.shiftKey &&
          (document.activeElement === first ||
            document.activeElement === ref.current)
        ) {
          event.preventDefault();
          last.focus();
        } else if (
          !event.shiftKey &&
          (document.activeElement === last ||
            document.activeElement === ref.current)
        ) {
          event.preventDefault();
          first.focus();
        }
      }
    }
    document.addEventListener("keydown", key);
    return () => {
      document.removeEventListener("keydown", key);
      previous?.focus();
    };
  }, []);
  return (
    <div className={styles.overlay}>
      <div
        ref={ref}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={`${styles.dialog} ${confirm ? styles.confirm : ""}`}
      >
        <header className={styles.dialogHeader}>
          <h2 id={titleId}>{title}</h2>
          <Button aria-label="닫기" onClick={onClose}>
            <X size={16} />
          </Button>
        </header>
        {children}
      </div>
    </div>
  );
}
export function ConfirmDialog({
  title,
  children,
  onConfirm,
  onClose,
  destructive = false,
  pending = false,
}: {
  title: string;
  children: ReactNode;
  onConfirm: () => void;
  onClose: () => void;
  destructive?: boolean;
  pending?: boolean;
}) {
  return (
    <Dialog title={title} onClose={onClose} confirm>
      {children}
      <Actions>
        <Button onClick={onClose} disabled={pending}>
          취소
        </Button>
        <Button
          onClick={onConfirm}
          pending={pending}
          primary={!destructive}
          danger={destructive}
        >
          확인
        </Button>
      </Actions>
    </Dialog>
  );
}
export function SuccessToast({
  message,
  onClose,
}: {
  message: string;
  onClose: () => void;
}) {
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const timer = setTimeout(() => close.current(), 3000);
    return () => clearTimeout(timer);
  }, [message]);
  return (
    <div role="status" className={styles.toast}>
      {message}
    </div>
  );
}
export function Notice({ children }: { children: ReactNode }) {
  return <div className={styles.notice}>{children}</div>;
}
export function EmptyState({
  reason,
  nextAction,
}: {
  reason: string;
  nextAction: ReactNode;
}) {
  return (
    <div className={styles.empty}>
      <Inbox size={32} aria-hidden="true" />
      <p>{reason}</p>
      <div>{nextAction}</div>
    </div>
  );
}
export function Skeleton({ label = "불러오는 중" }: { label?: string }) {
  return <div role="status" aria-label={label} className={styles.skeleton} />;
}
export function Badge({
  children,
  tone = "info",
}: {
  children: ReactNode;
  tone?: "success" | "warning" | "danger" | "info" | "locked";
}) {
  return <span className={`${styles.badge} ${styles[tone]}`}>{children}</span>;
}
export function Tabs({
  items,
  value,
  onChange,
}: {
  items: { key: string; label: string }[];
  value: string;
  onChange: (key: string) => void;
}) {
  return (
    <div role="tablist" className={styles.tabs}>
      {items.slice(0, 5).map((item) => (
        <button
          type="button"
          role="tab"
          key={item.key}
          aria-selected={item.key === value}
          onClick={() => onChange(item.key)}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}
export function SearchForm({
  children,
  onSearch,
  onReset,
}: {
  children: ReactNode;
  onSearch: () => void;
  onReset: () => void;
}) {
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        onSearch();
      }}
      className={styles.search}
    >
      {children}
      <Actions>
        <Button onClick={onReset}>초기화</Button>
        <Button type="submit" primary>
          조회
        </Button>
      </Actions>
    </form>
  );
}
