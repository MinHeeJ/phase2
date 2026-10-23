import { useRef, useState } from "react";
import { Button, Modal, Badge, SuccessToast } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ApiFailure } from "../../shared/api";
import { failure } from "../admin/client";
import { PartnerMappingDialog } from "../partnersops/PartnerMappingDialog";
import {
  checkUploadFile,
  createUploadValidation,
  getUploadErrorFile,
  getUploadTemplate,
  saveUploadRecheck,
  saveValidatedUploadApply,
  type UploadIssue,
  type UploadResult,
} from "./client";
import styles from "./Uploads.module.css";

// SCR-UI-15-M1 and SCR-UI-15-M2 are local work windows, not independent routes.
export function UploadButton({
  form = "B.7",
  onApplied,
}: {
  form?: string;
  onApplied: (count: number) => void;
}) {
  const [open, setOpen] = useState(false),
    [result, setResult] = useState<UploadResult | null>(null),
    [file, setFile] = useState<File | null>(null);
  const [pending, setPending] = useState(""),
    [error, setError] = useState<ApiFailure | null>(null),
    [localError, setLocalError] = useState<string | null>(null);
  const [tab, setTab] = useState<"ERROR" | "WARN">("ERROR"),
    [mapping, setMapping] = useState<UploadIssue | null>(null),
    [mapped, setMapped] = useState(false),
    [toast, setToast] = useState("");
  const busy = useRef(false);
  const accept = (chosen: File | undefined) => {
    if (!chosen || busy.current) return;
    const invalid = checkUploadFile(chosen);
    setLocalError(invalid);
    setError(null);
    if (!invalid) setFile(chosen);
  };
  const show = (next: UploadResult) => {
    setResult(next);
    setTab(next.errorRows ? "ERROR" : "WARN");
    setMapped(false);
  };
  const request = async (label: string, action: () => Promise<void>) => {
    if (busy.current) return;
    busy.current = true;
    setPending(label);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(failure(e));
    } finally {
      busy.current = false;
      setPending("");
    }
  };
  const close = () => {
    if (!busy.current) {
      setOpen(false);
      setResult(null);
      setFile(null);
      setError(null);
      setLocalError(null);
    }
  };
  const errorBanner =
    error || localError ? (
      <div role="alert" className={styles.error}>
        {localError ?? error?.message}
        {error?.requestId && <p>문의 번호: {error.requestId}</p>}
        {error?.uncertainWrite && (
          <p>
            결과를 확인할 수 없습니다. 다시 검사하거나 새 파일을 선택해 주세요.
            자동으로 다시 반영하지 않습니다.
          </p>
        )}
      </div>
    ) : null;
  return (
    <section data-testid="partner-upload-actions" className={styles.actions}>
      <Can perm="UI-15.download">
        <Button
          data-testid="upload-template"
          disabled={!!pending}
          onClick={() =>
            void request("내려받는 중", async () => {
              await getUploadTemplate(form);
            })
          }
        >
          양식 내려받기
        </Button>
      </Can>
      <Can perm="UI-15.upload">
        <Button data-testid="upload-open" onClick={() => setOpen(true)}>
          엑셀 업로드
        </Button>
      </Can>
      {!open && errorBanner}
      {toast && <SuccessToast message={toast} onDismiss={() => setToast("")} />}
      {open && !result && (
        <Modal title="엑셀 업로드 · 거래처" onCancel={close}>
          <div
            data-testid="upload-select-dialog"
            data-screen-id="SCR-UI-15-M1"
            className={styles.content}
          >
            {errorBanner}
            <div
              className={styles.drop}
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                e.preventDefault();
                accept(e.dataTransfer.files[0]);
              }}
            >
              <p>파일을 끌어다 놓거나 파일을 선택해 주세요.</p>
              <label>
                파일 선택
                <input
                  data-testid="upload-file"
                  type="file"
                  accept=".xlsx"
                  disabled={!!pending}
                  onChange={(e) => accept(e.target.files?.[0])}
                />
              </label>
              {file && <p>선택 파일: {file.name}</p>}
            </div>
            <Can perm="UI-15.download">
              <Button
                data-testid="upload-template-link"
                disabled={!!pending}
                onClick={() =>
                  void request("내려받는 중", async () => {
                    await getUploadTemplate(form);
                  })
                }
              >
                양식 내려받기
              </Button>
            </Can>
            <p>오류가 하나라도 있으면 파일 전체를 반영하지 않습니다.</p>
            <p>xlsx · 10MB 이하 · 데이터 10,000행 이하</p>
            {pending && <p role="status">{pending}</p>}
            <footer className={styles.footer}>
              <Button
                data-testid="upload-cancel"
                disabled={!!pending}
                onClick={close}
              >
                취소
              </Button>
              <Button
                data-testid="upload-submit"
                variant="primary"
                disabled={!file || !!localError || !!pending}
                onClick={() => {
                  if (!file) return;
                  void request("파일 확인 중", async () => {
                    const invalid = checkUploadFile(file);
                    if (invalid) {
                      setLocalError(invalid);
                      return;
                    }
                    setPending("검사 중");
                    show(await createUploadValidation(form, file));
                  });
                }}
              >
                올리기
              </Button>
            </footer>
          </div>
        </Modal>
      )}
      {open && result && (
        <Modal title="엑셀 업로드 결과 · 거래처" onCancel={close}>
          <div
            data-testid="upload-result-dialog"
            data-screen-id="SCR-UI-15-M2"
            className={styles.content}
          >
            {errorBanner}
            <p>
              {result.fileName} · 전체 {result.totalRows}행
            </p>
            <div className={styles.summary}>
              <Badge tone="success">성공 {result.okRows}</Badge>
              <Badge tone="warning">경고 {result.warningRows}</Badge>
              <Badge tone="danger">오류 {result.errorRows}</Badge>
            </div>
            <p>
              오류가 하나라도 있으면 파일 전체를 반영하지 않습니다. 경고는
              반영을 막지 않습니다.
            </p>
            <div className={styles.tabs} role="tablist" aria-label="검사 결과">
              <Button
                data-testid="upload-errors-tab"
                role="tab"
                aria-selected={tab === "ERROR"}
                onClick={() => setTab("ERROR")}
              >
                오류 ({result.errorRows})
              </Button>
              <Button
                data-testid="upload-warnings-tab"
                role="tab"
                aria-selected={tab === "WARN"}
                onClick={() => setTab("WARN")}
              >
                경고 ({result.warningRows})
              </Button>
              <Button
                data-testid="upload-recheck"
                variant={mapped ? "primary" : "secondary"}
                disabled={!result.upldId || !!pending}
                onClick={() => {
                  if (result.upldId)
                    void request("검사 중", async () =>
                      show(await saveUploadRecheck(result.upldId!)),
                    );
                }}
              >
                다시 검사
              </Button>
            </div>
            {!result.upldId && (
              <p>
                반영 전에는 다시 검사와 오류 목록 내려받기를 사용할 수 없습니다.
                새 파일을 선택할 수 있습니다.
              </p>
            )}
            {mapped && (
              <p role="status">대응을 저장했습니다. 다시 검사해 주세요.</p>
            )}
            <div
              role="tabpanel"
              data-testid="upload-issues-panel"
              className={styles.tableScroll}
            >
              <table>
                <thead>
                  <tr>
                    {["구분", "행", "열", "입력값", "이유", "조치"].map((t) => (
                      <th key={t}>{t}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {result.items
                    .filter((i) => i.severity === tab)
                    .sort((a, b) => a.rowNo - b.rowNo)
                    .map((issue, index) => (
                      <tr
                        data-testid={`upload-issue-${index}`}
                        key={`${issue.rowNo}-${index}`}
                      >
                        <td>{issue.severity === "ERROR" ? "오류" : "경고"}</td>
                        <td>{issue.rowNo}</td>
                        <td>{issue.column}</td>
                        <td>{issue.rawValue ?? "–"}</td>
                        <td>{issue.message}</td>
                        <td>
                          {issue.fix?.type === "MAPPING" && (
                            <Can perm="UI-15.create">
                              <Button
                                data-testid={`upload-map-${index}`}
                                disabled={!!pending}
                                onClick={() => setMapping(issue)}
                              >
                                대응 등록
                              </Button>
                            </Can>
                          )}
                        </td>
                      </tr>
                    ))}
                  {!result.items.some((i) => i.severity === tab) && (
                    <tr>
                      <td colSpan={6}>
                        {tab === "ERROR"
                          ? "오류가 없습니다."
                          : "경고가 없습니다."}
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            {pending && <p role="status">{pending}</p>}
            <footer className={styles.footer}>
              <Can perm="UI-15.download">
                <Button
                  data-testid="upload-error-file"
                  disabled={!result.upldId || !!pending}
                  onClick={() => {
                    if (result.upldId)
                      void request("내려받는 중", async () => {
                        await getUploadErrorFile(result.upldId!);
                      });
                  }}
                >
                  오류 목록 내려받기
                </Button>
              </Can>
              <Button
                data-testid="upload-new-file"
                disabled={!!pending}
                onClick={() => {
                  setResult(null);
                  setError(null);
                  setLocalError(null);
                }}
              >
                새 파일 선택
              </Button>
              <span className={styles.spacer} />
              <Button
                data-testid="upload-close"
                disabled={!!pending}
                onClick={close}
              >
                닫기
              </Button>
              <Can perm="UI-15.upload">
                <Button
                  data-testid="upload-apply"
                  variant="primary"
                  disabled={
                    result.errorRows !== 0 ||
                    !result.token ||
                    !!pending ||
                    !!error?.uncertainWrite
                  }
                  onClick={() => {
                    if (result.token)
                      void request("저장 중", async () => {
                        const applied = await saveValidatedUploadApply(
                          result.token!,
                        );
                        setOpen(false);
                        setResult(null);
                        setFile(null);
                        setToast(
                          `거래처 ${applied.appliedRows}건을 반영했습니다.`,
                        );
                        onApplied(applied.appliedRows);
                      });
                  }}
                >
                  반영
                </Button>
              </Can>
            </footer>
          </div>
        </Modal>
      )}
      {mapping?.fix && (
        <PartnerMappingDialog
          label={mapping.rawValue ?? ""}
          group={mapping.fix.group}
          onCancel={() => setMapping(null)}
          onSaved={() => {
            setMapped(true);
            setMapping(null);
          }}
        />
      )}
    </section>
  );
}
