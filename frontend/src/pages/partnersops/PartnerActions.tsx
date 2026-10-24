import { useRef, useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminWrite } from "../admin/useAdminResource";
import {
  disablePartners,
  enablePartner,
  downloadList,
  type PartnerRow,
  type PartnerBlock,
  type PartnerFilter,
} from "./client";
import styles from "./PartnerLifecycle.module.css";

export type PartnerDisableRow = Pick<
  PartnerRow,
  "ptnr_id" | "ptnr_nm" | "ptnr_se_cd" | "use_yn"
> & { projectCount?: number | null };
export function PartnerDisableDialog({
  rows,
  onClose,
  reload,
  onComplete,
}: {
  rows: PartnerDisableRow[];
  onClose: () => void;
  reload: () => Promise<unknown>;
  onComplete: (message: string) => void;
}) {
  const write = useAdminWrite(reload);
  const [blocked, setBlocked] = useState<PartnerBlock[]>([]);
  const [finished, setFinished] = useState(false);
  const [disabledCount, setDisabledCount] = useState(0);
  const active = rows.filter((row) => row.use_yn === "Y");
  const knownBlocked = active.filter(
    (row) => row.ptnr_se_cd === "CUST" && (row.projectCount ?? 0) > 0,
  );
  const allStopped = !active.length;
  const allBlocked =
    (finished && disabledCount === 0 && blocked.length > 0) ||
    write.error?.code === "PARTNER_IN_USE";
  return (
    <Modal
      title={allBlocked ? "사용을 중지할 수 없습니다" : "거래처 사용 중지"}
      onCancel={() => {
        if (!write.pending) onClose();
      }}
      confirmation
    >
      <div
        data-screen-id={allBlocked ? "SCR-UI-15-M11" : "SCR-UI-15-M4"}
        data-testid="partner-disable-dialog"
      >
        {allStopped ? (
          <p>이미 중지된 거래처입니다.</p>
        ) : (
          <>
            {!allBlocked && !finished && (
              <>
                <p>
                  선택한 거래처 {active.length}건의 사용을 중지하시겠습니까?
                </p>
                <p>{active.map((row) => row.ptnr_nm).join(", ")}</p>
              </>
            )}
            <p>
              기존 거래·프로젝트·투입 기록은 보존됩니다. 새 선택 목록에서는
              제외됩니다.
            </p>
            {finished && disabledCount > 0 && (
              <p role="status">
                중지 완료: {disabledCount}건 / 제외: {blocked.length}건
              </p>
            )}
            {finished && !disabledCount && !blocked.length && (
              <p>이미 중지된 거래처입니다.</p>
            )}
            {knownBlocked.length > 0 && !finished && !allBlocked && (
              <div className={styles.warning}>
                <p>
                  조회 시점에 진행 프로젝트가 있는 거래처입니다. 중지 가능
                  여부는 확인 후 다시 검사합니다.
                </p>
                {knownBlocked.map((row) => (
                  <p
                    data-testid={`partner-known-blocked-${row.ptnr_id.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
                    key={row.ptnr_id}
                  >
                    {row.ptnr_nm} · 진행 프로젝트 {row.projectCount}건
                  </p>
                ))}
              </div>
            )}
            {blocked.length > 0 && (
              <div className={styles.warning}>
                {blocked.map((row) => (
                  <p
                    data-testid={`partner-blocked-${row.id.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
                    key={row.id}
                  >
                    {row.name} · 진행 프로젝트 {row.projectCount}건 · 소속 인력{" "}
                    {row.peopleCount}명 · {row.reason}
                  </p>
                ))}
              </div>
            )}
            <ErrorBanner
              error={write.error}
              onRetry={() => {
                void write.recover();
              }}
              pending={write.pending}
            />
          </>
        )}
        <div className={styles.actions}>
          <Button
            data-testid="partner-disable-cancel"
            disabled={write.pending}
            onClick={onClose}
          >
            {allBlocked || allStopped || finished ? "닫기" : "취소"}
          </Button>
          {!allBlocked && !allStopped && !finished && (
            <Button
              data-testid="partner-disable-confirm"
              className={styles.danger}
              pending={write.pending}
              disabled={write.uncertain || !!write.error}
              onClick={() => {
                let closeAfterReload = false;
                void write.save(
                  async () => {
                    const result = await disablePartners(
                      rows.map((row) => row.ptnr_id),
                    );
                    setBlocked(result.blocked);
                    setDisabledCount(result.disabledIds.length);
                    setFinished(true);
                    closeAfterReload = result.blocked.length === 0;
                    onComplete(
                      result.disabledIds.length
                        ? `${result.disabledIds.length}건 사용 중지 완료${result.blocked.length ? ` · ${result.blocked.length}건 제외` : ""}`
                        : result.blocked.length
                          ? "사용을 중지할 수 없습니다"
                          : "이미 중지된 거래처입니다.",
                    );
                  },
                  () => {
                    if (closeAfterReload) onClose();
                  },
                );
              }}
            >
              사용 중지
            </Button>
          )}
        </div>
      </div>
    </Modal>
  );
}
export function PartnerEnableButton({
  id,
  reload,
  onComplete,
}: {
  id: string;
  reload: () => Promise<unknown>;
  onComplete: (message: string) => void;
}) {
  const write = useAdminWrite(reload);
  return (
    <div data-testid="partner-enable-control" className={styles.mutate}>
      <Button
        data-testid="partner-enable"
        pending={write.pending}
        disabled={write.uncertain || !!write.error}
        onClick={() => {
          void write.save(
            () => enablePartner(id),
            () => onComplete("다시 사용하도록 변경했습니다."),
          );
        }}
      >
        다시 사용
      </Button>
      <ErrorBanner
        error={write.error}
        pending={write.pending}
        onRetry={() => {
          void write.recover();
        }}
      />
    </div>
  );
}
export function ListExportButton({
  resource = "partners",
  filter,
  columns,
  selectedIds,
  total,
}: {
  resource?: "partners" | "sales-parts";
  filter: PartnerFilter;
  columns: readonly string[];
  selectedIds: readonly string[];
  total: number;
}) {
  const [open, setOpen] = useState(false),
    [scope, setScope] = useState("selected");
  const [pending, setPending] = useState(false),
    [error, setError] = useState<import("../../shared/api").ApiFailure | null>(
      null,
    );
  const [message, setMessage] = useState("");
  const inFlight = useRef(false);
  const lastDownload = useRef<Parameters<typeof downloadList> | null>(null);
  async function run(selected: boolean, retry = false) {
    if (inFlight.current) return;
    const request: Parameters<typeof downloadList> =
      retry && lastDownload.current
        ? lastDownload.current
        : [
            resource,
            { ...filter },
            [...columns],
            selected ? [...selectedIds] : undefined,
          ];
    lastDownload.current = request;
    inFlight.current = true;
    setPending(true);
    setError(null);
    setMessage("");
    try {
      await downloadList(...request);
      setOpen(false);
      setMessage("엑셀 파일을 내려받았습니다.");
    } catch (e) {
      const { failure } = await import("../admin/client");
      setError(failure(e));
    } finally {
      inFlight.current = false;
      setPending(false);
    }
  }
  return (
    <>
      <Button
        data-testid={`${resource}-export`}
        pending={pending}
        onClick={() => {
          setError(null);
          setMessage("");
          if (selectedIds.length) {
            setScope("selected");
            setOpen(true);
          } else void run(false);
        }}
      >
        엑셀 다운로드
      </Button>
      {message && <span role="status">{message}</span>}
      {!open && (
        <ErrorBanner
          error={error}
          onRetry={() => {
            void run(false, true);
          }}
          pending={pending}
        />
      )}
      {open && (
        <Modal
          title="엑셀 다운로드 범위"
          onCancel={() => {
            if (!pending) setOpen(false);
          }}
        >
          <div
            data-screen-id={
              resource === "partners" ? "SCR-UI-15-M13" : "SCR-UI-15-M14"
            }
            data-testid="partner-export-scope"
            className={styles.exportScope}
          >
            <label>
              <input
                data-testid="export-selected"
                type="radio"
                name={`${resource}-export-scope`}
                disabled={pending}
                checked={scope === "selected"}
                onChange={() => setScope("selected")}
              />
              선택 {selectedIds.length}건
            </label>
            <label>
              <input
                data-testid="export-all"
                type="radio"
                name={`${resource}-export-scope`}
                disabled={pending}
                checked={scope === "all"}
                onChange={() => setScope("all")}
              />
              조회 전체 {total}건
            </label>
            <p>
              현재 페이지와 관계없이 조회 조건에 맞는 데이터를 내려받습니다.
            </p>
            <ErrorBanner
              error={error}
              onRetry={() => {
                void run(scope === "selected");
              }}
              pending={pending}
            />
            <div className={styles.actions}>
              <Button
                data-testid="export-cancel"
                disabled={pending}
                onClick={() => setOpen(false)}
              >
                취소
              </Button>
              <Button
                data-testid="export-confirm"
                variant="primary"
                pending={pending}
                onClick={() => {
                  void run(scope === "selected");
                }}
              >
                내려받기
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </>
  );
}
