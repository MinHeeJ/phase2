import {
  useEffect,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { LoaderCircle, X } from "lucide-react";
import styles from "./UiKit.module.css";

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "text" | "danger";
  small?: boolean;
  pending?: boolean;
};
export function Button({
  variant = "secondary",
  small,
  pending,
  children,
  disabled,
  className = "",
  onClick,
  ...props
}: ButtonProps) {
  const inFlight = useRef(false);
  useEffect(() => {
    if (!pending) inFlight.current = false;
  }, [pending]);
  return (
    <button
      {...props}
      type={props.type ?? "button"}
      className={`${styles.button} ${styles[variant]} ${small ? styles.small : ""} ${className}`}
      disabled={disabled || pending}
      aria-busy={pending || undefined}
      onClick={(event) => {
        if (inFlight.current) return;
        inFlight.current = true;
        try {
          onClick?.(event);
        } finally {
          queueMicrotask(() => {
            if (!pending) inFlight.current = false;
          });
        }
      }}
    >
      {pending && (
        <LoaderCircle
          className={styles.spinner}
          aria-label="처리 중"
          size={16}
        />
      )}
      {children}
    </button>
  );
}
export function Actions({ children }: { children: ReactNode }) {
  return (
    <div
      data-testid={`actions-${Math.random().toString(36).slice(2)}`}
      className={styles.actions}
    >
      {children}
    </div>
  );
}
export type Column<T> = {
  key: string;
  title: string;
  kind?: "text" | "number" | "code" | "status" | "date";
  sensitive?: boolean;
  render: (row: T) => ReactNode;
};
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  total,
  dense = false,
  onDenseChange,
  loading = false,
  unavailable = false,
  emptyMessage = "조회 결과가 없습니다. 조건을 바꿔 다시 조회하세요.",
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  total: number;
  dense?: boolean;
  onDenseChange?: (dense: boolean) => void;
  loading?: boolean;
  unavailable?: boolean;
  emptyMessage?: string;
}) {
  return (
    <section
      data-testid={`data-table-${Math.random().toString(36).slice(2)}`}
      aria-busy={loading || undefined}
      className={styles.tablePanel}
    >
      <div className={styles.toolbar}>
        <span>
          {loading
            ? "목록을 불러오는 중"
            : unavailable
              ? "목록을 확인할 수 없습니다"
              : `총 ${total.toLocaleString("ko-KR")}건`}
        </span>
        {onDenseChange && (
          <label>
            <input
              data-testid={`table-density-toggle-${Math.random().toString(36).slice(2)}`}
              type="checkbox"
              checked={dense}
              onChange={(e) => onDenseChange(e.target.checked)}
            />
            좁게 보기
          </label>
        )}
      </div>
      <div className={styles.tableScroll}>
        <table className={`${styles.table} ${dense ? styles.dense : ""}`}>
          <thead>
            <tr>
              {columns.map((column) => (
                <th
                  data-testid={`table-column-${Math.random().toString(36).slice(2)}`}
                  key={column.key}
                  scope="col"
                  className={styles[column.kind ?? "text"]}
                >
                  {column.title}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.length ? (
              rows.map((row) => (
                <tr
                  data-testid={`table-row-${Math.random().toString(36).slice(2)}`}
                  key={rowKey(row)}
                >
                  {columns.map((column) => (
                    <td
                      data-testid={`table-cell-${Math.random().toString(36).slice(2)}`}
                      key={column.key}
                      className={styles[column.kind ?? "text"]}
                      data-sensitive={column.sensitive || undefined}
                    >
                      {column.render(row)}
                    </td>
                  ))}
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={columns.length} className={styles.empty}>
                  {loading ? (
                    <Skeleton label="목록 불러오는 중" />
                  ) : unavailable ? (
                    "조회 오류를 확인한 뒤 다시 시도해 주세요."
                  ) : (
                    emptyMessage
                  )}
                </td>
              </tr>
            )}
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
  const last = Math.max(1, Math.ceil(total / size));
  const start = Math.floor((page - 1) / 10) * 10 + 1;
  const pages = Array.from(
    { length: Math.min(10, last - start + 1) },
    (_, i) => start + i,
  );
  return (
    <nav
      data-testid={`pagination-${Math.random().toString(36).slice(2)}`}
      className={styles.pagination}
      aria-label="목록 페이지"
    >
      <span>
        {total === 0 ? 0 : (page - 1) * size + 1}–{Math.min(page * size, total)}{" "}
        / {total}건
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
      {pages.map((number) => (
        <button
          type="button"
          key={number}
          aria-current={number === page ? "page" : undefined}
          onClick={() => onPage(number)}
        >
          {number}
        </button>
      ))}
      <button
        type="button"
        aria-label="다음 페이지"
        disabled={page >= last}
        onClick={() => onPage(page + 1)}
      >
        ›
      </button>
      <button
        type="button"
        aria-label="마지막 페이지"
        disabled={page >= last}
        onClick={() => onPage(last)}
      >
        »
      </button>
      <select
        aria-label="목록 건수"
        value={size}
        onChange={(e) => onSize(Number(e.target.value) as 20 | 50 | 100)}
      >
        {[20, 50, 100].map((value) => (
          <option key={value} value={value}>
            {value}건
          </option>
        ))}
      </select>
    </nav>
  );
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
  if (items.length > 5) throw new RangeError("하위 탭은 최대 5개입니다.");
  return (
    <div className={styles.tabs} role="tablist">
      {items.map((item) => (
        <button
          type="button"
          role="tab"
          aria-selected={item.key === value}
          key={item.key}
          onClick={() => onChange(item.key)}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}
export function Badge({
  tone = "locked",
  children,
  category = false,
}: {
  tone?: "success" | "warning" | "danger" | "info" | "locked";
  children: ReactNode;
  category?: boolean;
}) {
  return (
    <span
      className={`${styles.badge} ${category ? styles.category : styles[tone]}`}
    >
      {children}
    </span>
  );
}
export function QueryBar({
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
      className={styles.query}
      onSubmit={(event) => {
        event.preventDefault();
        onSearch();
      }}
    >
      {children}
      <Actions>
        <Button onClick={onReset}>초기화</Button>
        <Button type="submit" variant="primary">
          조회
        </Button>
      </Actions>
    </form>
  );
}
export function Modal({
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
  const ref = useRef<HTMLElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    ref.current?.focus();
    return () => previous?.focus();
  }, []);
  return createPortal(
    <div className={styles.overlay}>
      <section
        ref={ref}
        tabIndex={-1}
        className={`${styles.dialog} ${confirm ? styles.confirm : ""}`}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.preventDefault();
            onClose();
          }
          if (event.key === "Tab") {
            const elements = ref.current?.querySelectorAll<HTMLElement>(
              "button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [href]",
            );
            if (!elements?.length) {
              event.preventDefault();
              return;
            }
            const first = elements[0],
              last = elements[elements.length - 1];
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
        }}
      >
        <header>
          <h2>{title}</h2>
          <Button aria-label="닫기" variant="text" onClick={onClose}>
            <X size={20} />
          </Button>
        </header>
        {children}
      </section>
    </div>,
    document.body,
  );
}
export function ConfirmDialog({
  message,
  onCancel,
  onConfirm,
  destructive = false,
  confirmLabel = "확인",
  cancelLabel = "취소",
}: {
  message: string;
  onCancel: () => void;
  onConfirm: () => void;
  destructive?: boolean;
  confirmLabel?: string;
  cancelLabel?: string;
}) {
  return (
    <Modal title="확인" onClose={onCancel} confirm>
      <p>{message}</p>
      <Actions>
        <Button onClick={onCancel}>{cancelLabel}</Button>
        <Button
          variant={destructive ? "danger" : "primary"}
          onClick={onConfirm}
        >
          {confirmLabel}
        </Button>
      </Actions>
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
    <div
      className={styles.skeleton}
      role="status"
      aria-label={label}
      aria-busy="true"
    />
  );
}
export function EmptyState({
  reason,
  action,
  onAction,
}: {
  reason: string;
  action: string;
  onAction: () => void;
}) {
  return (
    <div className={styles.empty}>
      <p>{reason}</p>
      <Button onClick={onAction}>{action}</Button>
    </div>
  );
}
