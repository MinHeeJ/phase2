import { Badge } from "../../shared/Controls";

export interface DispatchExpiryResult {
  firstStart: string | null;
  expiry: string | null;
  daysRemaining: number | null;
  alertDate: string | null;
  urgentDate: string | null;
  display: "NONE" | "DATE" | "WARNING" | "DANGER" | "EXCEEDED";
}

// SCR-UI-05: dispatch expiry is calculated by the server for the current query.
export function DispatchExpiryCell({ value }: { value: DispatchExpiryResult }) {
  if (value.display === "NONE" || !value.expiry) return <span>–</span>;
  const tooltip = `최초 투입일 ${value.firstStart} · 만료일 ${value.expiry} · 알림일 ${value.alertDate}, ${value.urgentDate}`;
  if (value.display === "DATE")
    return <span title={tooltip}>{value.expiry}</span>;
  const label =
    value.display === "EXCEEDED"
      ? "파견 기간 초과"
      : `파견 만료 D-${value.daysRemaining}`;
  return (
    <span title={tooltip}>
      <Badge tone={value.display === "WARNING" ? "warning" : "danger"}>
        {label}
      </Badge>
    </span>
  );
}

export function DispatchSoonFilter({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label>
      <input
        data-testid="dispatch-soon-filter"
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
      />{" "}
      파견 만료 60일 안
    </label>
  );
}
