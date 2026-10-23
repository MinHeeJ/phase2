import { useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { getSalesPartExport } from "./client";
import { useSalesPartMobile } from "./SalesPartActions";
import styles from "./SalesPartActions.module.css";

export function SalesPartDownload({
  columns,
  selectedIds,
  total,
}: {
  columns: readonly string[];
  selectedIds: readonly string[];
  total: number;
}) {
  const mobile = useSalesPartMobile();
  const [open, setOpen] = useState(false),
    [range, setRange] = useState("selected"),
    [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null),
    [success, setSuccess] = useState("");
  const download = async (ids?: readonly string[]) => {
    if (pending) return;
    setPending(true);
    setError(null);
    setSuccess("");
    try {
      const file = await getSalesPartExport(columns, ids);
      const url = URL.createObjectURL(file.blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = file.filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 0);
      setOpen(false);
      setSuccess(`${file.filename} 파일을 내려받았습니다.`);
    } catch (caught) {
      setError(
        caught instanceof ApiFailure
          ? caught
          : new ApiFailure("파일을 내려받지 못했습니다.", null, null),
      );
    } finally {
      setPending(false);
    }
  };
  if (mobile) return null;
  return (
    <section
      data-testid="sales-part-download"
      className={styles.writeActions}
      aria-label="영업 파트 다운로드"
    >
      <Button
        data-testid="sales-part-download-open"
        pending={pending}
        onClick={() => {
          if (selectedIds.length) {
            setRange("selected");
            setOpen(true);
          } else void download();
        }}
      >
        엑셀 다운로드
      </Button>
      {success && <p role="status">{success}</p>}
      {!open && (
        <ErrorBanner
          error={error}
          onRetry={() => {
            void download();
          }}
        />
      )}
      {open && (
        <Modal
          title="영업 파트 다운로드 범위"
          onCancel={() => {
            if (!pending) setOpen(false);
          }}
        >
          <div
            data-testid="sales-part-download-dialog"
            data-screen-id="SCR-UI-15-M14"
          >
            <label>
              <input
                data-testid="sales-part-download-selected"
                type="radio"
                name="sales-part-download-range"
                value="selected"
                checked={range === "selected"}
                disabled={pending}
                onChange={() => setRange("selected")}
              />
              선택한 {selectedIds.length}건만
            </label>
            <label>
              <input
                data-testid="sales-part-download-all"
                type="radio"
                name="sales-part-download-range"
                value="all"
                checked={range === "all"}
                disabled={pending}
                onChange={() => setRange("all")}
              />
              조회된 전체 {total}건
            </label>
            <ErrorBanner
              error={error}
              pending={pending}
              onRetry={() => {
                void download(range === "selected" ? selectedIds : undefined);
              }}
            />
            <div className={styles.buttons}>
              <Button
                data-testid="sales-part-download-cancel"
                disabled={pending}
                onClick={() => setOpen(false)}
              >
                취소
              </Button>
              <Button
                data-testid="sales-part-download-confirm"
                variant="primary"
                pending={pending}
                onClick={() => {
                  void download(range === "selected" ? selectedIds : undefined);
                }}
              >
                내려받기
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </section>
  );
}
