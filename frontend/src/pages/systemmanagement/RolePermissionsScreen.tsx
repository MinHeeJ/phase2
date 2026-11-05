import { useEffect, useState } from "react";
import { Button, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import {
  actions,
  actionLabels,
  command,
  useResource,
  type Role,
  type Permission,
} from "./client";
import styles from "../authentication/SecurityScreens.module.css";

export default function RolePermissionsScreen() {
  const roles = useResource<Role[]>("/api/admin/roles");
  const [selected, setSelected] = useState<Role | null>(null),
    [creating, setCreating] = useState(false);
  const [copying, setCopying] = useState(false),
    [copyName, setCopyName] = useState("");
  const [error, setError] = useState<ApiFailure | null>(null),
    [pending, setPending] = useState(false),
    [message, setMessage] = useState("");
  return (
    <section
      className={styles.workspace}
      data-testid="role-permissions-screen"
      data-screen-id="UI-24"
    >
      <h1>권한 관리</h1>
      <ErrorBanner error={roles.error ?? error} onRetry={roles.refresh} />
      {roles.loading && <Skeleton label="역할 불러오는 중" />}
      {message && <p role="status">{message}</p>}
      <div className={styles.layout}>
        <aside className={styles.panel}>
          <Button
            data-testid="role-add"
            onClick={() => {
              setSelected(null);
              setCreating(true);
              setCopying(false);
            }}
          >
            역할 추가
          </Button>
          <ul className={styles.list}>
            {roles.data?.map((role) => (
              <li key={role.id} data-testid={`role-row-${role.id}`}>
                <Button
                  data-testid={`role-select-${role.id}`}
                  onClick={() => {
                    setSelected(role);
                    setCreating(false);
                    setCopying(false);
                    setMessage("");
                  }}
                >
                  {role.name} · {role.dataScope} · {role.useYn}
                </Button>
              </li>
            ))}
          </ul>
          {roles.data?.length === 0 && <p>등록된 역할이 없습니다.</p>}
        </aside>
        <div className={styles.panel}>
          {creating || selected ? (
            <RoleEditor
              key={creating ? "create" : selected!.id}
              role={creating ? null : selected}
              onSaved={(role) => {
                setSelected(role);
                setCreating(false);
                roles.refresh();
                setMessage("역할을 저장했습니다.");
              }}
            />
          ) : (
            <p>역할을 선택하세요.</p>
          )}
          {selected && !creating && (
            <>
              <Button
                data-testid="role-copy-open"
                onClick={() => {
                  setCopying(true);
                  setCopyName("");
                }}
              >
                특권 제거 복사
              </Button>
              {copying && (
                <form
                  data-testid="role-copy-panel"
                  className={styles.form}
                  onSubmit={async (e) => {
                    e.preventDefault();
                    setPending(true);
                    setError(null);
                    try {
                      const role = await command<Role>(
                        `/api/admin/roles/${selected.id}/copy`,
                        "POST",
                        { name: copyName, description: selected.description },
                      );
                      setSelected(role);
                      roles.refresh();
                      setCopying(false);
                      setMessage("네 특권이 제거된 역할을 복사했습니다.");
                    } catch (e) {
                      setError(e as ApiFailure);
                    } finally {
                      setPending(false);
                    }
                  }}
                >
                  <p>
                    범위·메뉴 권한만 복사합니다. 관리자·기본 역할·민감 보기·파트
                    기준 변경은 해제됩니다.
                  </p>
                  <label>
                    새 역할명
                    <input
                      data-testid="role-copy-name"
                      required
                      maxLength={50}
                      value={copyName}
                      onChange={(e) => setCopyName(e.target.value)}
                    />
                  </label>
                  <div className={styles.toolbar}>
                    <Button
                      data-testid="role-copy-save"
                      type="submit"
                      disabled={pending}
                    >
                      복사 저장
                    </Button>
                    <Button
                      data-testid="role-copy-cancel"
                      type="button"
                      onClick={() => setCopying(false)}
                    >
                      취소
                    </Button>
                  </div>
                </form>
              )}
              <PermissionEditor key={selected.id} role={selected} />
            </>
          )}
        </div>
      </div>
    </section>
  );
}
function RoleEditor({
  role,
  onSaved,
}: {
  role: Role | null;
  onSaved: (role: Role) => void;
}) {
  const [draft, setDraft] = useState<Omit<Role, "id" | "builtIn">>(
    role ?? {
      name: "",
      description: "",
      useYn: "Y",
      dataScope: "SELF",
      sensitiveView: false,
      partStandardChange: false,
      admin: false,
    },
  );
  const [confirmed, setConfirmed] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null),
    [pending, setPending] = useState(false);
  const field = <K extends keyof typeof draft>(
    key: K,
    value: (typeof draft)[K],
  ) => setDraft((old) => ({ ...old, [key]: value }));
  return (
    <form
      className={styles.form}
      data-testid="role-editor"
      onSubmit={async (e) => {
        e.preventDefault();
        setError(null);
        setPending(true);
        try {
          const {
            name,
            description,
            useYn,
            dataScope,
            sensitiveView,
            partStandardChange,
            admin,
          } = draft;
          onSaved(
            await command<Role>(
              role ? `/api/admin/roles/${role.id}` : "/api/admin/roles",
              role ? "PATCH" : "POST",
              {
                name,
                description,
                useYn,
                dataScope,
                sensitiveView,
                partStandardChange,
                admin,
                adminGrantConfirmed: confirmed,
              },
            ),
          );
        } catch (e) {
          setError(e as ApiFailure);
        } finally {
          setPending(false);
        }
      }}
    >
      <label>
        역할명
        <input
          data-testid="role-name"
          required
          maxLength={50}
          readOnly={role?.builtIn}
          value={draft.name}
          onChange={(e) => field("name", e.target.value)}
        />
      </label>
      <label>
        설명
        <textarea
          data-testid="role-description"
          maxLength={500}
          value={draft.description ?? ""}
          onChange={(e) => field("description", e.target.value)}
        />
      </label>
      <label>
        데이터 범위
        <select
          data-testid="role-scope"
          value={draft.dataScope}
          onChange={(e) =>
            field("dataScope", e.target.value as Role["dataScope"])
          }
        >
          {["ALL", "PART", "PROJECT", "SELF"].map((value) => (
            <option key={value}>{value}</option>
          ))}
        </select>
      </label>
      <label>
        사용 여부
        <select
          data-testid="role-use"
          value={draft.useYn}
          onChange={(e) => field("useYn", e.target.value as "Y" | "N")}
        >
          <option value="Y">사용</option>
          <option value="N">사용 중지</option>
        </select>
      </label>
      {(
        [
          ["sensitiveView", "민감 보기"],
          ["partStandardChange", "파트 기준 변경"],
          ["admin", "관리자"],
        ] as const
      ).map(([key, label]) => (
        <label className={styles.check} key={key}>
          <input
            data-testid={`role-${key}`}
            type="checkbox"
            disabled={key === "admin" && role?.builtIn}
            checked={draft[key]}
            onChange={(e) => field(key, e.target.checked)}
          />
          {label}
        </label>
      ))}
      {(draft.admin || draft.useYn === "N") && (
        <label className={styles.check}>
          <input
            data-testid="role-risk-confirm"
            type="checkbox"
            required
            checked={confirmed}
            onChange={(e) => setConfirmed(e.target.checked)}
          />
          역할의 관리자 권한 또는 사용 중지 영향을 확인했습니다.
        </label>
      )}
      <ErrorBanner error={error} />
      <div className={styles.toolbar}>
        <Button
          data-testid="role-properties-save"
          type="submit"
          disabled={pending}
        >
          속성 저장
        </Button>
        <Button
          data-testid="role-properties-cancel"
          type="button"
          onClick={() => {
            if (role) setDraft(role);
            setConfirmed(false);
          }}
        >
          취소
        </Button>
      </div>
    </form>
  );
}
function PermissionEditor({ role }: { role: Role }) {
  const source = useResource<Permission[]>(
    `/api/admin/roles/${role.id}/permissions`,
  );
  const [draft, setDraft] = useState<Permission[]>([]),
    [reason, setReason] = useState("");
  const [error, setError] = useState<ApiFailure | null>(null),
    [pending, setPending] = useState(false),
    [saved, setSaved] = useState(false);
  useEffect(() => {
    setDraft(source.data ?? []);
  }, [source.data]);
  const locked = (p: Permission) =>
    p.screenId === null ||
    (!role.admin && ["UI-23", "UI-24", "UI-25"].includes(p.screenId));
  const changed = draft.reduce(
    (total, row) =>
      total +
      actions.filter(
        (action) =>
          row[action] !==
          source.data?.find((p) => p.menuId === row.menuId)?.[action],
      ).length,
    0,
  );
  return (
    <form
      data-testid="role-permission-panel"
      className={styles.form}
      onSubmit={async (e) => {
        e.preventDefault();
        setPending(true);
        setError(null);
        setSaved(false);
        try {
          await command(`/api/admin/roles/${role.id}/permissions`, "PUT", {
            reason,
            permissions: draft.map((p) => ({
              menuId: p.menuId,
              inq: p.inq,
              reg: p.reg,
              mdfcn: p.mdfcn,
              del: p.del,
              upld: p.upld,
              dwnld: p.dwnld,
              cnfrm: p.cnfrm,
            })),
          });
          source.refresh();
          setSaved(true);
        } catch (e) {
          setError(e as ApiFailure);
        } finally {
          setPending(false);
        }
      }}
    >
      <h2>화면별 권한</h2>
      <ErrorBanner error={source.error ?? error} onRetry={source.refresh} />
      {source.loading && <Skeleton label="권한 불러오는 중" />}
      <div className={styles.toolbar}>
        <Button
          data-testid="permissions-select-all"
          type="button"
          onClick={() => {
            setDraft((rows) =>
              rows.map((r) =>
                locked(r)
                  ? r
                  : {
                      ...r,
                      ...Object.fromEntries(actions.map((a) => [a, true])),
                    },
              ),
            );
          }}
        >
          현재 역할 전체선택
        </Button>
        <Button
          data-testid="permissions-reset"
          type="button"
          onClick={() => {
            setDraft((rows) =>
              rows.map((r) =>
                locked(r)
                  ? r
                  : {
                      ...r,
                      ...Object.fromEntries(actions.map((a) => [a, false])),
                    },
              ),
            );
          }}
        >
          초기화
        </Button>
      </div>
      <div className={styles.scroll}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>메뉴</th>
              {actionLabels.map((a) => (
                <th key={a}>{a}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {draft.map((row) => (
              <tr key={row.menuId} data-testid={`permission-row-${row.menuId}`}>
                <td>
                  {row.name} {locked(row) && "(편집 불가)"}
                </td>
                {actions.map((action) => (
                  <td key={action}>
                    <input
                      data-testid={`permission-${row.menuId}-${action}`}
                      aria-label={`${row.name} ${action}`}
                      type="checkbox"
                      checked={row[action]}
                      disabled={locked(row)}
                      onChange={(e) => {
                        const checked = e.target.checked;
                        setDraft((rows) =>
                          rows.map((r) =>
                            r.menuId === row.menuId
                              ? { ...r, [action]: checked }
                              : r,
                          ),
                        );
                      }}
                    />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p>변경 전후 비교: {changed}개 권한 변경</p>
      <label>
        변경 사유
        <textarea
          data-testid="permission-reason"
          required
          maxLength={500}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
      </label>
      {saved && <p role="status">권한을 저장하고 재조회했습니다.</p>}
      <div className={styles.toolbar}>
        <Button
          data-testid="permission-save"
          type="submit"
          disabled={pending || source.loading}
        >
          권한 저장
        </Button>
        <Button
          data-testid="permission-cancel"
          type="button"
          onClick={() => {
            setDraft(source.data ?? []);
            setReason("");
          }}
        >
          취소
        </Button>
      </div>
    </form>
  );
}
