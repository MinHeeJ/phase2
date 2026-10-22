import type { FormEvent, ReactNode } from "react";
import { useId, useState } from "react";
import { formatAmount, isValidDate, isValidMonth } from "../lib/format";
import styles from "./Inputs.module.css";

type BaseProps = {
  label: string;
  required?: boolean;
  error?: string | null;
  disabled?: boolean;
  readOnly?: boolean;
  name?: string;
  id?: string;
  requiredMessage?: string;
};
type TextProps = BaseProps & {
  value: string;
  onChange: (value: string) => void;
  maxLength?: number;
};
function Field({
  label,
  required,
  error,
  id,
  children,
  counter,
}: BaseProps & { id: string; children: ReactNode; counter?: string }) {
  return (
    <div
      data-testid={`field-${id
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-|-$/g, "")}`}
      className={styles.field}
      data-field="true"
    >
      <label htmlFor={id}>
        {label}
        {required && <span className={styles.required}> *</span>}
      </label>
      <div className={styles.control}>
        {children}
        {counter && <span className={styles.counter}>{counter}</span>}
        {error && (
          <span role="alert" id={`${id}-error`} className={styles.error}>
            {error}
          </span>
        )}
      </div>
    </div>
  );
}
export function TextInput(props: TextProps & { multiline?: boolean }) {
  const generated = useId();
  const id = props.id ?? generated;
  const [touched, setTouched] = useState(false);
  const error =
    props.error ||
    (touched && props.required && !props.value.trim()
      ? (props.requiredMessage ?? `${props.label}을 입력하세요.`)
      : null);
  const common = {
    id,
    name: props.name,
    value: props.value,
    disabled: props.disabled,
    readOnly: props.readOnly,
    required: props.required,
    maxLength: props.maxLength,
    "aria-invalid": Boolean(error),
    "aria-describedby": error ? `${id}-error` : undefined,
    onBlur: () => setTouched(true),
    className: styles.input,
    onChange: (
      event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>,
    ) => props.onChange(event.target.value.slice(0, props.maxLength)),
  };
  return (
    <Field
      {...props}
      id={id}
      error={error}
      counter={
        props.maxLength == null
          ? undefined
          : `${props.value.length}/${props.maxLength}`
      }
    >
      {props.multiline ? (
        <textarea
          data-testid={`textarea-${id
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, "-")
            .replace(/^-|-$/g, "")}`}
          {...common}
        />
      ) : (
        <input
          data-testid={`text-input-${id
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, "-")
            .replace(/^-|-$/g, "")}`}
          type="text"
          {...common}
        />
      )}
    </Field>
  );
}
export function TextArea(props: TextProps) {
  return <TextInput {...props} multiline />;
}
export function AmountInput(
  props: BaseProps & {
    value: number | null;
    onChange: (value: number | null) => void;
  },
) {
  const generated = useId();
  const id = props.id ?? generated;
  const [localError, setLocalError] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);
  const error =
    props.error ||
    localError ||
    (touched && props.required && props.value == null
      ? (props.requiredMessage ?? "금액을 입력하세요.")
      : null);
  function change(raw: string) {
    const clean = raw.replace(/[,\s원]/g, "");
    if (!clean) {
      props.onChange(null);
      setLocalError(null);
      return;
    }
    if (
      !/^\d+$/.test(clean) ||
      !Number.isSafeInteger(Number(clean)) ||
      Number(clean) > 999999999999
    ) {
      setLocalError("0~999,999,999,999원 범위의 금액을 입력하세요.");
      return;
    }
    setLocalError(null);
    props.onChange(Number(clean));
  }
  return (
    <Field {...props} id={id} error={error}>
      <input
        data-testid={`amount-input-${id
          .toLowerCase()
          .replace(/[^a-z0-9]+/g, "-")
          .replace(/^-|-$/g, "")}`}
        id={id}
        name={props.name}
        inputMode="numeric"
        type="text"
        className={`${styles.input} ${styles.amount}`}
        value={props.value == null ? "" : formatAmount(props.value)}
        disabled={props.disabled}
        readOnly={props.readOnly}
        required={props.required}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${id}-error` : undefined}
        onBlur={() => setTouched(true)}
        onChange={(e) => change(e.target.value)}
        onPaste={(e) => {
          e.preventDefault();
          change(e.clipboardData.getData("text"));
        }}
      />
    </Field>
  );
}
function CalendarInput(props: TextProps & { kind: "date" | "month" }) {
  const generated = useId();
  const id = props.id ?? generated;
  const [touched, setTouched] = useState(false);
  const valid =
    props.value === ""
      ? !props.required
      : props.kind === "date"
        ? isValidDate(props.value)
        : isValidMonth(props.value);
  const error =
    props.error || (touched && !valid ? "올바른 날짜를 입력하세요." : null);
  return (
    <Field {...props} id={id} error={error}>
      <input
        data-testid={`${props.kind}-input-${id
          .toLowerCase()
          .replace(/[^a-z0-9]+/g, "-")
          .replace(/^-|-$/g, "")}`}
        id={id}
        name={props.name}
        type={props.kind}
        value={props.value}
        className={styles.input}
        disabled={props.disabled}
        readOnly={props.readOnly}
        required={props.required}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${id}-error` : undefined}
        onChange={(e) => props.onChange(e.target.value)}
        onBlur={() => setTouched(true)}
      />
    </Field>
  );
}
export function DateInput(props: TextProps) {
  return <CalendarInput {...props} kind="date" />;
}
export function MonthInput(props: TextProps) {
  return <CalendarInput {...props} kind="month" />;
}
export function SelectInput(
  props: BaseProps & {
    value: string;
    onChange: (value: string) => void;
    options: { value: string; label: string }[];
  },
) {
  const generated = useId();
  const id = props.id ?? generated;
  const [touched, setTouched] = useState(false);
  const error =
    props.error ||
    (touched && props.required && !props.value
      ? (props.requiredMessage ?? "항목을 선택하세요.")
      : null);
  return (
    <Field {...props} id={id} error={error}>
      <select
        data-testid={`select-input-${id
          .toLowerCase()
          .replace(/[^a-z0-9]+/g, "-")
          .replace(/^-|-$/g, "")}`}
        id={id}
        name={props.name}
        className={styles.input}
        value={props.value}
        required={props.required}
        disabled={props.disabled || props.readOnly}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${id}-error` : undefined}
        onChange={(e) => props.onChange(e.target.value)}
        onBlur={() => setTouched(true)}
      >
        <option
          data-testid={`select-placeholder-${id
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, "-")
            .replace(/^-|-$/g, "")}`}
          value=""
        >
          선택하세요
        </option>
        {props.options.map((option) => (
          <option
            data-testid={`select-option-${id
              .toLowerCase()
              .replace(/[^a-z0-9]+/g, "-")
              .replace(/^-|-$/g, "")}-${props.options.indexOf(option)}`}
            key={option.value}
            value={option.value}
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
  onSubmit,
}: {
  children: ReactNode;
  onSubmit: () => void;
}) {
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const first = event.currentTarget.querySelector<HTMLElement>(
      '[aria-invalid="true"], :invalid',
    );
    if (first) {
      first.focus();
      first.dispatchEvent(new FocusEvent("blur", { bubbles: true }));
      return;
    }
    onSubmit();
  }
  return (
    <form
      data-testid={`input-form-${Math.random().toString(36).slice(2)}`}
      className={styles.form}
      noValidate
      onSubmit={submit}
    >
      {children}
    </form>
  );
}
