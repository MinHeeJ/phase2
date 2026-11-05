import { useState } from "react";
import { Button, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure, readData } from "../../shared/api";
import {
  command,
  download,
  useResource,
  type User,
  type Role,
  type Page,
  type History,
} from "./client";
import styles from "../authentication/SecurityScreens.module.css";

export default function UserManagementScreen() {
  const [query, setQuery] = useState(""),
    [name, setName] = useState(""),
    [loginId, setLoginId] = useState(""),
    [status, setStatus] = useState(""),
    [page, setPage] = useState(1),
    [tab, setTab] = useState("users");
  const [partId, setPartId] = useState(""),
    [roleId, setRoleId] = useState("");
  const parts = useResource<{ id: string; name: string }[]>(
    "/api/sales-parts/options",
  );
  const roles = useResource<Role[]>("/api/admin/roles");
  const source = useResource<Page<User>>(
    `/api/admin/users?page=${page}&size=50${query}`,
  );
  const [selected, setSelected] = useState<User | null>(null),
    [creating, setCreating] = useState(false),
    [message, setMessage] = useState("");
  const [error, setError] = useState<ApiFailure | null>(null);
  return (
    <section
      className={styles.workspace}
      data-testid="user-management-screen"
      data-screen-id="UI-23"
    >
      <h1>사용자 관리</h1>
      <div className={styles.toolbar}>
        <Button data-testid="users-tab" onClick={() => setTab("users")}>
          사용자
        </Button>
        <Button
          data-testid="access-history-tab"
          onClick={() => setTab("history")}
        >
          접근 기록
        </Button>
      </div>
      {tab === "history" ? (
        <AccessHistory />
      ) : (
        <>
          <form
            className={styles.toolbar}
            data-testid="user-search"
            onSubmit={(e) => {
              e.preventDefault();
              setPage(1);
              setSelected(null);
              setQuery(
                `&${new URLSearchParams({ name, loginId, partId, roleId, status })}`,
              );
            }}
          >
            <label>
              이름
              <input
                data-testid="user-filter-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </label>
            <label>
              아이디
              <input
                data-testid="user-filter-login"
                value={loginId}
                onChange={(e) => setLoginId(e.target.value)}
              />
            </label>
            <label>
              상태
              <select
                data-testid="user-filter-status"
                value={status}
                onChange={(e) => setStatus(e.target.value)}
              >
                <option value="">전체</option>
                {["ACTIVE", "LOCKED", "DISABLED"].map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            </label>
            <Button data-testid="user-search-submit" type="submit">
              조회
            </Button>
            <label>
              파트
              <select
                data-testid="user-filter-part"
                value={partId}
                disabled={parts.loading || !!parts.error}
                onChange={(e) => setPartId(e.target.value)}
              >
                <option value="">전체</option>
                {parts.data?.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              역할
              <select
                data-testid="user-filter-role"
                value={roleId}
                disabled={roles.loading || !!roles.error}
                onChange={(e) => setRoleId(e.target.value)}
              >
                <option value="">전체</option>
                {roles.data?.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </select>
            </label>
            <Button
              data-testid="user-export"
              type="button"
              onClick={() => {
                void download(
                  `/api/admin/users/export?${query.slice(1)}`,
                ).catch((e) => setError(e as ApiFailure));
              }}
            >
              엑셀 내려받기
            </Button>
            <Button
              data-testid="user-create"
              type="button"
              onClick={() => {
                setSelected(null);
                setCreating(true);
              }}
            >
              사용자 등록
            </Button>
          </form>
          <ErrorBanner error={source.error ?? error} onRetry={source.refresh} />
          <ErrorBanner error={parts.error} onRetry={parts.refresh} />
          <ErrorBanner error={roles.error} onRetry={roles.refresh} />
          {source.loading && <Skeleton label="사용자 불러오는 중" />}
          {message && <p role="status">{message}</p>}
          <div className={styles.layout}>
            <div className={`${styles.panel} ${styles.scroll}`}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>아이디·이름</th>
                    <th>이메일</th>
                    <th>역할</th>
                    <th>상태</th>
                    <th>최근 로그인·퇴사일</th>
                  </tr>
                </thead>
                <tbody>
                  {source.data?.items.map((u) => (
                    <tr key={u.id} data-testid={`user-row-${u.id}`}>
                      <td>
                        <Button
                          data-testid={`user-select-${u.id}`}
                          onClick={async () => {
                            setSelected(null);
                            setCreating(false);
                            try {
                              setSelected(
                                await readData<User>(
                                  `/api/admin/users/${u.id}`,
                                ),
                              );
                            } catch (e) {
                              setError(e as ApiFailure);
                            }
                          }}
                        >
                          {u.loginId} · {u.name}
                        </Button>
                      </td>
                      <td>{u.email}</td>
                      <td>{u.roleIds.join(", ")}</td>
                      <td>{u.status}</td>
                      <td>
                        {u.lastLoginAt ?? "—"} / {u.resignDate ?? "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {source.data?.items.length === 0 && <p>검색 결과가 없습니다.</p>}
              <p>총 {source.data?.total ?? "—"}건</p>
              <Button
                data-testid="users-previous"
                disabled={page === 1}
                onClick={() => {
                  setSelected(null);
                  setPage((p) => p - 1);
                }}
              >
                이전
              </Button>
              <Button
                data-testid="users-next"
                disabled={!source.data || page * 50 >= source.data.total}
                onClick={() => {
                  setSelected(null);
                  setPage((p) => p + 1);
                }}
              >
                다음
              </Button>
            </div>
            <div className={styles.panel}>
              {selected || creating ? (
                <UserEditor
                  key={creating ? "new" : selected!.id}
                  user={creating ? null : selected}
                  onSaved={(user) => {
                    setSelected(user);
                    setCreating(false);
                    source.refresh();
                    setMessage("저장 결과를 재조회했습니다.");
                  }}
                  onCancel={() => {
                    setCreating(false);
                    setSelected(null);
                  }}
                />
              ) : (
                <p>사용자를 선택하세요.</p>
              )}
            </div>
          </div>
        </>
      )}
    </section>
  );
}
function UserEditor({
  user,
  onSaved,
  onCancel,
}: {
  user: User | null;
  onSaved: (user: User) => void;
  onCancel: () => void;
}) {
  const roles = useResource<Role[]>("/api/admin/roles"),
    parts = useResource<{ id: string; name: string }[]>(
      "/api/sales-parts/options",
    ),
    projects = useResource<{ id: number; name: string }[]>(
      "/api/projects/options",
    ),
    developers = useResource<{ id: number; name: string }[]>(
      "/api/developers/options",
    );
  const [draft, setDraft] = useState(
    user ?? {
      name: "",
      loginId: "",
      email: "",
      phone: "",
      basePartId: null,
      developerId: null,
      partIds: [] as number[],
      projectIds: [] as number[],
      roleIds: [] as number[],
      status: "ACTIVE",
      resignDate: null,
    },
  );
  const [pending, setPending] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null),
    [confirmation, setConfirmation] = useState<
      "disable" | "password-reset" | null
    >(null),
    [risk, setRisk] = useState(false);
  const set = (key: string, value: unknown) =>
    setDraft((d) => ({ ...d, [key]: value }));
  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    setPending(true);
    try {
      const result = await action();
      if (user) onSaved(await readData<User>(`/api/admin/users/${user.id}`));
      else if (result) onSaved(result as User);
    } catch (e) {
      setError(e as ApiFailure);
    } finally {
      setPending(false);
    }
  };
  return (
    <div className={styles.form} data-testid="user-editor">
      <form
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          void run(async () => {
            const {
              name,
              loginId,
              email,
              basePartId,
              developerId,
              partIds,
              projectIds,
              roleIds,
              phone,
              status,
              resignDate,
            } = draft;
            return command<User>(
              user ? `/api/admin/users/${user.id}` : "/api/admin/users",
              user ? "PATCH" : "POST",
              user
                ? {
                    name,
                    email,
                    phone: phone || null,
                    basePartId,
                    resignDate,
                    status,
                  }
                : {
                    name,
                    loginId,
                    email,
                    basePartId,
                    developerId,
                    partIds,
                    projectIds,
                    roleIds,
                  },
            );
          });
        }}
      >
        <h2>{user ? "사용자 상세·수정" : "사용자 등록"}</h2>
        <label>
          이름
          <input
            data-testid="user-name"
            required
            maxLength={50}
            value={draft.name}
            onChange={(e) => set("name", e.target.value)}
          />
        </label>
        <label>
          아이디
          <input
            data-testid="user-login-id"
            required
            pattern="[A-Za-z0-9._-]{1,50}"
            maxLength={50}
            readOnly={!!user}
            value={draft.loginId}
            onChange={(e) => set("loginId", e.target.value)}
          />
        </label>
        <label>
          이메일
          <input
            data-testid="user-email"
            type="email"
            required
            maxLength={100}
            value={draft.email}
            onChange={(e) => set("email", e.target.value)}
          />
        </label>
        {user && (
          <label>
            계정 휴대폰
            <input
              data-testid="user-phone"
              maxLength={30}
              value={draft.phone ?? ""}
              onChange={(e) => set("phone", e.target.value)}
            />
          </label>
        )}
        <label>
          기본 파트
          <select
            data-testid="user-base-part"
            required
            value={draft.basePartId ?? ""}
            onChange={(e) => set("basePartId", Number(e.target.value))}
          >
            <option value="">선택</option>
            {parts.data?.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          개발자 연결
          <select
            data-testid="user-developer"
            disabled={!!user}
            value={draft.developerId ?? ""}
            onChange={(e) =>
              set("developerId", e.target.value ? Number(e.target.value) : null)
            }
          >
            <option value="">연결 없음</option>
            {developers.data?.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
        </label>
        {user && (
          <>
            <label>
              퇴사일
              <input
                data-testid="user-resign-date"
                type="date"
                value={draft.resignDate ?? ""}
                onChange={(e) => set("resignDate", e.target.value || null)}
              />
            </label>
            <label>
              상태
              <select
                data-testid="user-status"
                value={draft.status}
                onChange={(e) => set("status", e.target.value)}
              >
                {["ACTIVE", "LOCKED", "DISABLED"].map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            </label>
            <p>MFA: {user.mfaRequired ? "필수" : "정책에 따름"} (읽기 전용)</p>
          </>
        )}
        {!user && (
          <p>
            담당 관계·역할은 아래에서 선택한 값과 함께 등록됩니다. 비밀번호는
            표시하지 않고 RESET 링크로 안내합니다.
          </p>
        )}
        <Button data-testid="user-basic-save" type="submit" disabled={pending}>
          기본 정보 저장
        </Button>
      </form>
      <label>
        역할
        <select
          data-testid="user-roles"
          multiple
          required
          value={draft.roleIds.map(String)}
          onChange={(e) =>
            set(
              "roleIds",
              Array.from(e.target.selectedOptions, (o) => Number(o.value)),
            )
          }
        >
          {roles.data
            ?.filter((r) => r.useYn === "Y")
            .map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
        </select>
      </label>
      {user && (
        <>
          <label className={styles.check}>
            <input
              data-testid="user-admin-grant-confirm"
              type="checkbox"
              checked={risk}
              onChange={(e) => setRisk(e.target.checked)}
            />
            관리자 역할 부여 위험 확인
          </label>
          <Button
            data-testid="user-roles-save"
            disabled={pending}
            onClick={() =>
              void run(() =>
                command(`/api/admin/users/${user.id}/roles`, "PUT", {
                  roleIds: draft.roleIds,
                  adminGrantConfirmed: risk,
                }),
              )
            }
          >
            역할 저장
          </Button>
        </>
      )}
      <label>
        담당 파트
        <select
          data-testid="user-parts"
          multiple
          value={draft.partIds.map(String)}
          onChange={(e) =>
            set(
              "partIds",
              Array.from(e.target.selectedOptions, (o) => Number(o.value)),
            )
          }
        >
          {parts.data?.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        담당 프로젝트
        <select
          data-testid="user-projects"
          multiple
          value={draft.projectIds.map(String)}
          onChange={(e) =>
            set(
              "projectIds",
              Array.from(e.target.selectedOptions, (o) => Number(o.value)),
            )
          }
        >
          {projects.data?.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </label>
      {user && (
        <>
          <Button
            data-testid="user-assignments-save"
            disabled={pending}
            onClick={() =>
              void run(() =>
                command(`/api/admin/users/${user.id}/assignments`, "PUT", {
                  partIds: draft.partIds,
                  projectIds: draft.projectIds,
                }),
              )
            }
          >
            담당 저장
          </Button>
          <div className={styles.toolbar}>
            <Button
              data-testid="user-disable"
              disabled={pending}
              onClick={() => setConfirmation("disable")}
            >
              사용 중지
            </Button>
            <Button
              data-testid="user-reset"
              disabled={pending}
              onClick={() => setConfirmation("password-reset")}
            >
              RESET 시작
            </Button>
            <Button
              data-testid="user-unlock"
              disabled={pending || user.status !== "LOCKED"}
              onClick={() =>
                void run(() =>
                  command(`/api/admin/users/${user.id}/unlock`, "POST"),
                )
              }
            >
              잠금 해제
            </Button>
          </div>
        </>
      )}
      {confirmation && user && (
        <section data-testid="user-action-confirm">
          <p>
            {user.name} 계정에{" "}
            {confirmation === "disable"
              ? "사용 중지 (기존 세션도 차단)"
              : "비밀번호 재설정 메일 발송"}{" "}
            작업을 실행합니다.
          </p>
          <Button
            data-testid="user-action-apply"
            disabled={pending}
            onClick={() => {
              void run(() =>
                command(`/api/admin/users/${user.id}/${confirmation}`, "POST"),
              );
              setConfirmation(null);
            }}
          >
            확인·실행
          </Button>
          <Button
            data-testid="user-action-cancel"
            onClick={() => setConfirmation(null)}
          >
            취소
          </Button>
        </section>
      )}
      <ErrorBanner
        error={
          error ??
          roles.error ??
          parts.error ??
          projects.error ??
          developers.error
        }
      />
      <Button data-testid="user-editor-cancel" onClick={onCancel}>
        취소
      </Button>
    </div>
  );
}
function AccessHistory() {
  const [filters, setFilters] = useState({
      userId: "",
      from: "",
      to: "",
      type: "",
      result: "",
      screenId: "",
    }),
    [query, setQuery] = useState(""),
    [page, setPage] = useState(1);
  const source = useResource<Page<History>>(
    `/api/admin/access-history?page=${page}&size=50&${query}`,
  );
  const [error, setError] = useState<ApiFailure | null>(null);
  return (
    <section className={styles.workspace} data-testid="access-history-panel">
      <form
        className={styles.toolbar}
        onSubmit={(e) => {
          e.preventDefault();
          setPage(1);
          setQuery(new URLSearchParams(filters).toString());
        }}
      >
        {(
          [
            ["userId", "사용자 ID"],
            ["from", "시작일"],
            ["to", "종료일"],
            ["type", "작업 유형"],
            ["screenId", "화면 ID"],
          ] as const
        ).map(([key, label]) => (
          <label key={key}>
            {label}
            <input
              data-testid={`history-filter-${key.toLowerCase()}`}
              type={
                key === "from" || key === "to"
                  ? "date"
                  : key === "userId"
                    ? "number"
                    : "text"
              }
              value={filters[key]}
              onChange={(e) =>
                setFilters((f) => ({ ...f, [key]: e.target.value }))
              }
            />
          </label>
        ))}
        <label>
          결과
          <select
            data-testid="history-result"
            value={filters.result}
            onChange={(e) =>
              setFilters((f) => ({ ...f, result: e.target.value }))
            }
          >
            <option value="">전체</option>
            {["SUCCESS", "FAILURE", "UNKNOWN"].map((r) => (
              <option key={r}>{r}</option>
            ))}
          </select>
        </label>
        <Button data-testid="history-search" type="submit">
          조회
        </Button>
        <Button
          data-testid="history-export"
          type="button"
          onClick={() => {
            void download(`/api/admin/access-history/export?${query}`).catch(
              (e) => setError(e as ApiFailure),
            );
          }}
        >
          기록 내려받기
        </Button>
      </form>
      <ErrorBanner error={source.error ?? error} onRetry={source.refresh} />
      {source.loading && <Skeleton label="접근 기록 불러오는 중" />}
      <div className={styles.scroll}>
        <table className={styles.table}>
          <thead>
            <tr>
              {[
                "발생 시각",
                "사용자",
                "IP",
                "작업",
                "결과",
                "화면",
                "대상",
              ].map((t) => (
                <th key={t}>{t}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {source.data?.items.map((h) => (
              <tr key={h.id} data-testid={`history-row-${h.id}`}>
                <td>{h.createdAt}</td>
                <td>{h.loginId ?? h.userId ?? "—"}</td>
                <td>{h.ip ?? "—"}</td>
                <td>{h.type}</td>
                <td>{h.result}</td>
                <td>{h.screenId ?? "—"}</td>
                <td>{h.target ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {source.data?.items.length === 0 && <p>접근 기록이 없습니다.</p>}
      <div className={styles.toolbar}>
        <Button
          data-testid="history-previous"
          disabled={page === 1}
          onClick={() => setPage((p) => p - 1)}
        >
          이전
        </Button>
        <span>{source.data?.total ?? "—"}건</span>
        <Button
          data-testid="history-next"
          disabled={!source.data || page * 50 >= source.data.total}
          onClick={() => setPage((p) => p + 1)}
        >
          다음
        </Button>
      </div>
    </section>
  );
}
