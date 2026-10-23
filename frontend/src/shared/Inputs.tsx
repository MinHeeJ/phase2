import {
  forwardRef,
  useId,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import { isDate, isMonth, parseMoneyDraft } from "../lib/format";
import styles from "./Inputs.module.css";

type Common = {
  label: string;
  name: string;
  required?: boolean;
  disabled?: boolean;
  readOnly?: boolean;
  error?: string | null;
  maxLength?: number;
};
type TextProps = Common & {
  value: string;
  onValueChange: (value: string) => void;
  type?: "text" | "date" | "month";
  placeholder?: string;
  onBlur?: () => void;
};

function Field({
  id,
  label,
  required,
  error,
  children,
  count,
  maxLength,
}: Common & { id: string; children: ReactNode; count?: number }) {
  return (
    <div className={styles.field}>
      <label htmlFor={id}>
        {label}
        {required && (
          <span className={styles.required} aria-hidden="true">
            {" "}
            *
          </span>
        )}
      </label>
      <div className={styles.control}>
        {children}
        {maxLength != null && (
          <span className={styles.counter}>
            {count ?? 0}/{maxLength}
          </span>
        )}
        {error && (
          <span id={`${id}-error`} role="alert" className={styles.error}>
            {error}
          </span>
        )}
      </div>
    </div>
  );
}

export const TextInput = forwardRef<HTMLInputElement, TextProps>(
  function TextInput(props, ref) {
    const id = useId();
    const [touched, setTouched] = useState(false);
    const {
      value,
      onValueChange,
      type = "text",
      label,
      name,
      required,
      disabled,
      readOnly,
      maxLength,
      placeholder,
    } = props;
    const validation =
      required && !value
        ? `${label}을 입력하세요.`
        : value && type === "date" && !isDate(value)
          ? "올바른 날짜를 입력하세요."
          : value && type === "month" && !isMonth(value)
            ? "올바른 연월을 입력하세요."
            : null;
    const error = props.error ?? (touched ? validation : null);
    return (
      <Field {...props} id={id} error={error} count={value.length}>
        <input
          ref={ref}
          id={id}
          aria-label={label}
          name={name}
          type={type}
          value={value}
          required={required}
          disabled={disabled}
          readOnly={readOnly}
          maxLength={maxLength}
          placeholder={placeholder}
          className={styles.input}
          aria-invalid={!!error}
          aria-describedby={error ? `${id}-error` : undefined}
          onInvalid={() => setTouched(true)}
          onBlur={() => {
            setTouched(true);
            props.onBlur?.();
          }}
          onChange={(event) => {
            const next = event.target.value;
            if (maxLength == null || next.length <= maxLength)
              onValueChange(next);
          }}
        />
      </Field>
    );
  },
);
export const DateInput = (props: Omit<TextProps, "type">) => (
  <TextInput {...props} type="date" />
);
export const MonthInput = (props: Omit<TextProps, "type">) => (
  <TextInput {...props} type="month" />
);

export function MoneyInput(
  props: Common & {
    value: number | null;
    onValueChange: (value: number | null) => void;
  },
) {
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const [touched, setTouched] = useState(false);
  const [boundaryError, setBoundaryError] = useState<string | null>(null);
  const display =
    props.value == null ? "" : props.value.toLocaleString("en-US");
  const error =
    props.error ??
    boundaryError ??
    (touched && props.required && props.value == null
      ? `${props.label}을 입력하세요.`
      : null);
  return (
    <Field {...props} id={id} error={error}>
      <input
        ref={input}
        id={id}
        aria-label={props.label}
        name={props.name}
        type="text"
        inputMode="numeric"
        value={display}
        required={props.required}
        disabled={props.disabled}
        readOnly={props.readOnly}
        className={`${styles.input} ${styles.money}`}
        aria-invalid={!!error}
        aria-describedby={error ? `${id}-error` : undefined}
        onInvalid={() => setTouched(true)}
        onBlur={() => setTouched(true)}
        onChange={(event) => {
          const parsed = parseMoneyDraft(event.target.value);
          if (!parsed.valid) {
            setBoundaryError(parsed.message);
            event.target.setCustomValidity(parsed.message);
            return;
          }
          event.target.setCustomValidity("");
          setBoundaryError(null);
          props.onValueChange(parsed.value);
        }}
      />
    </Field>
  );
}

export function TextareaInput(props: Omit<TextProps, "type">) {
  const id = useId();
  const [touched, setTouched] = useState(false);
  const error =
    props.error ??
    (touched && props.required && !props.value
      ? `${props.label}을 입력하세요.`
      : null);
  return (
    <Field {...props} id={id} error={error} count={props.value.length}>
      <textarea
        id={id}
        aria-label={props.label}
        name={props.name}
        value={props.value}
        maxLength={props.maxLength}
        required={props.required}
        readOnly={props.readOnly}
        disabled={props.disabled}
        className={styles.input}
        aria-invalid={!!error}
        aria-describedby={error ? `${id}-error` : undefined}
        onBlur={() => setTouched(true)}
        onInvalid={() => setTouched(true)}
        onChange={(e) => {
          if (
            props.maxLength == null ||
            e.target.value.length <= props.maxLength
          )
            props.onValueChange(e.target.value);
        }}
      />
    </Field>
  );
}
export function SelectInput(
  props: Common & {
    value: string;
    onValueChange: (value: string) => void;
    options: readonly { value: string; label: string; disabled?: boolean }[];
  },
) {
  const id = useId();
  const [touched, setTouched] = useState(false);
  const error =
    props.error ??
    (touched && props.required && !props.value
      ? `${props.label}을 선택하세요.`
      : null);
  return (
    <Field {...props} id={id} error={error}>
      <select
        id={id}
        aria-label={props.label}
        name={props.name}
        value={props.value}
        required={props.required}
        disabled={props.disabled || props.readOnly}
        className={styles.input}
        aria-invalid={!!error}
        aria-describedby={error ? `${id}-error` : undefined}
        onBlur={() => setTouched(true)}
        onInvalid={() => setTouched(true)}
        onChange={(e) => props.onValueChange(e.target.value)}
      >
        <option value="">선택하세요</option>
        {props.options.map((option) => (
          <option
            key={option.value}
            value={option.value}
            disabled={option.disabled}
          >
            {option.label}
          </option>
        ))}
      </select>
    </Field>
  );
}
export function Form({
  children,
  onSave,
  className = "",
}: {
  children: ReactNode;
  onSave: () => void;
  className?: string;
}) {
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    if (!form.checkValidity()) {
      form.querySelector<HTMLElement>(":invalid")?.focus();
      return;
    }
    const serverError = form.querySelector<HTMLElement>(
      '[aria-invalid="true"]',
    );
    if (serverError) {
      serverError.focus();
      return;
    }
    onSave();
  };
  return (
    <form
      noValidate
      onSubmit={submit}
      className={`${styles.form} ${className}`}
    >
      {children}
    </form>
  );
}
