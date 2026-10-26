import { useEffect, useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminWrite } from "../admin/useAdminResource";
import { failure } from "../admin/client";
import { useSharedMobile } from "../shared/PlaceholderScreen";
import {
  downloadProjects,
  updateProjectDisable,
  updateProjectEnable,
  type ProjectExportFilter,
} from "./client";
import styles from "./ProjectLifecycle.module.css";

// SCR-UI-01-M2: local confirmation inside /UI-01.
export function ProjectLifecycleActions({
  projectId,
  useYn,
  reload,
  onComplete,
}: {
  projectId: number;
  useYn: "Y" | "N";
  reload: () => Promise<unknown>;
  onComplete: (message: string) => void;
}) {
  const mobile = useSharedMobile();
  const [confirm, setConfirm] = useState(false);
  const write = useAdminWrite(reload);
  const blocked = write.error?.code === "PROJECT_IN_USE";
  useEffect(() => {
    if (mobile) setConfirm(false);
  }, [mobile]);
  if (mobile)
    return useYn === "N" ? (
      <p className={styles.warning}>사용 안 함으로 바꾼 프로젝트입니다.</p>
    ) : null;
  return (
    <Can perm="UI-01.delete">
      <section data-testid="project-lifecycle-actions">
        {useYn === "N" ? (
          <>
            <p className={styles.warning}>
              사용 안 함으로 바꾼 프로젝트입니다.
            </p>
            <Button
              data-testid="project-enable"
              pending={write.pending}
              disabled={write.uncertain || !!write.error}
              onClick={() =>
                void write.save(
                  () => updateProjectEnable(projectId),
                  () => onComplete("다시 사용하도록 변경했습니다."),
                )
              }
            >
              다시 사용
            </Button>
            <ErrorBanner
              error={write.error}
              onRetry={() => void write.recover()}
            />
          </>
        ) : (
          <Button
            data-testid="project-disable-open"
            onClick={() => {
              write.clear();
              setConfirm(true);
            }}
          >
            사용 안 함
          </Button>
        )}
        {confirm && (
          <Modal
            confirmation
            title={
              blocked
                ? "사용 안 함으로 바꿀 수 없습니다"
                : "이 프로젝트를 사용 안 함으로 바꿀까요?"
            }
            onCancel={() => {
              if (!write.pending) setConfirm(false);
            }}
          >
            <section
              data-screen-id="SCR-UI-01-M2"
              data-testid="project-disable-dialog"
            >
              {!blocked && (
                <p>목록과 선택 목록에서 빠집니다. 기록은 그대로 남습니다.</p>
              )}
              <ErrorBanner
                error={write.error}
                pending={write.pending}
                onRetry={blocked ? undefined : () => void write.recover()}
              />
              <div className={styles.actions}>
                <Button
                  data-testid="project-disable-cancel"
                  disabled={write.pending}
                  onClick={() => setConfirm(false)}
                >
                  {blocked ? "확인" : "취소"}
                </Button>
                {!blocked && (
                  <Button
                    data-testid="project-disable-confirm"
                    className={styles.danger}
                    pending={write.pending}
                    disabled={write.uncertain || !!write.error}
                    onClick={() =>
                      void write.save(
                        () => updateProjectDisable(projectId),
                        () => {
                          setConfirm(false);
                          onComplete("사용 안 함으로 변경했습니다.");
                        },
                      )
                    }
                  >
                    사용 안 함
                  </Button>
                )}
              </div>
            </section>
          </Modal>
        )}
      </section>
    </Can>
  );
}
export function ProjectExportButton({
  filter,
}: {
  filter: ProjectExportFilter;
}) {
  const [pending, setPending] = useState(false),
    [error, setError] = useState<ReturnType<typeof failure> | null>(null),
    [message, setMessage] = useState("");
  const run = async () => {
    if (pending) return;
    setPending(true);
    setError(null);
    setMessage("");
    try {
      await downloadProjects(filter);
      setMessage("엑셀 파일을 내려받았습니다.");
    } catch (e) {
      setError(failure(e));
    } finally {
      setPending(false);
    }
  };
  return (
    <Can perm="UI-01.download">
      <div data-testid="project-export-control">
        <Button
          data-testid="project-export"
          pending={pending}
          onClick={() => void run()}
        >
          엑셀 다운로드
        </Button>
        {message && <span role="status">{message}</span>}
        <ErrorBanner
          error={error}
          pending={pending}
          onRetry={() => void run()}
        />
      </div>
    </Can>
  );
}
