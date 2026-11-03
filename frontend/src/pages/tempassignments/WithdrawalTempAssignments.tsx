import { useCallback, useState } from "react";
import {
  Badge,
  Button,
  DataTable,
  Modal,
  Skeleton,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import {
  TempAssignmentForm,
  TempAssignmentWarnings,
} from "./TempAssignmentPanel";
import * as client from "./client";
import type { NextSlot, TempAssignment } from "./client";
import styles from "./TempAssignments.module.css";

// SCR-UI-05: append below the existing actual next assignment; never replace that column's source.
export function WithdrawalTempAssignments({
  asgnId,
  devId,
  onChanged,
}: {
  asgnId: number;
  devId: number;
  onChanged: () => Promise<unknown>;
}) {
  const state = useAdminRead(
    useCallback(() => client.getWithdrawalNextSlots(asgnId), [asgnId]),
  );
  const [visible, setVisible] = useState(false);
  const [selected, setSelected] = useState<NextSlot | null>(null);
  const [releasing, setReleasing] = useState<TempAssignment | null>(null);
  const refresh = async () => {
    await state.reload();
    window.dispatchEvent(new Event(client.TEMP_ASSIGNMENTS_CHANGED));
    await onChanged();
  };
  const write = useAdminWrite(refresh);
  return (
    <section
      data-testid={`withdrawal-temp-assignments-${asgnId}`}
      data-screen-id="SCR-UI-05"
    >
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        state.data && (
          <>
            {state.data.recommendation_count > 0 ? (
              <Button
                small
                data-testid={`withdrawal-recommendations-${asgnId}`}
                onClick={() => setVisible(true)}
              >
                추천 {state.data.recommendation_count}곳
              </Button>
            ) : (
              <span>맞는 자리 없음</span>
            )}
            {state.data.next_temp_assignments.map((row) => (
              <div key={row.id} data-testid={`withdrawal-temp-row-${row.id}`}>
                <Badge tone="info">다음 가배정</Badge> {row.bgn_ym} ~{" "}
                {row.end_ym} · {row.mth_mm_qty.toFixed(2)} MM
                <TempAssignmentWarnings warnings={row.warnings} />
                {row.status === "ACTIVE" && (
                  <Button
                    small
                    data-testid={`withdrawal-temp-release-${row.id}`}
                    onClick={() => setReleasing(row)}
                  >
                    해제
                  </Button>
                )}
              </div>
            ))}
          </>
        )
      )}
      {visible && state.data && (
        <Modal title="다음 자리 추천" onCancel={() => setVisible(false)}>
          <div data-testid="withdrawal-recommendation-dialog">
            <p className={styles.info}>
              추천은 정보 제공이며 자동 확정 투입이 아닙니다.
            </p>
            <ErrorBanner
              error={write.error}
              onRetry={() => void write.recover()}
            />
            <DataTable
              rows={state.data.items}
              total={state.data.recommendation_count}
              rowKey={(r) => String(r.need_id)}
              columns={[
                {
                  key: "name",
                  label: "프로젝트/기회",
                  render: (r) => (
                    <span data-testid={`recommendation-row-${r.need_id}`}>
                      {r.oppt_nm}
                    </span>
                  ),
                },
                {
                  key: "job",
                  label: "필요직무·등급",
                  render: (r) => `${r.job_cd} · ${r.grd_cd ?? "–"}`,
                },
                { key: "period", label: "시작월", render: (r) => r.bgn_ym },
                {
                  key: "slots",
                  label: "남은 자리",
                  kind: "number",
                  render: (r) => r.remaining_slots,
                },
                {
                  key: "score",
                  label: "점수·세부 근거",
                  render: (r) => (
                    <>
                      <strong>{r.mtch_scr}점</strong>
                      <p>
                        직무 {r.score_components.job} · 등급{" "}
                        {r.score_components.grade} · 공백{" "}
                        {r.score_components.gap} · 고객{" "}
                        {r.score_components.customer} · 확률{" "}
                        {r.score_components.probability}
                      </p>
                    </>
                  ),
                },
                {
                  key: "wait",
                  label: "대기일수·수주확률",
                  render: (r) => `${r.waiting_days}일 · ${r.win_prblt_rt}%`,
                },
                {
                  key: "warnings",
                  label: "경고",
                  render: (r) => (
                    <TempAssignmentWarnings warnings={r.warnings} />
                  ),
                },
                {
                  key: "action",
                  label: "조작",
                  render: (r) => (
                    <>
                      <Button
                        small
                        data-testid={`recommendation-assign-${r.need_id}`}
                        disabled={r.remaining_slots < 1 || !r.end_ym}
                        onClick={() => setSelected(r)}
                      >
                        가배정
                      </Button>
                      {!r.end_ym && <small>종료월 자료가 없습니다.</small>}
                    </>
                  ),
                },
              ]}
            />
            <Button
              data-testid="withdrawal-recommendation-close"
              onClick={() => setVisible(false)}
            >
              닫기
            </Button>
          </div>
        </Modal>
      )}
      {selected?.end_ym && (
        <TempAssignmentForm
          need={{
            need_id: selected.need_id,
            bgn_ym: selected.bgn_ym,
            end_ym: selected.end_ym,
            mth_mm_qty: 1,
            label: selected.oppt_nm,
          }}
          devId={devId}
          source="NEXT"
          reload={refresh}
          onCancel={() => setSelected(null)}
          onSaved={() => setSelected(null)}
        />
      )}
      {releasing && (
        <Modal
          title="가배정 해제"
          onCancel={() => {
            if (!write.pending) setReleasing(null);
          }}
          confirmation
        >
          <div data-testid="withdrawal-temp-release-dialog">
            <p>다음 가배정을 해제할까요? 확정 다음 배정은 바뀌지 않습니다.</p>
            <ErrorBanner
              error={write.error}
              pending={write.pending}
              onRetry={() => void write.recover()}
            />
            <Button
              data-testid="withdrawal-temp-release-cancel"
              disabled={write.pending}
              onClick={() => setReleasing(null)}
            >
              취소
            </Button>
            <Button
              data-testid="withdrawal-temp-release-confirm"
              pending={write.pending}
              disabled={write.uncertain}
              onClick={() =>
                void write.save(
                  () => client.releaseTempAssignment(releasing.id),
                  () => setReleasing(null),
                )
              }
            >
              해제 확인
            </Button>
          </div>
        </Modal>
      )}
    </section>
  );
}
