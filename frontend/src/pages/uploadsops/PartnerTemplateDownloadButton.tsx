import { useRef, useState } from "react";

import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import {
  downloadUploadFile,
  uploadTemplatePath,
  uploadFailure,
} from "./client";

export function PartnerTemplateDownloadButton({
  className,
}: {
  className?: string;
}) {
  const busy = useRef(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const download = async () => {
    if (busy.current) return;
    busy.current = true;
    setPending(true);
    setError(null);
    try {
      await downloadUploadFile(
        uploadTemplatePath("B.7"),
        "거래처_업로드양식.xlsx",
      );
    } catch (e) {
      setError(uploadFailure(e));
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  return (
    <div data-testid="partner-template-action">
      <a
        data-testid="partner-template-download"
        className={className}
        href={uploadTemplatePath("B.7")}
        aria-disabled={pending}
        aria-busy={pending}
        onClick={(event) => {
          event.preventDefault();
          void download();
        }}
      >
        양식 내려받기
      </a>
      <ErrorBanner
        error={error}
        pending={pending}
        onRetry={() => void download()}
      />
    </div>
  );
}
