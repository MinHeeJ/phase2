import { useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminWrite } from "../admin/useAdminResource";
import { failure } from "../admin/client";
import {
  disableDevelopers,
  enableDeveloper,
  downloadDevelopers,
  type DeveloperRow,
  type DeveloperBlock,
  type DeveloperFilter,
} from "./client";
import styles from "./DeveloperLifecycle.module.css";

export type DeveloperDisableRow = Pick<
  DeveloperRow,
  "dev_id" | "dev_nm" | "use_yn" | "current_assignment_count"
>;
// SCR-UI-02-M3: local confirmation, never a separate browser route.
export function DeveloperDisableDialog({
  rows,
  onClose,
  reload,
  onComplete,
}: {
  rows: DeveloperDisableRow[];
  onClose: () => void;
  reload: () => Promise<unknown>;
  onComplete: (message: string) => void;
}) {
  const write = useAdminWrite(reload);
  const [blocked, setBlocked] = useState<DeveloperBlock[] | null>(null);
  const [finished, setFinished] = useState(false);
  const known = rows.filter(
    (r) => r.use_yn === "Y" && r.current_assignment_count > 0,
  );
  const allBlocked =
    finished ||
    write.error?.code === "DEVELOPER_IN_USE" ||
    (known.length > 0 && known.length === rows.length);
  return (
    <Modal
      title={
        allBlocked
          ? "사용을 중지할 수 없습니다"
          : `${rows.length}명의 사용을 중지할까요?`
      }
      confirmation
      onCancel={() => {
        if (!write.pending) onClose();
      }}
    >
      <section
        data-screen-id="SCR-UI-02-M3"
        data-testid="developer-disable-dialog"
      >
        <p>
          {rows.map((r) => r.dev_nm).join(", ")} — 중지하면 새 투입에서 고를 수
          없습니다. 기록은 그대로 남습니다.
        </p>
        {(known.length > 0 || !!blocked?.length) && (
          <div className={styles.warning}>
            <p>아래 사람은 투입 중이라 중지할 수 없습니다.</p>
            {blocked
              ? blocked.map((r) => (
                  <p key={r.id} data-testid={`developer-blocked-${r.id}`}>
                    {r.name} — 현재 투입 {r.current_assignment_count}건
                  </p>
                ))
              : known.map((r) => (
                  <p
                    key={r.dev_id}
                    data-testid={`developer-blocked-${r.dev_id}`}
                  >
                    {r.dev_nm} — 현재 투입 {r.current_assignment_count}건
                  </p>
                ))}
          </div>
        )}
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => {
            void write.recover();
          }}
        />
        <div className={styles.actions}>
          <Button
            data-testid="developer-disable-cancel"
            disabled={write.pending}
            onClick={onClose}
          >
            {allBlocked ? "닫기" : "취소"}
          </Button>
          {!allBlocked && (
            <Can perm="UI-02.delete">
              <Button
                data-testid="developer-disable-confirm"
                className={styles.danger}
                pending={write.pending}
                disabled={write.uncertain || !!write.error}
                onClick={() => {
                  void write.save(
                    async () => {
                      const result = await disableDevelopers(
                        rows.map((r) => r.dev_id),
                      );
                      setBlocked(result.blocked);
                      if (
                        result.disabled_count === 0 &&
                        result.blocked_count > 0
                      ) {
                        setFinished(true);
                        return;
                      }
                      onComplete(
                        `${result.disabled_count}명의 사용을 중지했습니다.${result.blocked_count ? ` (${result.blocked_count}명은 투입 중이라 제외)` : ""}`,
                      );
                      onClose();
                    },
                    () => {},
                  );
                }}
              >
                사용 중지
              </Button>
            </Can>
          )}
        </div>
      </section>
    </Modal>
  );
}
export function DeveloperEnableButton({
  id,
  reload,
  onComplete,
}: {
  id: number;
  reload: () => Promise<unknown>;
  onComplete: (message: string) => void;
}) {
  const write = useAdminWrite(reload);
  return (
    <Can perm="UI-02.delete">
      <div data-testid="developer-enable-control" className={styles.mutate}>
        <Button
          data-testid="developer-enable"
          pending={write.pending}
          disabled={write.uncertain || !!write.error}
          onClick={() => {
            void write.save(
              () => enableDeveloper(id),
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
    </Can>
  );
}
export function DeveloperExportButton({ filter }: { filter: DeveloperFilter }) {
  const [pending, setPending] = useState(false),
    [error, setError] = useState<ReturnType<typeof failure> | null>(null),
    [message, setMessage] = useState("");
  const run = async () => {
    if (pending) return;
    setPending(true);
    setError(null);
    setMessage("");
    try {
      await downloadDevelopers(filter);
      setMessage("엑셀 파일을 내려받았습니다.");
    } catch (e) {
      setError(failure(e));
    } finally {
      setPending(false);
    }
  };
  return (
    <Can perm="UI-02.download">
      <div data-testid="developer-export-control">
        <Button
          data-testid="developer-export"
          pending={pending}
          onClick={() => {
            void run();
          }}
        >
          엑셀 다운로드
        </Button>
        {message && <span role="status">{message}</span>}
        <ErrorBanner
          error={error}
          pending={pending}
          onRetry={() => {
            void run();
          }}
        />
      </div>
    </Can>
  );
}
