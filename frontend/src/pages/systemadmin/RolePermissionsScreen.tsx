import { useState } from "react";
import { Button } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useAdminRead } from "../admin/useAdminResource";
import {
  adminRoles,
  adminRole,
  rolePermissions,
  command,
  actionKeys,
  type AdminRole,
  type MenuPermission,
} from "./client";
import styles from "./SystemAdministration.module.css";

const blank = () => ({
  name: "",
  description: "",
  useYn: "Y",
  dataScope: "SELF",
  admin: false,
  sensitiveInquiry: false,
  partStandardChange: false,
  riskConfirmed: false,
});
export default function RolePermissionsScreen() {
  const roles = useAdminRead(adminRoles);
  const [selected, setSelected] = useState<AdminRole | null>(null),
    [creating, setCreating] = useState(false),
    [draft, setDraft] = useState(blank);
  const [matrix, setMatrix] = useState<MenuPermission[]>([]),
    [original, setOriginal] = useState<MenuPermission[]>([]),
    [reason, setReason] = useState("");
  const [copyName, setCopyName] = useState(""),
    [comparison, setComparison] = useState(false),
    [collapsed, setCollapsed] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  const select = async (id: number) => {
    setError(null);
    setSelected(null);
    setCreating(false);
    try {
      const [r, p] = await Promise.all([adminRole(id), rolePermissions(id)]);
      setSelected(r);
      setDraft({
        ...blank(),
        name: r.name,
        description: r.description ?? "",
        useYn: r.useYn,
        dataScope: r.dataScope,
        admin: r.admin,
        sensitiveInquiry: r.sensitiveInquiry,
        partStandardChange: r.partStandardChange,
      });
      setMatrix(p);
      setOriginal(p);
      setReason("");
      setComparison(false);
    } catch (e) {
      setError(e as ApiFailure);
    }
  };
  const run = async (fn: () => Promise<AdminRole | MenuPermission[]>) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const value = await fn();
      await roles.reload();
      await select(Array.isArray(value) ? selected!.roleId : value.roleId);
      setMessage("저장했습니다.");
    } catch (e) {
      setError(e as ApiFailure);
    } finally {
      setBusy(false);
    }
  };
  const update = (
    id: number,
    key: (typeof actionKeys)[number],
    checked: boolean,
  ) => {
    setMatrix((m) =>
      m.map((row) =>
        row.menuId === id ? { ...row, [key]: checked ? "Y" : "N" } : row,
      ),
    );
    setComparison(false);
  };
  const changed = matrix.filter((r, i) =>
    actionKeys.some((k) => r[k] !== original[i]?.[k]),
  );
  return (
    <section
      className={styles.screen}
      data-screen-id="UI-24"
      data-testid="role-permissions-screen"
    >
      <ScreenHeading screenId="UI-24" name="권한 관리" groupName="시스템" />
      <ErrorBanner
        error={error ?? roles.error}
        onRetry={() => void roles.reload().catch(() => {})}
      />
      {error?.field && (
        <p role="alert">
          {error.field}: {error.message}
        </p>
      )}
      {message && <p role="status">{message}</p>}
      <div className={styles.layout}>
        <aside className={styles.panel}>
          <Can perm="UI-24.create">
            <Button
              data-testid="role-create"
              onClick={() => {
                setSelected(null);
                setCreating(true);
                setDraft(blank());
                setMatrix([]);
                setOriginal([]);
              }}
            >
              역할 추가
            </Button>
          </Can>
          {roles.loading ? (
            <p role="status">불러오는 중…</p>
          ) : !roles.data?.length ? (
            <p>등록된 역할이 없습니다.</p>
          ) : (
            roles.data.map((r) => (
              <div key={r.roleId} data-testid={`role-row-${r.roleId}`}>
                <Button
                  data-testid={`role-select-${r.roleId}`}
                  aria-pressed={r.roleId === selected?.roleId}
                  onClick={() => void select(r.roleId)}
                >
                  {r.name} · {r.dataScope} · {r.useYn}
                </Button>
              </div>
            ))
          )}
        </aside>
        <div
          className={`${styles.panel} ${styles.fields}`}
          data-testid="role-detail-panel"
        >
          {!selected && !creating ? (
            <p>역할을 선택하세요.</p>
          ) : (
            <>
              <label>
                역할명
                <input
                  data-testid="role-name"
                  maxLength={50}
                  readOnly={selected?.builtin}
                  value={draft.name}
                  onChange={(e) =>
                    setDraft((d) => ({ ...d, name: e.target.value }))
                  }
                />
              </label>
              <label>
                설명
                <textarea
                  data-testid="role-description"
                  maxLength={500}
                  value={draft.description}
                  onChange={(e) =>
                    setDraft((d) => ({ ...d, description: e.target.value }))
                  }
                />
              </label>
              <label>
                데이터 범위
                <select
                  data-testid="role-scope"
                  value={draft.dataScope}
                  onChange={(e) =>
                    setDraft((d) => ({ ...d, dataScope: e.target.value }))
                  }
                >
                  {["ALL", "PART", "PROJECT", "SELF"].map((v) => (
                    <option key={v}>{v}</option>
                  ))}
                </select>
              </label>
              <label>
                사용 여부
                <select
                  data-testid="role-use"
                  value={draft.useYn}
                  onChange={(e) =>
                    setDraft((d) => ({ ...d, useYn: e.target.value }))
                  }
                >
                  <option>Y</option>
                  <option>N</option>
                </select>
              </label>
              {(
                [
                  "admin",
                  "sensitiveInquiry",
                  "partStandardChange",
                  "riskConfirmed",
                ] as const
              ).map((k) => (
                <label className={styles.check} key={k}>
                  <input
                    data-testid={`role-${k.replace(/[A-Z]/g, (v) => `-${v.toLowerCase()}`)}`}
                    type="checkbox"
                    disabled={k === "admin" && selected?.builtin}
                    checked={draft[k]}
                    onChange={(e) =>
                      setDraft((d) => ({ ...d, [k]: e.target.checked }))
                    }
                  />
                  {
                    {
                      admin: "관리자",
                      sensitiveInquiry: "민감 정보 조회",
                      partStandardChange: "파트 기준 변경",
                      riskConfirmed: "관리자 승격 위험 확인",
                    }[k]
                  }
                </label>
              ))}
              <Can perm={creating ? "UI-24.create" : "UI-24.update"}>
                <Button
                  data-testid="role-save"
                  variant="primary"
                  pending={busy}
                  onClick={() =>
                    void run(() =>
                      command<AdminRole>(
                        creating
                          ? "/api/admin/roles"
                          : `/api/admin/roles/${selected!.roleId}`,
                        creating ? "POST" : "PATCH",
                        draft,
                      ),
                    )
                  }
                >
                  역할 속성 저장
                </Button>
              </Can>
              {selected && (
                <>
                  <Can perm="UI-24.create">
                    <label>
                      복사할 역할명
                      <input
                        data-testid="role-copy-name"
                        maxLength={50}
                        value={copyName}
                        onChange={(e) => setCopyName(e.target.value)}
                      />
                    </label>
                    <Button
                      data-testid="role-copy"
                      pending={busy}
                      disabled={!copyName.trim()}
                      onClick={() =>
                        void run(() =>
                          command<AdminRole>(
                            `/api/admin/roles/${selected.roleId}/copy`,
                            "POST",
                            { name: copyName },
                          ),
                        )
                      }
                    >
                      특권을 제거하여 복사
                    </Button>
                  </Can>
                  <h2>화면별 권한</h2>
                  <div className={styles.toolbar}>
                    <Button
                      data-testid="role-tree-toggle"
                      onClick={() => setCollapsed((v) => !v)}
                    >
                      {collapsed ? "펼치기" : "접기"}
                    </Button>
                    <Button
                      data-testid="role-matrix-reset"
                      onClick={() => {
                        setMatrix(original);
                        setComparison(false);
                      }}
                    >
                      변경 취소
                    </Button>
                    <Button
                      data-testid="role-matrix-clear"
                      onClick={() =>
                        setMatrix((m) =>
                          m.map((r) => ({
                            ...r,
                            ...Object.fromEntries(
                              actionKeys.map((k) => [k, "N"]),
                            ),
                          })),
                        )
                      }
                    >
                      모두 해제
                    </Button>
                    <Button
                      data-testid="role-matrix-select-all"
                      onClick={() =>
                        setMatrix((m) =>
                          m.map((r) =>
                            r.screenId &&
                            (!["UI-23", "UI-24", "UI-25"].includes(
                              r.screenId,
                            ) ||
                              selected.admin)
                              ? {
                                  ...r,
                                  inq: "Y",
                                  reg: "Y",
                                  mdfcn: "Y",
                                  del: "Y",
                                  upld: "Y",
                                  dwnld: "Y",
                                  cnfrm: "Y",
                                }
                              : r,
                          ),
                        )
                      }
                    >
                      현재 역할 전체 선택
                    </Button>
                  </div>
                  {!collapsed && (
                    <div
                      className={styles.scroll}
                      data-testid="role-matrix-panel"
                    >
                      <table>
                        <thead>
                          <tr>
                            <th>화면</th>
                            {[
                              "조회",
                              "등록",
                              "수정",
                              "삭제",
                              "업로드",
                              "다운로드",
                              "확정",
                            ].map((v) => (
                              <th key={v}>{v}</th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {matrix.map((r) => (
                            <tr
                              key={r.menuId}
                              data-testid={`permission-row-${r.menuId}`}
                            >
                              <td>{r.name}</td>
                              {actionKeys.map((k) => (
                                <td key={k}>
                                  <input
                                    data-testid={`permission-${r.menuId}-${k}`}
                                    aria-label={`${r.name} ${k}`}
                                    type="checkbox"
                                    disabled={
                                      !r.screenId ||
                                      (["UI-23", "UI-24", "UI-25"].includes(
                                        r.screenId,
                                      ) &&
                                        !selected.admin)
                                    }
                                    checked={r[k] === "Y"}
                                    onChange={(e) =>
                                      update(r.menuId, k, e.target.checked)
                                    }
                                  />
                                </td>
                              ))}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                  <label>
                    변경 사유
                    <textarea
                      data-testid="role-permission-reason"
                      maxLength={500}
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                    />
                  </label>
                  <Can perm="UI-24.update">
                    <Button
                      data-testid="role-permission-compare"
                      onClick={() => setComparison(true)}
                    >
                      변경 전후 비교
                    </Button>
                    {comparison && (
                      <div
                        data-testid="role-permission-confirmation"
                        className={styles.confirm}
                      >
                        <p>
                          변경된 메뉴 {changed.length}개. 저장하면 모든 세션의
                          다음 요청부터 최신 권한이 적용됩니다.
                        </p>
                        {changed.map((r) => (
                          <p key={r.menuId}>
                            {r.name}:{" "}
                            {actionKeys
                              .map(
                                (k) =>
                                  `${k} ${original.find((o) => o.menuId === r.menuId)?.[k]}→${r[k]}`,
                              )
                              .join(" / ")}
                          </p>
                        ))}
                        <Button
                          data-testid="role-permission-save"
                          pending={busy}
                          disabled={
                            !reason.trim() ||
                            matrix.some(
                              (r) =>
                                r.inq === "N" &&
                                actionKeys.slice(1).some((k) => r[k] === "Y"),
                            )
                          }
                          onClick={() =>
                            void run(() =>
                              command<MenuPermission[]>(
                                `/api/admin/roles/${selected.roleId}/permissions`,
                                "PUT",
                                {
                                  reason,
                                  permissions: matrix.map(
                                    ({ menuId, ...r }) => ({
                                      menuId,
                                      ...Object.fromEntries(
                                        actionKeys.map((k) => [k, r[k]]),
                                      ),
                                    }),
                                  ),
                                },
                              ),
                            )
                          }
                        >
                          권한 저장 확인
                        </Button>
                        <Button
                          data-testid="role-permission-cancel"
                          onClick={() => setComparison(false)}
                        >
                          취소
                        </Button>
                      </div>
                    )}
                  </Can>
                </>
              )}
            </>
          )}
        </div>
      </div>
    </section>
  );
}
