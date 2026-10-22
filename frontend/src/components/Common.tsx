import {
  useEffect,
  useRef,
  useState,
  useId,
  type ReactNode,
  type InputHTMLAttributes,
} from "react";
import { AlertCircle, Inbox, LoaderCircle } from "lucide-react";
import { ApiError } from "../services/api";
import { amount, parseAmount } from "../lib/format";
export function Can({ perm, children }: { perm: string; children: ReactNode }) {
  return <span data-permission={perm}>{children}</span>;
}
export interface Column<T> {
  key: string;
  title: string;
  sensitive?: boolean;
  render: (row: T) => ReactNode;
}
export function ErrorBanner({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  if (!error) return null;
  return (
    <div role="alert" className="error">
      <AlertCircle size={18} aria-hidden="true" />
      <span>
        {error instanceof Error
          ? error.message
          : "처리 중 오류가 발생했습니다."}
        {error instanceof ApiError && error.requestId && (
          <> · 문의 번호 {error.requestId}</>
        )}
      </span>
      {retry && <button onClick={retry}>다시 시도</button>}
    </div>
  );
}
export function Busy({
  children,
  busy = false,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean }) {
  return (
    <button {...props} disabled={props.disabled || busy} aria-busy={busy}>
      {busy && <LoaderCircle className="spinner" size={16} />} {children}
    </button>
  );
}
export function Modal({
  title,
  children,
  onClose,
  small = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  small?: boolean;
}) {
  const ref = useRef<HTMLElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    ref.current?.focus();
    return () => previous?.focus();
  }, []);
  function trapFocus(e: React.KeyboardEvent) {
    if (e.key === "Escape") onClose();
    if (e.key !== "Tab") return;
    const elements = ref.current?.querySelectorAll<HTMLElement>(
      "button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled)",
    );
    if (!elements?.length) return;
    const first = elements[0],
      last = elements[elements.length - 1];
    if (
      e.shiftKey &&
      (document.activeElement === first ||
        document.activeElement === ref.current)
    ) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }
  return (
    <div className="overlay">
      <section
        ref={ref}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={small ? "modal small" : "modal"}
        onKeyDown={trapFocus}
      >
        <h2>{title}</h2>
        {children}
      </section>
    </div>
  );
}
export function TextField({
  label,
  error,
  maxLength,
  required,
  invalidMessage,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & {
  label: string;
  error?: string;
  invalidMessage?: string;
}) {
  const [touched, setTouched] = useState(false);
  const [validation, setValidation] = useState<string>();
  const hintId = useId();
  const invalid =
    error ||
    validation ||
    (touched && required && !props.value ? "필수 항목입니다." : undefined);
  return (
    <label className="field">
      <span>
        {label}
        {required && <b className="danger"> *</b>}
      </span>
      <input
        {...props}
        maxLength={maxLength}
        required={required}
        aria-invalid={!!invalid}
        aria-describedby={hintId}
        onInvalid={(e) => {
          setTouched(true);
          setValidation(invalidMessage ?? e.currentTarget.validationMessage);
          props.onInvalid?.(e);
        }}
        onChange={(e) => {
          setValidation(undefined);
          props.onChange?.(e);
        }}
        onBlur={(e) => {
          setTouched(true);
          setValidation(
            e.currentTarget.validity.valid
              ? undefined
              : (invalidMessage ?? e.currentTarget.validationMessage),
          );
          props.onBlur?.(e);
        }}
      />
      <span id={hintId} className="fieldHint">
        {maxLength && (
          <small>
            {String(props.value ?? "").length}/{maxLength}
          </small>
        )}
        {invalid && <small className="danger">{invalid}</small>}
      </span>
    </label>
  );
}
export function MoneyInput({
  value,
  onChange,
  label,
}: {
  value: number | null;
  onChange: (value: number | null) => void;
  label: string;
}) {
  return (
    <label>
      {label}
      <input
        aria-label={label}
        className="numeric"
        inputMode="numeric"
        value={value === null ? "" : amount(value)}
        onChange={(e) => {
          const n = parseAmount(e.target.value);
          if (n !== undefined) onChange(n);
        }}
      />
    </label>
  );
}
export function Loading() {
  return (
    <div role="status" aria-label="불러오는 중" className="skeleton">
      <span className="srOnly">불러오는 중…</span>
      <span className="skeletonLine" />
      <span className="skeletonLine" />
      <span className="skeletonLine" />
    </div>
  );
}
export function Empty() {
  return (
    <div className="empty" role="status">
      <Inbox size={28} aria-hidden="true" />
      <p>조건에 맞는 결과가 없습니다</p>
    </div>
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
  size: number;
  total: number;
  onPage: (p: number) => void;
  onSize: (s: number) => void;
}) {
  const last = Math.max(1, Math.ceil(total / size));
  const start = Math.floor((page - 1) / 10) * 10 + 1;
  return (
    <div className="pagination">
      <span>
        {total ? (page - 1) * size + 1 : 0}-{Math.min(page * size, total)} /{" "}
        {total}건
      </span>
      <button
        disabled={page === 1}
        onClick={() => onPage(1)}
        aria-label="첫 페이지"
      >
        «
      </button>
      <button
        disabled={page === 1}
        onClick={() => onPage(page - 1)}
        aria-label="이전 페이지"
      >
        ‹
      </button>
      {Array.from(
        { length: Math.min(10, last - start + 1) },
        (_, i) => start + i,
      ).map((p) => (
        <button
          key={p}
          className={p === page ? "primary" : ""}
          aria-current={p === page ? "page" : undefined}
          onClick={() => onPage(p)}
        >
          {p}
        </button>
      ))}
      <button
        disabled={page >= last}
        onClick={() => onPage(page + 1)}
        aria-label="다음 페이지"
      >
        ›
      </button>
      <button
        disabled={page >= last}
        onClick={() => onPage(last)}
        aria-label="마지막 페이지"
      >
        »
      </button>
      <select
        aria-label="페이지 건수"
        value={size}
        onChange={(e) => onSize(Number(e.target.value))}
      >
        {[20, 50, 100].map((n) => (
          <option key={n} value={n}>
            {n}건씩
          </option>
        ))}
      </select>
    </div>
  );
}
