import {
  useEffect,
  useId,
  useRef,
  useState,
  type FormEvent,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from "react";
import { normalizeMoney, validMoney } from "../lib/format";
import styles from "./ui.module.css";

type Common = {
  label: string;
  error?: string;
  required?: boolean;
  name: string;
};
function Field({
  id,
  label,
  required,
  error,
  children,
  count,
}: Common & { id: string; children: ReactNode; count?: string }) {
  return (
    <div className={styles.field}>
      <label htmlFor={id}>
        {label}
        {required && (
          <span className={styles.required} aria-label="필수">
            {" "}
            *
          </span>
        )}
      </label>
      <div className={styles.fieldControl}>
        {children}
        {count && <span className={styles.counter}>{count}</span>}
        {error && (
          <span id={`${id}-error`} className={styles.fieldError}>
            {error}
          </span>
        )}
      </div>
    </div>
  );
}
export function TextInput({
  label,
  error,
  value,
  onChange,
  maxLength,
  required,
  ...props
}: Common & Omit<InputHTMLAttributes<HTMLInputElement>, "name" | "required">) {
  const id = useId();
  const ref = useRef<HTMLInputElement>(null);
  const [touched, setTouched] = useState(false);
  useEffect(() => {
    ref.current?.setCustomValidity(
      required && !String(value ?? "").trim() ? "필수 항목을 입력하세요." : "",
    );
  }, [required, value]);
  const message =
    error ||
    (touched && required && !String(value ?? "").trim()
      ? "필수 항목을 입력하세요."
      : undefined);
  return (
    <Field
      {...props}
      id={id}
      label={label}
      required={required}
      error={message}
      count={
        maxLength ? `${String(value ?? "").length}/${maxLength}` : undefined
      }
    >
      <input
        {...props}
        ref={ref}
        id={id}
        className={styles.input}
        value={value}
        required={required}
        maxLength={maxLength}
        aria-invalid={!!message}
        aria-describedby={message ? `${id}-error` : undefined}
        onChange={onChange}
        onBlur={(event) => {
          setTouched(true);
          props.onBlur?.(event);
        }}
        onInvalid={() => setTouched(true)}
      />
    </Field>
  );
}
export function MoneyInput({
  label,
  name,
  value,
  onChange,
  required,
  disabled,
  error,
}: Common & {
  value: string;
  onChange: (raw: string) => void;
  disabled?: boolean;
}) {
  const id = useId();
  const ref = useRef<HTMLInputElement>(null);
  const [touched, setTouched] = useState(false);
  const valid = value === "" ? !required : validMoney(value);
  const message =
    error ||
    (touched && !valid
      ? "0~999,999,999,999 범위의 정수를 입력하세요."
      : undefined);
  useEffect(() => {
    ref.current?.setCustomValidity(valid ? "" : "금액을 확인하세요.");
  }, [valid]);
  const displayed =
    value && validMoney(value) ? BigInt(value).toLocaleString("ko-KR") : value;
  return (
    <Field
      id={id}
      name={name}
      label={label}
      required={required}
      error={message}
    >
      <input
        ref={ref}
        id={id}
        name={name}
        className={`${styles.input} ${styles.numeric}`}
        inputMode="numeric"
        value={displayed}
        required={required}
        disabled={disabled}
        aria-invalid={!!message}
        aria-describedby={message ? `${id}-error` : undefined}
        onInvalid={() => setTouched(true)}
        onBlur={() => setTouched(true)}
        onChange={(event) => onChange(normalizeMoney(event.target.value))}
      />
    </Field>
  );
}
export function DateInput(
  props: Common &
    Omit<InputHTMLAttributes<HTMLInputElement>, "name" | "required">,
) {
  return <TextInput {...props} type="date" />;
}
export function MonthInput(
  props: Common &
    Omit<InputHTMLAttributes<HTMLInputElement>, "name" | "required">,
) {
  return <TextInput {...props} type="month" />;
}
export function SelectInput({
  label,
  name,
  required,
  error,
  children,
  ...props
}: Common & SelectHTMLAttributes<HTMLSelectElement>) {
  const id = useId();
  const [touched, setTouched] = useState(false);
  const message =
    error ||
    (touched && required && !props.value
      ? "필수 항목을 선택하세요."
      : undefined);
  return (
    <Field
      id={id}
      label={label}
      name={name}
      required={required}
      error={message}
    >
      <select
        {...props}
        id={id}
        name={name}
        required={required}
        className={styles.input}
        aria-invalid={!!message}
        onBlur={(event) => {
          setTouched(true);
          props.onBlur?.(event);
        }}
        onInvalid={() => setTouched(true)}
      >
        {children}
      </select>
    </Field>
  );
}
export function TextArea({
  label,
  name,
  required,
  error,
  maxLength,
  ...props
}: Common & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const id = useId();
  const [touched, setTouched] = useState(false);
  const message =
    error ||
    (touched && required && !String(props.value ?? "").trim()
      ? "필수 항목을 입력하세요."
      : undefined);
  return (
    <Field
      id={id}
      name={name}
      label={label}
      required={required}
      error={message}
      count={
        maxLength
          ? `${String(props.value ?? "").length}/${maxLength}`
          : undefined
      }
    >
      <textarea
        {...props}
        id={id}
        name={name}
        maxLength={maxLength}
        required={required}
        className={styles.input}
        aria-invalid={!!message}
        onBlur={(event) => {
          setTouched(true);
          props.onBlur?.(event);
        }}
        onInvalid={() => setTouched(true)}
      />
    </Field>
  );
}
export function focusField(form: HTMLFormElement, field?: string | null) {
  const element = field
    ? form.elements.namedItem(field)
    : form.querySelector<HTMLElement>('[aria-invalid="true"], :invalid');
  if (element instanceof HTMLElement) element.focus();
}
export function Form({
  children,
  onSave,
}: {
  children: ReactNode;
  onSave: () => void;
}) {
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (
      !event.currentTarget.checkValidity() ||
      event.currentTarget.querySelector('[aria-invalid="true"]')
    ) {
      focusField(event.currentTarget);
      return;
    }
    onSave();
  }
  return (
    <form className={styles.form} noValidate onSubmit={submit}>
      {children}
    </form>
  );
}
