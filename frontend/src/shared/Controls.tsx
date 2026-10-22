import {
  useEffect,
  useId,
  useRef,
  type ButtonHTMLAttributes,
  type FormEvent,
  type ReactNode,
} from "react";
import { LoaderCircle } from "lucide-react";
import styles from "./Controls.module.css";

export function Button({
  pending = false,
  variant = "secondary",
  small = false,
  children,
  onClick,
  disabled,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  pending?: boolean;
  variant?: "primary" | "secondary" | "text";
  small?: boolean;
}) {
  const guard = useRef(false);
  useEffect(() => {
    if (!pending) guard.current = false;
  }, [pending]);
  return (
    <button
      {...props}
      disabled={disabled || pending}
      aria-busy={pending}
      className={`${styles.button} ${styles[variant]} ${small ? styles.small : ""} ${props.className ?? ""}`}
      onClick={(event) => {
        if (guard.current || pending) return;
        guard.current = true;
        onClick?.(event);
        queueMicrotask(() => {
          guard.current = false;
        });
      }}
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
export function ActionBar({
  children,
  primary,
}: {
  children?: ReactNode;
  primary: ReactNode;
}) {
  return (
    <div className={styles.actions}>
      {children}
      <span className={styles.primaryAction}>{primary}</span>
    </div>
  );
}
export function Badge({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: "success" | "warning" | "danger" | "info" | "locked" | "neutral";
}) {
  return <span className={`${styles.badge} ${styles[tone]}`}>{children}</span>;
}
export function Tabs({
  items,
  selected,
  onChange,
}: {
  items: readonly { key: string; label: string }[];
  selected: string;
  onChange: (key: string) => void;
}) {
  if (items.length > 5) throw new RangeError("하위 탭은 최대 5개입니다.");
  return (
    <div role="tablist" className={styles.tabs}>
      {items.map((item) => (
        <button
          type="button"
          role="tab"
          key={item.key}
          aria-selected={item.key === selected}
          onClick={() => onChange(item.key)}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}
export interface Column<T> {
  key: string;
  label: string;
  kind?: "number" | "text" | "code" | "status" | "date";
  sensitive?: boolean;
  render: (row: T) => ReactNode;
}
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  total,
  dense = false,
  onDenseChange,
  showEmpty = true,
}: {
  columns: readonly Column<T>[];
  rows: readonly T[];
  rowKey: (row: T) => string;
  total: number;
  dense?: boolean;
  onDenseChange?: (dense: boolean) => void;
  showEmpty?: boolean;
}) {
  return (
    <section className={styles.tableRegion}>
      <div className={styles.toolbar}>
        <span>총 {total.toLocaleString("ko-KR")}건</span>
        {onDenseChange && (
          <label>
            <input
              type="checkbox"
              checked={dense}
              onChange={(e) => onDenseChange(e.target.checked)}
            />
            좁게 보기
          </label>
        )}
      </div>
      <div className={styles.tableScroll}>
        <table className={dense ? styles.dense : ""}>
          <thead>
            <tr>
              {columns.map((c) => (
                <th key={c.key} className={styles[c.kind ?? "text"]}>
                  {c.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {showEmpty && rows.length === 0 && columns.length > 0 && (
              <tr>
                <td className={styles.emptyCell} colSpan={columns.length}>
                  조건에 맞는 결과가 없습니다
                </td>
              </tr>
            )}
            {rows.map((row) => (
              <tr key={rowKey(row)}>
                {columns.map((c) => (
                  <td
                    key={c.key}
                    className={styles[c.kind ?? "text"]}
                    data-sensitive={c.sensitive || undefined}
                  >
                    {c.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
export function Pagination({
  page,
  size,
  total,
  onPage,
  onSize,
}: {
  page: number;
  size: 20 | 50 | 100;
  total: number;
  onPage: (page: number) => void;
  onSize: (size: 20 | 50 | 100) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  const first = Math.floor((page - 1) / 10) * 10 + 1;
  const numbers = Array.from(
    { length: Math.max(0, Math.min(10, pages - first + 1)) },
    (_, i) => first + i,
  );
  return (
    <nav aria-label="페이지" className={styles.pagination}>
      <span>
        {total ? (page - 1) * size + 1 : 0}~{Math.min(page * size, total)} /
        전체 {total}건
      </span>
      <button
        type="button"
        aria-label="첫 페이지"
        disabled={page <= 1}
        onClick={() => onPage(1)}
      >
        «
      </button>
      <button
        type="button"
        aria-label="이전 페이지"
        disabled={page <= 1}
        onClick={() => onPage(page - 1)}
      >
        ‹
      </button>
      {numbers.map((n) => (
        <button
          type="button"
          key={n}
          aria-current={n === page ? "page" : undefined}
          onClick={() => onPage(n)}
        >
          {n}
        </button>
      ))}
      <button
        type="button"
        aria-label="다음 페이지"
        disabled={page >= pages}
        onClick={() => onPage(page + 1)}
      >
        ›
      </button>
      <button
        type="button"
        aria-label="마지막 페이지"
        disabled={page >= pages}
        onClick={() => onPage(pages)}
      >
        »
      </button>
      <select
        aria-label="목록 건수"
        value={size}
        onChange={(e) => onSize(Number(e.target.value) as 20 | 50 | 100)}
      >
        {[20, 50, 100].map((n) => (
          <option key={n} value={n}>
            {n}건
          </option>
        ))}
      </select>
    </nav>
  );
}
export function SearchBar({
  children,
  onSearch,
  onReset,
}: {
  children: ReactNode;
  onSearch: () => void;
  onReset: () => void;
}) {
  const search = (event: FormEvent) => {
    event.preventDefault();
    onSearch();
  };
  return (
    <form className={styles.search} onSubmit={search}>
      {children}
      <Button type="button" onClick={onReset}>
        초기화
      </Button>
      <Button type="submit" variant="primary">
        조회
      </Button>
    </form>
  );
}
export function Modal({
  title,
  children,
  onCancel,
  confirmation = false,
}: {
  title: string;
  children: ReactNode;
  onCancel: () => void;
  confirmation?: boolean;
}) {
  const id = useId();
  const container = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    container.current?.focus();
    return () => previous?.focus();
  }, []);
  return (
    <div className={styles.backdrop}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={id}
        tabIndex={-1}
        ref={container}
        className={`${styles.modal} ${confirmation ? styles.confirmation : ""}`}
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.preventDefault();
            onCancel();
          }
          if (event.key === "Tab") {
            const focusable = Array.from(
              container.current?.querySelectorAll<HTMLElement>(
                'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]',
              ) ?? [],
            );
            if (!focusable.length) {
              event.preventDefault();
              return;
            }
            const first = focusable[0],
              last = focusable[focusable.length - 1];
            if (
              event.shiftKey &&
              (document.activeElement === first ||
                document.activeElement === container.current)
            ) {
              event.preventDefault();
              last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
              event.preventDefault();
              first.focus();
            }
          }
        }}
      >
        <h2 id={id}>{title}</h2>
        {children}
      </div>
    </div>
  );
}
export function ConfirmDialog({
  title,
  children,
  onConfirm,
  onCancel,
  destructive = false,
  confirmLabel = "확인",
  cancelLabel = "취소",
}: {
  title: string;
  children: ReactNode;
  onConfirm: () => void;
  onCancel: () => void;
  destructive?: boolean;
  confirmLabel?: string;
  cancelLabel?: string;
}) {
  return (
    <Modal title={title} confirmation onCancel={onCancel}>
      <p>{children}</p>
      <div className={styles.actions}>
        <Button onClick={onCancel}>{cancelLabel}</Button>
        <button
          type="button"
          className={`${styles.button} ${destructive ? styles.destructive : styles.primary}`}
          onClick={onConfirm}
        >
          {confirmLabel}
        </button>
      </div>
    </Modal>
  );
}
export function SuccessToast({
  message,
  onDismiss,
}: {
  message: string;
  onDismiss: () => void;
}) {
  useEffect(() => {
    const timer = setTimeout(onDismiss, 3000);
    return () => clearTimeout(timer);
  }, [message, onDismiss]);
  return (
    <div role="status" className={styles.toast}>
      {message}
    </div>
  );
}
export function LockedBanner({ children }: { children: ReactNode }) {
  return <div className={styles.lockedBanner}>{children}</div>;
}
export function Skeleton({ label = "불러오는 중" }: { label?: string }) {
  return (
    <div role="status" aria-label={label} className={styles.skeleton}>
      <span>{label}</span>
      <i aria-hidden="true" />
      <i aria-hidden="true" />
      <i aria-hidden="true" />
    </div>
  );
}
export function EmptyState({
  reason,
  actionLabel,
  onAction,
}: {
  reason: string;
  actionLabel: string;
  onAction: () => void;
}) {
  return (
    <div className={styles.empty}>
      <p>{reason}</p>
      <Button onClick={onAction}>{actionLabel}</Button>
    </div>
  );
}
