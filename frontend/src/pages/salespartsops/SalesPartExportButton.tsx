import { useRef, useState } from "react";
import { Can } from "../../shared/Can";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { failure } from "../admin/client";
import { getSalesPartExport } from "./client";
import styles from "./SalesPartManagement.module.css";

// SCR-UI-15-M14. The owning list supplies its visible column order, never its page rows.
export function SalesPartExportButton({
  columns,
  selectedIds,
  total,
  sort,
}: {
  columns: readonly string[];
  selectedIds: readonly string[];
  total: number;
  sort?: string;
}) {
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState(true);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const busy = useRef(false);
  const download = async (onlySelected: boolean) => {
    if (busy.current) return;
    busy.current = true;
    setPending(true);
    setError(null);
    try {
      const file = await getSalesPartExport(
        columns,
        onlySelected ? selectedIds : undefined,
        sort,
      );
      const url = URL.createObjectURL(file.blob);
      const anchor = document.createElement("a");
      try {
        anchor.href = url;
        anchor.download = file.filename;
        anchor.hidden = true;
        document.body.appendChild(anchor);
        anchor.click();
      } finally {
        anchor.remove();
        URL.revokeObjectURL(url);
      }
      setOpen(false);
    } catch (e) {
      setError(failure(e));
    } finally {
      setPending(false);
      busy.current = false;
    }
  };
  return (
    <section data-testid="sales-part-export" className={styles.export}>
      <Can perm="UI-15.download">
        <Button
          data-testid="sales-part-export-open"
          pending={pending}
          onClick={() => {
            setError(null);
            if (selectedIds.length) {
              setSelected(true);
              setOpen(true);
            } else void download(false);
          }}
        >
          엑셀 다운로드
        </Button>
      </Can>
      {!open && (
        <ErrorBanner
          error={error}
          onRetry={() => {
            void download(false);
          }}
          pending={pending}
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
            data-testid="sales-part-export-dialog"
            data-screen-id="SCR-UI-15-M14"
            className={styles.range}
          >
            <label>
              <input
                data-testid="sales-part-export-selected"
                name="sales-part-export-range"
                type="radio"
                checked={selected}
                disabled={pending}
                onChange={() => setSelected(true)}
              />
              선택한 {selectedIds.length}건만
            </label>
            <label>
              <input
                data-testid="sales-part-export-all"
                name="sales-part-export-range"
                type="radio"
                checked={!selected}
                disabled={pending}
                onChange={() => setSelected(false)}
              />
              조회된 전체 {total}건
            </label>
            <ErrorBanner
              error={error}
              onRetry={() => {
                void download(selected);
              }}
              pending={pending}
            />
            <div className={styles.footer}>
              <Button
                data-testid="sales-part-export-cancel"
                disabled={pending}
                onClick={() => setOpen(false)}
              >
                취소
              </Button>
              <Button
                data-testid="sales-part-export-confirm"
                variant="primary"
                pending={pending}
                onClick={() => {
                  void download(selected);
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
