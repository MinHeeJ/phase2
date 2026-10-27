import { useCallback, useEffect, useState } from "react";
import { Badge, Button, Skeleton, SuccessToast } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { AssignmentFixForm } from "./AssignmentChangeForms";
import {
  changeNames,
  listAssignmentChanges,
  type AssignmentChange,
  type ChangeContext,
  type ChangeType,
} from "./client";
import styles from "./AssignmentChanges.module.css";

export function UI12ChangesPanel({
  assignment,
  contractType,
  readOnly = false,
  projectWritable = true,
  onChanged,
}: ChangeContext & {
  onChanged: () => Promise<unknown>;
}) {
  const [type, setType] = useState<ChangeType | undefined>();
  const [target, setTarget] = useState<AssignmentChange | null>(null);
  const [message, setMessage] = useState("");
  const mobile = useDeveloperReadOnly();
  const locked =
    readOnly ||
    mobile ||
    !projectWritable ||
    ["CANCELLED", "ENDED"].includes(assignment.asgn_stts_cd);
  const read = useCallback(
    () => listAssignmentChanges(assignment.asgn_id, type),
    [assignment.asgn_id, type],
  );
  const state = useAdminRead(read);
  useEffect(() => {
    setTarget(null);
    setType(undefined);
    setMessage("");
  }, [assignment.asgn_id]);
  const refresh = async () => {
    await state.reload();
    await onChanged();
  };
  return (
    <section
      role="tabpanel"
      aria-label="변경 이력"
      data-testid="assignment-changes-panel"
    >
      <h3>변경 이력 ({state.data?.total ?? "–"})</h3>
      <p>
        지나간 기록은 고치거나 지울 수 없습니다. 틀렸으면 [정정]으로 바로잡은
        내용을 남깁니다.
      </p>
      <div className={styles.chips} aria-label="종류">
        <Button
          small
          aria-pressed={!type}
          data-testid="changes-filter-all"
          onClick={() => setType(undefined)}
        >
          전체
        </Button>
        {(Object.entries(changeNames) as [ChangeType, string][]).map(
          ([key, name]) => (
            <Button
              small
              key={key}
              aria-pressed={type === key}
              data-testid={`changes-filter-${key.toLowerCase().replaceAll("_", "-")}`}
              onClick={() => setType(key)}
            >
              {name}
            </Button>
          ),
        )}
      </div>
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        state.data && (
          <div className={styles.tableScroll}>
            <table className={styles.table}>
              <thead>
                <tr>
                  {[
                    "적용일 / 입력 처리자·시각",
                    "종류",
                    "변경 전 → 후",
                    "사유",
                    "동작",
                  ].map((t) => (
                    <th
                      data-testid={`assignment-changes-header-${["적용일 / 입력 처리자·시각", "종류", "변경 전 → 후", "사유", "동작"].indexOf(t)}`}
                      key={t}
                    >
                      {t}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {state.data.items.map((row) => (
                  <tr
                    key={row.asgn_chg_id}
                    data-testid={`changes-row-${row.asgn_chg_id}`}
                    className={row.fixed_yn === "Y" ? styles.fixed : undefined}
                  >
                    <td>
                      {row.aply_dt}
                      <small>
                        {row.chg_user_nm ?? "기본 사용자"} ·{" "}
                        {row.chg_at.replace("T", " ")}
                      </small>
                    </td>
                    <td>
                      <Badge
                        tone={
                          row.asgn_chg_ty_cd === "FIX"
                            ? "info"
                            : row.asgn_chg_ty_cd === "EARLY_END"
                              ? "danger"
                              : "neutral"
                        }
                      >
                        {changeNames[row.asgn_chg_ty_cd]}
                      </Badge>
                      {row.retro_yn === "Y" && (
                        <Badge tone="warning">소급 입력</Badge>
                      )}
                      {row.fixed_yn === "Y" && (
                        <Badge tone="locked">정정됨</Badge>
                      )}
                    </td>
                    <td>
                      {row.bfr_val ?? "–"} → {row.aft_val}
                      {row.asgn_chg_ty_cd === "FIX" && (
                        <small>
                          정정 대상: {row.fix_trgt_aply_dt} ·{" "}
                          {row.fix_trgt_chg_ty_cd
                            ? changeNames[row.fix_trgt_chg_ty_cd]
                            : "–"}
                        </small>
                      )}
                    </td>
                    <td>
                      {[row.rsn_nm, row.rsn_desc].filter(Boolean).join(" · ") ||
                        "–"}
                    </td>
                    <td>
                      {!locked &&
                        !["START", "FIX"].includes(row.asgn_chg_ty_cd) &&
                        row.fixed_yn !== "Y" && (
                          <Can perm="UI-12.update">
                            <Button
                              small
                              data-testid={`changes-correct-${row.asgn_chg_id}`}
                              onClick={() => setTarget(row)}
                            >
                              정정
                            </Button>
                          </Can>
                        )}
                    </td>
                  </tr>
                ))}
                {state.data.items.length === 0 && (
                  <tr>
                    <td colSpan={5} className={styles.empty}>
                      이 종류의 기록이 없습니다.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )
      )}
      {!locked && target && (
        <AssignmentFixForm
          assignment={assignment}
          contractType={contractType}
          target={target}
          reload={refresh}
          onCancel={() => setTarget(null)}
          onSaved={() => {
            setTarget(null);
            setMessage("정정 기록을 남겼습니다.");
          }}
        />
      )}
    </section>
  );
}
