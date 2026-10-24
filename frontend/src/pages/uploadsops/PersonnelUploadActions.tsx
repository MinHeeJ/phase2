import { useEffect, useState } from "react";
import { Button } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { UploadDialogs } from "./UploadDialogs";
import {
  downloadUploadFile,
  uploadFailure,
  uploadTemplatePath,
  type UploadApplyResult,
} from "./client";
import styles from "./Uploads.module.css";

export function PersonnelUploadActions({
  onApplied,
}: {
  onApplied: (result: UploadApplyResult) => void | Promise<unknown>;
}) {
  const [form, setForm] = useState<"B.8" | "B.1" | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [mobile, setMobile] = useState(
    () =>
      typeof window.matchMedia === "function" &&
      window.matchMedia("(max-width: 767px)").matches,
  );
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const media = window.matchMedia("(max-width: 767px)");
    const changed = () => {
      setMobile(media.matches);
      if (media.matches) setForm(null);
    };
    media.addEventListener("change", changed);
    return () => media.removeEventListener("change", changed);
  }, []);
  if (mobile) return null;
  return (
    <section
      data-testid="personnel-upload-actions"
      data-screen-id="SCR-UI-02"
      className={styles.tools}
    >
      <Can perm="UI-02.download">
        <Button
          data-testid="developer-upload-template"
          onClick={() => {
            setError(null);
            void downloadUploadFile(
              uploadTemplatePath("B.8"),
              "개발자_업로드양식.xlsx",
            ).catch((e) => setError(uploadFailure(e)));
          }}
        >
          양식 내려받기
        </Button>
      </Can>
      <Can perm="UI-02.upload">
        <Button
          data-testid="developer-upload-open"
          onClick={() => setForm("B.8")}
        >
          엑셀 업로드
        </Button>
        <Button data-testid="salary-upload-open" onClick={() => setForm("B.1")}>
          급여 업로드
        </Button>
      </Can>
      <ErrorBanner error={error} onRetry={() => setError(null)} />
      {form && (
        <UploadDialogs
          form={form}
          onClose={() => setForm(null)}
          onApplied={onApplied}
        />
      )}
    </section>
  );
}
