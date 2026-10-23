import { useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import type { ApiFailure } from "../../shared/api";
import {
  partnerFailure,
  savePartnerDisable,
  savePartnerEnable,
  downloadPartnerFile,
  partnerExportPath,
  type PartnerSelection,
  type PartnerDisableResult,
  type PartnerFilters,
} from "./client";
import styles from "./Partners.module.css";

// SCR-UI-15-M4 / SCR-UI-15-M11; no write is sent before confirmation.
export function PartnerStatusActions({
  rows,
  onChanged,
}: {
  rows: readonly PartnerSelection[];
  onChanged: () => void | Promise<void>;
}) {
  const [open, setOpen] = useState(false),
    [pending, setPending] = useState(false),
    [result, setResult] = useState<PartnerDisableResult | null>(null),
    [error, setError] = useState<ApiFailure | null>(null),
    [message, setMessage] = useState("");
  const already = rows.length > 0 && rows.every((row) => row.use_yn === "N");
  const close = () => {
    if (!pending) {
      setOpen(false);
      setError(null);
      setResult(null);
    }
  };
  async function disable() {
    setPending(true);
    setError(null);
    try {
      const value = await savePartnerDisable(rows.map((row) => row.ptnr_id));
      setResult(value);
      setMessage(
        `${value.disabledIds.length}건 완료${value.blocked.length ? ` · ${value.blocked.length}건 제외` : ""}`,
      );
      if (value.disabledIds.length) await onChanged();
    } catch (e) {
      setError(partnerFailure(e));
    } finally {
      setPending(false);
    }
  }
  async function enable() {
    setPending(true);
    setError(null);
    try {
      await savePartnerEnable(rows[0].ptnr_id);
      setMessage("다시 사용합니다.");
      await onChanged();
    } catch (e) {
      setError(partnerFailure(e));
    } finally {
      setPending(false);
    }
  }
  const blocked =
    error?.code === "PARTNER_IN_USE" ||
    (result && !result.disabledIds.length && result.blocked.length > 0);
  return (
    <div data-testid="partner-status-actions" className={styles.desktopActions}>
      <Can perm="UI-15.delete">
        <Button
          data-testid="partner-disable-open"
          disabled={!rows.length || pending}
          onClick={() => {
            setResult(null);
            setError(null);
            setOpen(true);
          }}
        >
          사용 중지
        </Button>
        {rows.length === 1 && already && (
          <Button
            data-testid="partner-enable"
            pending={pending}
            onClick={() => void enable()}
          >
            다시 사용
          </Button>
        )}
      </Can>
      {message && <p role="status">{message}</p>}
      {!open && <ErrorBanner error={error} onRetry={() => void onChanged()} />}
      {open && (
        <Modal
          title={
            already
              ? "이미 중지된 거래처입니다."
              : blocked
                ? "사용을 중지할 수 없습니다"
                : result
                  ? "사용 중지 결과"
                  : `${rows.length}건의 사용을 중지할까요?`
          }
          onCancel={close}
        >
          <section
            data-testid="partner-disable-dialog"
            data-screen-id={
              blocked || already ? "SCR-UI-15-M11" : "SCR-UI-15-M4"
            }
          >
            <ul>
              {rows.map((row) => (
                <li
                  data-testid={`partner-disable-name-${row.ptnr_id}`}
                  key={row.ptnr_id}
                >
                  {row.ptnr_nm}
                </li>
              ))}
            </ul>
            <p>
              중지하면 새 프로젝트·지급에서 고를 수 없습니다. 기록은 그대로
              남습니다.
            </p>
            <ErrorBanner
              error={error}
              onRetry={() => void onChanged()}
              pending={pending}
            />
            {result && (
              <>
                <p role="status">
                  {result.disabledIds.length}건 완료 · {result.blocked.length}건
                  제외
                </p>
                <ul>
                  {result.blocked.map((row) => (
                    <li data-testid={`partner-blocked-${row.id}`} key={row.id}>
                      {row.name} — 진행 프로젝트 {row.projectCount}건 / 소속
                      인력 {row.peopleCount}명 · {row.reason}
                    </li>
                  ))}
                </ul>
              </>
            )}
            <Button
              data-testid="partner-disable-cancel"
              disabled={pending}
              onClick={close}
            >
              {result || already || blocked ? "확인" : "취소"}
            </Button>
            {!already && !result && !blocked && (
              <Button
                data-testid="partner-disable-confirm"
                className={styles.danger}
                pending={pending}
                onClick={() => void disable()}
              >
                사용 중지
              </Button>
            )}
          </section>
        </Modal>
      )}
    </div>
  );
}

// SCR-UI-15-M13 / SCR-UI-15-M14; shared by the two table toolbars.
export function PartnerExportButton({
  resource = "partners",
  filters,
  columns,
  ids,
  total,
}: {
  resource?: "partners" | "sales-parts";
  filters: PartnerFilters;
  columns: readonly string[];
  ids: readonly string[];
  total: number;
}) {
  const [open, setOpen] = useState(false),
    [range, setRange] = useState("selected"),
    [pending, setPending] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null);
  async function download(selected: boolean) {
    setPending(true);
    setError(null);
    try {
      await downloadPartnerFile(
        partnerExportPath(
          resource,
          filters,
          columns,
          selected ? ids : undefined,
        ),
      );
      setOpen(false);
    } catch (e) {
      setError(partnerFailure(e));
    } finally {
      setPending(false);
    }
  }
  return (
    <div className={styles.desktopActions} data-testid="partner-export-actions">
      <Can perm="UI-15.download">
        <Button
          data-testid="partner-export-open"
          pending={pending}
          onClick={() => {
            if (ids.length) {
              setRange("selected");
              setOpen(true);
            } else void download(false);
          }}
        >
          엑셀 다운로드
        </Button>
      </Can>
      <ErrorBanner
        error={error}
        onRetry={() => void download(open && range === "selected")}
        pending={pending}
      />
      {open && (
        <Modal
          title="다운로드 범위"
          onCancel={() => {
            if (!pending) setOpen(false);
          }}
        >
          <section
            data-testid="partner-export-dialog"
            data-screen-id={
              resource === "partners" ? "SCR-UI-15-M13" : "SCR-UI-15-M14"
            }
          >
            <label>
              <input
                data-testid="partner-export-selected"
                type="radio"
                name="export-range"
                checked={range === "selected"}
                onChange={() => setRange("selected")}
              />
              선택한 {ids.length}건만
            </label>
            <label>
              <input
                data-testid="partner-export-all"
                type="radio"
                name="export-range"
                checked={range === "all"}
                onChange={() => setRange("all")}
              />
              조회된 전체 {total}건
            </label>
            <Button
              data-testid="partner-export-cancel"
              disabled={pending}
              onClick={() => setOpen(false)}
            >
              취소
            </Button>
            <Button
              data-testid="partner-export-confirm"
              pending={pending}
              onClick={() => void download(range === "selected")}
            >
              내려받기
            </Button>
          </section>
        </Modal>
      )}
    </div>
  );
}
