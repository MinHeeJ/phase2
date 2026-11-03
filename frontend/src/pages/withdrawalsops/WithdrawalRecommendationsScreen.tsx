import { useCallback, useState } from "react";
import {
  Badge,
  Button,
  ConfirmDialog,
  Modal,
  Skeleton,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import UI05Screen, {
  type WithdrawalsScreenProps,
  type WithdrawalScreenContext,
} from "../us1/UI05Screen";
import type { WithdrawalRow } from "../us1/client";
import {
  createNextTemporaryAssignment,
  getWithdrawalNextSlots,
  releaseNextTemporaryAssignment,
  type NextSlot,
  type NextSlotsResult,
} from "./client";
import styles from "./NextSlots.module.css";

export function NextSlotsSummary({
  asgnId,
  version,
}: {
  asgnId: number;
  version: string;
}) {
  const read = useAdminRead(
    useCallback(() => getWithdrawalNextSlots(asgnId), [asgnId, version]),
  );
  return (
    <div data-testid="next-slots-summary">
      {read.loading ? (
        <Skeleton label="추천 현황 불러오는 중" />
      ) : (
        read.data &&
        !read.error && (
          <div className={styles.summary}>
            다음 배정 없는 {read.data.without_next_assignment_count}명 중{" "}
            {read.data.with_valid_slots_count}명은 맞는 자리 있음
          </div>
        )
      )}
      <ErrorBanner
        error={read.error}
        onRetry={() => void read.reload().catch(() => {})}
      />
    </div>
  );
}

export function NextSlotsCell({
  row,
  context,
  refreshVersion = 0,
}: {
  row: WithdrawalRow;
  context: WithdrawalScreenContext;
  refreshVersion?: number;
}) {
  const [open, setOpen] = useState(false);
  const [version, setVersion] = useState(0);
  const read = useAdminRead(
    useCallback(
      () => getWithdrawalNextSlots(row.asgn_id),
      [row.asgn_id, row.updated_at, version, refreshVersion],
    ),
  );
  const refresh = async () => {
    await read.reload();
    await context.refresh();
    setVersion((v) => v + 1);
  };
  return (
    <div
      className={styles.cell}
      data-testid={`next-slots-${row.asgn_id}`}
      onClick={(e) => e.stopPropagation()}
    >
      {read.loading ? (
        <Skeleton label="추천 불러오는 중" />
      ) : (
        read.data &&
        !read.error && (
          <>
            {read.data.recommendation_count > 0 ? (
              <Button
                small
                data-testid={`next-slots-open-${row.asgn_id}`}
                onClick={() => setOpen(true)}
              >
                추천 {read.data.recommendation_count}곳
              </Button>
            ) : (
              <small>맞는 자리 없음</small>
            )}
            {read.data.next_temp_assignments.map((t) => (
              <Badge key={t.id} tone="info">
                다음 가배정 · {t.bgn_ym}~{t.end_ym} · {t.mth_mm_qty} MM
              </Badge>
            ))}
            {read.data.next_temp_assignments.length > 0 &&
              read.data.recommendation_count === 0 && (
                <Button
                  small
                  data-testid={`next-temporary-open-${row.asgn_id}`}
                  onClick={() => setOpen(true)}
                >
                  가배정 상세
                </Button>
              )}
          </>
        )
      )}
      <ErrorBanner
        error={read.error}
        onRetry={() => void read.reload().catch(() => {})}
      />
      {open && read.data && (
        <NextSlotsDetail
          row={row}
          context={context}
          data={read.data}
          onClose={() => setOpen(false)}
          onChanged={refresh}
        />
      )}
    </div>
  );
}

function NextSlotsDetail({
  row,
  context,
  data,
  onClose,
  onChanged,
}: {
  row: WithdrawalRow;
  context: WithdrawalScreenContext;
  data: NextSlotsResult;
  onClose: () => void;
  onChanged: () => Promise<unknown>;
}) {
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE");
  const [selected, setSelected] = useState<NextSlot | null>(null);
  const [release, setRelease] = useState<number | null>(null);
  const [start, setStart] = useState(""),
    [end, setEnd] = useState(""),
    [mm, setMm] = useState("1.00");
  const [saved, setSaved] = useState(false);
  const write = useAdminWrite(onChanged);
  const readOnly = context.year < Number(context.today.slice(0, 4));
  const valid =
    !!selected &&
    /^\d{4}-(0[1-9]|1[0-2])$/.test(start) &&
    /^\d{4}-(0[1-9]|1[0-2])$/.test(end) &&
    start >= selected.bgn_ym &&
    end <= selected.end_ym &&
    start <= end &&
    /^(0\.\d{1,2}|1(?:\.0{1,2})?)$/.test(mm) &&
    Number(mm) > 0;
  const choose = (slot: NextSlot) => {
    setSelected(slot);
    setStart(slot.bgn_ym);
    setEnd(slot.end_ym);
    setMm("1.00");
    setSaved(false);
    write.clear();
  };
  const label = (code: string | null, codes: typeof jobs.codes) =>
    codes.find((c) => c.code === code)?.name ?? code ?? "미지정";
  return (
    <Modal title={`${row.dev_nm} · 다음 자리 추천`} onCancel={onClose}>
      <section className={styles.detail} data-testid="next-slots-detail">
        <div className={styles.notice}>
          규칙 기반 추천입니다. 가배정은 확정 다음 배정이 아니며 원가·청구·확정
          MM에 포함되지 않습니다.
        </div>
        <p>
          철수일 {row.end_dt} · 실제 다음 배정 {row.next_display}
        </p>
        <ErrorBanner error={jobs.error} onRetry={jobs.retry} />
        <ErrorBanner error={grades.error} onRetry={grades.retry} />
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <thead>
              <tr>
                {[
                  "기회",
                  "직무",
                  "시작월",
                  "종료월",
                  "등급",
                  "잔여자리",
                  "점수·근거",
                  "대기일",
                  "확률",
                  "가배정",
                ].map((h) => (
                  <th key={h}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.items.map((slot) => (
                <tr
                  key={slot.need_id}
                  data-testid={`next-slot-row-${slot.need_id}`}
                >
                  <td>{slot.oppt_nm}</td>
                  <td>{label(slot.job_cd, jobs.codes)}</td>
                  <td>{slot.bgn_ym}</td>
                  <td>{slot.end_ym}</td>
                  <td>{label(slot.grd_cd, grades.codes)}</td>
                  <td>{slot.remaining_slots}</td>
                  <td>
                    <strong>{slot.mtch_scr}점</strong>
                    <div>
                      직무 {slot.score_components.job} · 등급{" "}
                      {slot.score_components.grade} · 공백{" "}
                      {slot.score_components.gap} · 같은 고객{" "}
                      {slot.score_components.customer} · 확률{" "}
                      {slot.score_components.probability}
                    </div>
                    {slot.warnings.map((w) => (
                      <Badge key={w} tone="warning">
                        {w}
                      </Badge>
                    ))}
                  </td>
                  <td>{slot.waiting_days}일</td>
                  <td>{slot.win_prblt_rt}%</td>
                  <td>
                    <Button
                      small
                      disabled={readOnly || write.pending || write.uncertain}
                      data-testid={`next-slot-assign-${slot.need_id}`}
                      onClick={() => choose(slot)}
                    >
                      가배정
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {data.items.length === 0 && <p>조건에 맞는 추천 자리가 없습니다.</p>}
        {readOnly && <Badge tone="locked">지난 해 · 읽기 전용</Badge>}
        {selected && (
          <form
            className={styles.form}
            data-testid="next-slot-form"
            onSubmit={(e) => {
              e.preventDefault();
              if (!valid || readOnly) return;
              void write.save(
                () =>
                  createNextTemporaryAssignment({
                    need_id: selected.need_id,
                    dev_id: row.dev_id,
                    bgn_ym: start,
                    end_ym: end,
                    mth_mm_qty: Number(mm),
                    tmpr_asgn_src_cd: "NEXT",
                  }),
                () => {
                  setSelected(null);
                  setSaved(true);
                },
              );
            }}
          >
            <p>
              {selected.oppt_nm} · 점수 {selected.mtch_scr} (서버 계산)
            </p>
            <label>
              시작월
              <input
                data-testid="next-slot-start"
                type="month"
                value={start}
                min={selected.bgn_ym}
                max={selected.end_ym}
                onChange={(e) => setStart(e.target.value)}
              />
            </label>
            <label>
              종료월
              <input
                data-testid="next-slot-end"
                type="month"
                value={end}
                min={start}
                max={selected.end_ym}
                onChange={(e) => setEnd(e.target.value)}
              />
            </label>
            <label>
              월 MM
              <input
                data-testid="next-slot-mm"
                type="number"
                min="0.01"
                max="1"
                step="0.01"
                value={mm}
                onChange={(e) => setMm(e.target.value)}
              />
            </label>
            {!valid && (
              <p role="alert">
                자리 기간 안에서 시작·종료월과 0.01~1.00 MM을 입력하세요.
              </p>
            )}
            <Button
              type="submit"
              data-testid="next-slot-save"
              pending={write.pending}
              disabled={!valid || readOnly || write.uncertain}
            >
              가배정 저장
            </Button>
            <Button
              type="button"
              data-testid="next-slot-cancel"
              onClick={() => setSelected(null)}
            >
              취소
            </Button>
          </form>
        )}
        <h3>다음 가배정</h3>
        {data.next_temp_assignments.length === 0 ? (
          <p>다음 가배정이 없습니다.</p>
        ) : (
          data.next_temp_assignments.map((t) => (
            <div key={t.id} data-testid={`next-temporary-${t.id}`}>
              <Badge tone="info">가배정</Badge> 필요 인력 #{t.need_id} ·{" "}
              {t.bgn_ym}~{t.end_ym} · {t.mth_mm_qty} MM
              {t.warnings.map((w) => (
                <Badge key={w} tone="warning">
                  {w}
                </Badge>
              ))}
              <Button
                small
                data-testid={`next-temporary-release-${t.id}`}
                disabled={readOnly || write.pending || write.uncertain}
                onClick={() => setRelease(t.id)}
              >
                해제
              </Button>
            </div>
          ))
        )}
        <ErrorBanner
          error={write.error}
          onRetry={() => void write.recover()}
          pending={write.pending}
        />
        {saved && <p role="status">가배정 변경을 반영했습니다.</p>}
        <Button data-testid="next-slots-close" onClick={onClose}>
          닫기
        </Button>
        {release !== null && (
          <ConfirmDialog
            title="가배정 해제"
            destructive
            onCancel={() => setRelease(null)}
            onConfirm={() => {
              const id = release;
              setRelease(null);
              void write.save(
                () => releaseNextTemporaryAssignment(id),
                () => setSaved(true),
              );
            }}
          >
            선택한 가배정을 해제하시겠습니까? 확정 배정에는 영향이 없습니다.
          </ConfirmDialog>
        )}
      </section>
    </Modal>
  );
}

export default function WithdrawalRecommendationsScreen(
  props: WithdrawalsScreenProps,
) {
  const [recommendationVersion, setRecommendationVersion] = useState(0);
  return (
    <UI05Screen
      {...props}
      renderNext={(row, context) => (
        <NextSlotsCell
          key={`${row.asgn_id}-${row.updated_at}-${context.year}`}
          row={row}
          refreshVersion={recommendationVersion}
          context={{
            ...context,
            refresh: async () => {
              await context.refresh();
              setRecommendationVersion((v) => v + 1);
            },
          }}
        />
      )}
      renderRecommendationSummary={(rows, context) =>
        rows[0] ? (
          <NextSlotsSummary
            asgnId={rows[0].asgn_id}
            version={`${rows[0].updated_at}-${context.year}-${props.refreshVersion ?? 0}-${recommendationVersion}`}
          />
        ) : null
      }
    />
  );
}
