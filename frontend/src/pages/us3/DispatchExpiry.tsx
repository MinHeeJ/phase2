import { Badge } from "../../shared/Controls";
import styles from "./DispatchExpiry.module.css";

export interface DispatchExpiryFields {
  dispatch_first_dt: string | null;
  dispatch_expiry_dt: string | null;
  dispatch_display: string;
  dispatch_tone: "neutral" | "warning" | "danger";
  dispatch_alert_dates: readonly string[];
}

// SCR-UI-05: inserted before the extension-chance column by the withdrawals screen.
export function DispatchExpiryCell({
  assignmentId,
  value,
}: {
  assignmentId: number;
  value: DispatchExpiryFields;
}) {
  const tooltip =
    value.dispatch_first_dt && value.dispatch_expiry_dt
      ? `최초 투입일 ${value.dispatch_first_dt} · 만료일 ${value.dispatch_expiry_dt} · 알림일 ${value.dispatch_alert_dates.join(", ")}`
      : undefined;
  const badge = value.dispatch_display.startsWith("파견");
  return (
    <span
      data-testid={`dispatch-expiry-${assignmentId}`}
      className={styles.cell}
      title={tooltip}
      aria-label={
        tooltip ? `${value.dispatch_display} · ${tooltip}` : undefined
      }
      tabIndex={tooltip ? 0 : undefined}
    >
      {badge ? (
        <Badge tone={value.dispatch_tone}>{value.dispatch_display}</Badge>
      ) : (
        value.dispatch_display
      )}
    </span>
  );
}

export function DispatchExpiryFilter({
  checked,
  onChange,
  disabled = false,
  error,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  error?: string;
}) {
  return (
    <div className={styles.filter} data-testid="dispatch-expiry-filter">
      <label>
        <input
          data-testid="dispatch-within-sixty-checkbox"
          type="checkbox"
          checked={checked}
          disabled={disabled}
          aria-invalid={!!error}
          aria-describedby={error ? "dispatch-within-sixty-error" : undefined}
          onChange={(event) => onChange(event.target.checked)}
        />
        파견 만료 60일 안
      </label>
      {error && (
        <span id="dispatch-within-sixty-error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}

export function dispatchQuery(checked: boolean): Record<string, string> {
  return checked ? { dispatchWithin60: "true" } : {};
}
