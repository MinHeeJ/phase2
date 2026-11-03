import { useCallback, useEffect, useRef, useState } from "react";
import {
  Badge,
  Button,
  DataTable,
  Modal,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { TextInput, SelectInput } from "../../shared/Inputs";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { listDeveloperOptions } from "../developersops/client";
import * as client from "./client";
import type { TempAssignment, TempAssignmentNeed } from "./client";
import styles from "./TempAssignments.module.css";

export function TempAssignmentWarnings({ warnings }: { warnings: string[] }) {
  return (
    <>
      {warnings.map((warning) => (
        <p className={styles.warning} key={warning}>
          <Badge tone="warning">경고</Badge> {warning}
        </p>
      ))}
    </>
  );
}

// SCR-UI-29 need-row region; SCR-UI-05 recommendation region uses the same persisted overlay.
export function TempAssignmentPanel({
  need,
  devId,
  source = "MANUAL",
  open = true,
  onChanged,
}: {
  need: TempAssignmentNeed;
  devId?: number;
  source?: "MANUAL" | "NEXT";
  open?: boolean;
  onChanged: () => Promise<unknown>;
}) {
  const [page, setPage] = useState(0);
  const state = useAdminRead(
    useCallback(
      () =>
        client.listTempAssignments({
          need_id: need.need_id,
          dev_id: devId,
          page,
          size: 50,
        }),
      [need.need_id, devId, page],
    ),
  );
  const [editing, setEditing] = useState(false);
  const [releasing, setReleasing] = useState<TempAssignment | null>(null);
  const [message, setMessage] = useState("");
  const refresh = async () => {
    await state.reload();
    window.dispatchEvent(new Event(client.TEMP_ASSIGNMENTS_CHANGED));
    await onChanged();
  };
  const write = useAdminWrite(refresh);
  useEffect(() => {
    setEditing(false);
    setReleasing(null);
    setPage(0);
    setMessage("");
  }, [need.need_id, devId, open]);
  const ready = !state.loading && !state.error && !!state.data;
  return (
    <section
      data-testid="temp-assignment-panel"
      data-screen-id={source === "NEXT" ? "SCR-UI-05" : "SCR-UI-29"}
      aria-label="다음 가배정"
    >
      <div className={styles.toolbar}>
        <h3>다음 가배정</h3>
        {open && ready && (
          <Button
            data-testid="temp-assignment-add"
            onClick={() => setEditing(true)}
          >
            가배정
          </Button>
        )}
      </div>
      <p className={styles.info}>
        추천은 정보 제공이며 자동 확정 투입이 아닙니다. 실제 MM·다음
        배정·청구·원가에는 포함되지 않습니다.
      </p>
      {!open && (
        <p className={styles.info}>
          종료된 기회입니다. 가배정 기록은 읽기만 할 수 있습니다.
        </p>
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      <ErrorBanner
        error={write.error}
        pending={write.pending}
        onRetry={() => void write.recover()}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        ready && (
          <>
            <DataTable
              rows={state.data!.items}
              total={state.data!.total}
              rowKey={(r) => String(r.id)}
              columns={[
                {
                  key: "dev",
                  label: "개발자",
                  render: (r) => (
                    <span data-testid={`temp-assignment-row-${r.id}`}>
                      개발자 #{r.dev_id}
                    </span>
                  ),
                },
                {
                  key: "period",
                  label: "기간",
                  render: (r) => `${r.bgn_ym} ~ ${r.end_ym}`,
                },
                {
                  key: "mm",
                  label: "월 MM",
                  kind: "number",
                  render: (r) => r.mth_mm_qty.toFixed(2),
                },
                {
                  key: "source",
                  label: "출처·점수",
                  render: (r) =>
                    r.tmpr_asgn_src_cd === "NEXT"
                      ? `추천 · ${r.mtch_scr ?? "–"}점`
                      : "직접 선택",
                },
                {
                  key: "status",
                  label: "상태",
                  render: (r) => (
                    <Badge
                      tone={
                        r.status === "ACTIVE"
                          ? "info"
                          : r.status === "CONVERTED"
                            ? "success"
                            : "locked"
                      }
                    >
                      {r.status === "ACTIVE"
                        ? "가배정"
                        : r.status === "CONVERTED"
                          ? "확정 전환"
                          : "해제"}
                    </Badge>
                  ),
                },
                {
                  key: "warnings",
                  label: "충돌 경고",
                  render: (r) => (
                    <TempAssignmentWarnings warnings={r.warnings} />
                  ),
                },
                {
                  key: "action",
                  label: "조작",
                  render: (r) =>
                    open &&
                    r.status === "ACTIVE" && (
                      <Button
                        small
                        data-testid={`temp-assignment-release-${r.id}`}
                        disabled={write.pending || write.uncertain}
                        onClick={() => setReleasing(r)}
                      >
                        해제
                      </Button>
                    ),
                },
              ]}
            />
            <div className={styles.actions}>
              <Button
                data-testid="temp-assignment-previous"
                disabled={page === 0}
                onClick={() => setPage(page - 1)}
              >
                이전
              </Button>
              <span>{page + 1} 페이지</span>
              <Button
                data-testid="temp-assignment-next"
                disabled={(page + 1) * 50 >= state.data!.total}
                onClick={() => setPage(page + 1)}
              >
                다음
              </Button>
            </div>
          </>
        )
      )}
      {open && editing && (
        <TempAssignmentForm
          key={`${need.need_id}-${devId}`}
          need={need}
          devId={devId}
          source={source}
          reload={refresh}
          onCancel={() => setEditing(false)}
          onSaved={() => {
            setEditing(false);
            setMessage("가배정을 저장했습니다.");
          }}
        />
      )}
      {open && releasing && (
        <Modal
          title="가배정 해제"
          onCancel={() => {
            if (!write.pending) setReleasing(null);
          }}
          confirmation
        >
          <div data-testid="temp-assignment-release-dialog">
            <p>
              {releasing.bgn_ym} ~ {releasing.end_ym} 가배정을 해제할까요? 원본
              기록은 보존됩니다.
            </p>
            <ErrorBanner
              error={write.error}
              pending={write.pending}
              onRetry={() => void write.recover()}
            />
            <div className={styles.actions}>
              <Button
                data-testid="temp-assignment-release-cancel"
                disabled={write.pending}
                onClick={() => setReleasing(null)}
              >
                취소
              </Button>
              <Button
                data-testid="temp-assignment-release-confirm"
                disabled={write.pending || write.uncertain}
                onClick={() =>
                  void write.save(
                    () => client.releaseTempAssignment(releasing.id),
                    () => {
                      setReleasing(null);
                      setMessage("가배정을 해제했습니다.");
                    },
                  )
                }
              >
                해제 확인
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </section>
  );
}

export function TempAssignmentForm({
  need,
  devId,
  source,
  reload,
  onCancel,
  onSaved,
}: {
  need: TempAssignmentNeed;
  devId?: number;
  source: "NEXT" | "MANUAL";
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const options = useAdminRead(
    useCallback(
      () => (devId ? Promise.resolve([]) : listDeveloperOptions()),
      [devId],
    ),
  );
  const [developer, setDeveloper] = useState(String(devId ?? ""));
  const [from, setFrom] = useState(need.bgn_ym),
    [to, setTo] = useState(need.end_ym),
    [mm, setMm] = useState(String(need.mth_mm_qty));
  const [validation, setValidation] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const write = useAdminWrite(reload);
  const form = useRef<HTMLFormElement>(null);
  const error = validation ?? write.error;
  useEffect(() => {
    if (error?.field)
      form.current
        ?.querySelector<HTMLElement>(
          `[name="${error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [error]);
  const blocked =
    write.pending || write.uncertain || options.loading || !!options.error;
  const change = (setter: (value: string) => void) => (value: string) => {
    setter(value);
    setValidation(null);
    write.clear();
  };
  const fieldError = (key: string) =>
    error?.field === key ? error.message : null;
  const save = () => {
    if (blocked) return;
    let field = "";
    if (!/^[1-9]\d*$/.test(developer)) field = "dev_id";
    else if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(from)) field = "bgn_ym";
    else if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(to) || from > to) field = "end_ym";
    else if (
      !/^(?:0(?:\.\d{1,2})?|1(?:\.0{1,2})?)$/.test(mm) ||
      Number(mm) < 0.01
    )
      field = "mth_mm_qty";
    if (field) {
      setValidation({
        field,
        message:
          field === "mth_mm_qty"
            ? "월 MM은 0.01~1.00으로 입력하세요."
            : field === "end_ym"
              ? "종료월은 시작월 이후여야 합니다."
              : "입력값을 확인하세요.",
      });
      return;
    }
    void write.save(
      () =>
        client.createTempAssignment({
          need_id: need.need_id,
          dev_id: Number(developer),
          bgn_ym: from,
          end_ym: to,
          mth_mm_qty: Number(mm),
          tmpr_asgn_src_cd: source,
        }),
      onSaved,
    );
  };
  return (
    <Modal
      title="가배정"
      onCancel={() => {
        if (!write.pending) onCancel();
      }}
    >
      <form
        ref={form}
        data-testid="temp-assignment-form"
        className={styles.form}
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          save();
        }}
      >
        <p>{need.label}</p>
        <p className={styles.info}>
          시작월·종료월을 모두 포함합니다. 충돌은 경고하며 저장할 수 있습니다.
          동일 필요·개발자의 겹치는 가배정은 저장할 수 없습니다.
        </p>
        <ErrorBanner
          error={options.error}
          onRetry={() => void options.reload().catch(() => {})}
        />
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => void write.recover()}
        />
        {devId ? (
          <p>선택 개발자 #{devId} · 추천 점수는 서버에서 다시 계산합니다.</p>
        ) : (
          <SelectInput
            label="개발자"
            name="dev_id"
            required
            value={developer}
            onValueChange={change(setDeveloper)}
            error={fieldError("dev_id")}
            disabled={blocked}
            options={(options.data ?? []).map((d) => ({
              value: String(d.id),
              label: `${d.name} (${d.mngNo})`,
            }))}
          />
        )}
        <TextInput
          label="시작월"
          name="bgn_ym"
          type="month"
          required
          value={from}
          onValueChange={change(setFrom)}
          error={fieldError("bgn_ym")}
          disabled={write.pending || write.uncertain}
        />
        <TextInput
          label="종료월"
          name="end_ym"
          type="month"
          required
          value={to}
          onValueChange={change(setTo)}
          error={fieldError("end_ym")}
          disabled={write.pending || write.uncertain}
        />
        <TextInput
          label="월 MM"
          name="mth_mm_qty"
          required
          value={mm}
          onValueChange={change(setMm)}
          error={fieldError("mth_mm_qty")}
          disabled={write.pending || write.uncertain}
        />
        <div className={styles.actions}>
          <Button
            type="button"
            data-testid="temp-assignment-cancel"
            disabled={write.pending}
            onClick={onCancel}
          >
            취소
          </Button>
          <Button
            type="submit"
            data-testid="temp-assignment-save"
            variant="primary"
            pending={write.pending}
            disabled={blocked}
          >
            가배정 저장
          </Button>
        </div>
      </form>
    </Modal>
  );
}
