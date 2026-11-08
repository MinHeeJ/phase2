import { useCallback, useEffect, useState } from "react";
import { Button } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useAdminRead } from "../admin/useAdminResource";
import { listSalesPartOptions } from "../salespartsops/client";
import { listProjectOptions } from "../projects/client";
import { listDeveloperOptions } from "../developersops/client";
import {
  adminUsers,
  adminUser,
  adminRoles,
  command,
  downloadAdmin,
  query,
  type AdminUser,
} from "./client";
import { AccessHistoryPanel } from "./AccessHistoryPanel";
import styles from "./SystemAdministration.module.css";

interface Draft {
  loginId: string;
  name: string;
  email: string;
  phone: string;
  partId: number | null;
  developerId: number | null;
  status: string;
  resignationDate: string;
  roleIds: number[];
  partIds: number[];
  projectIds: number[];
  riskConfirmed: boolean;
}
const empty = (): Draft => ({
  loginId: "",
  name: "",
  email: "",
  phone: "",
  partId: null,
  developerId: null,
  status: "ACTIVE",
  resignationDate: "",
  roleIds: [],
  partIds: [],
  projectIds: [],
  riskConfirmed: false,
});
export default function UserManagementScreen() {
  const [tab, setTab] = useState("users");
  const [search, setSearch] = useState<Record<string, string | number>>({
    page: 1,
  });
  const [filters, setFilters] = useState({
    name: "",
    loginId: "",
    partId: "",
    roleId: "",
    status: "",
  });
  const users = useAdminRead(useCallback(() => adminUsers(search), [search]));
  const roles = useAdminRead(adminRoles),
    parts = useAdminRead(listSalesPartOptions),
    projects = useAdminRead(listProjectOptions),
    developers = useAdminRead(useCallback(() => listDeveloperOptions(), []));
  const [selected, setSelected] = useState<number | null>(null),
    [creating, setCreating] = useState(false),
    [draft, setDraft] = useState<Draft>(empty);
  const [error, setError] = useState<ApiFailure | null>(null),
    [busy, setBusy] = useState(false),
    [success, setSuccess] = useState(""),
    [confirm, setConfirm] = useState<
      "disable" | "unlock" | "password-reset" | null
    >(null);
  const [dirty, setDirty] = useState(false);
  const fill = (row: AdminUser) => {
    setDraft({
      ...empty(),
      ...row,
      email: row.email ?? "",
      phone: row.phone ?? "",
      resignationDate: row.resignationDate ?? "",
    });
    setSelected(row.userId);
    setCreating(false);
    setDirty(false);
  };
  const select = async (id: number) => {
    setSelected(null);
    setError(null);
    try {
      fill(await adminUser(id));
    } catch (e) {
      setError(e as ApiFailure);
    }
  };
  useEffect(() => {
    if (users.data && users.data.items.length === 0) {
      setSelected(null);
      setCreating(false);
      setDraft(empty());
    }
  }, [users.data]);
  const change = <K extends keyof Draft>(key: K, value: Draft[K]) => {
    setDraft((d) => ({ ...d, [key]: value }));
    setDirty(true);
  };
  const run = async (action: () => Promise<unknown>, id = selected) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    setSuccess("");
    try {
      const result = await action();
      await users.reload();
      if (id) fill(await adminUser(id));
      else if (result && typeof result === "object" && "userId" in result)
        fill(result as AdminUser);
      setSuccess("저장했습니다.");
      setDirty(false);
      setConfirm(null);
    } catch (e) {
      setError(e as ApiFailure);
    } finally {
      setBusy(false);
    }
  };
  const save = () => {
    if (creating) {
      if (!draft.roleIds.length) {
        setError(
          new ApiFailure("역할을 하나 이상 선택하세요.", 400, null, "roleIds"),
        );
        return;
      }
      const { status: _, resignationDate: __, ...body } = draft;
      void run(
        () => command<AdminUser>("/api/admin/users", "POST", body),
        null,
      );
    } else if (selected) {
      const { name, email, phone, partId, status, resignationDate } = draft;
      void run(() =>
        command(`/api/admin/users/${selected}`, "PATCH", {
          name,
          email,
          phone,
          partId,
          status,
          resignationDate: resignationDate || null,
        }),
      );
    }
  };
  const multi = (
    key: "roleIds" | "partIds" | "projectIds",
    options: { id: number; name: string }[],
    label: string,
  ) => (
    <label>
      {label}
      <select
        multiple
        data-testid={`user-${key.replace("Ids", "-ids")}-select`}
        value={draft[key].map(String)}
        onChange={(e) =>
          change(
            key,
            Array.from(e.target.selectedOptions, (o) => Number(o.value)),
          )
        }
      >
        {options.map((o) => (
          <option key={o.id} value={o.id}>
            {o.name}
          </option>
        ))}
      </select>
    </label>
  );
  return (
    <section
      className={styles.screen}
      data-screen-id="UI-23"
      data-testid="user-management-screen"
    >
      <ScreenHeading screenId="UI-23" name="사용자 관리" groupName="시스템" />
      <div className={styles.toolbar}>
        <Button
          data-testid="user-list-tab"
          aria-pressed={tab === "users"}
          onClick={() => setTab("users")}
        >
          사용자
        </Button>
        <Button
          data-testid="user-history-tab"
          aria-pressed={tab === "history"}
          onClick={() => setTab("history")}
        >
          접근 기록
        </Button>
      </div>
      {tab === "history" ? (
        <AccessHistoryPanel />
      ) : (
        <div data-testid="user-list-panel">
          <form
            className={styles.toolbar}
            onSubmit={(e) => {
              e.preventDefault();
              setSelected(null);
              setCreating(false);
              setSearch({ ...filters, page: 1 });
            }}
          >
            {(["name", "loginId"] as const).map((k) => (
              <label key={k}>
                {k === "name" ? "이름" : "아이디"}
                <input
                  data-testid={`user-search-${k.toLowerCase()}`}
                  value={filters[k]}
                  onChange={(e) =>
                    setFilters((f) => ({ ...f, [k]: e.target.value }))
                  }
                />
              </label>
            ))}
            <label>
              파트
              <select
                data-testid="user-search-part"
                value={filters.partId}
                onChange={(e) =>
                  setFilters((f) => ({ ...f, partId: e.target.value }))
                }
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
                data-testid="user-search-role"
                value={filters.roleId}
                onChange={(e) =>
                  setFilters((f) => ({ ...f, roleId: e.target.value }))
                }
              >
                <option value="">전체</option>
                {roles.data?.map((r) => (
                  <option key={r.roleId} value={r.roleId}>
                    {r.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              상태
              <select
                data-testid="user-search-status"
                value={filters.status}
                onChange={(e) =>
                  setFilters((f) => ({ ...f, status: e.target.value }))
                }
              >
                <option value="">전체</option>
                {["ACTIVE", "LOCKED", "DISABLED"].map((v) => (
                  <option key={v}>{v}</option>
                ))}
              </select>
            </label>
            <Button type="submit" data-testid="user-search-submit">
              검색
            </Button>
            <Can perm="UI-23.create">
              <Button
                type="button"
                data-testid="user-create"
                onClick={() => {
                  setSelected(null);
                  setCreating(true);
                  setDraft(empty());
                  setDirty(false);
                }}
              >
                등록
              </Button>
            </Can>
            <Can perm="UI-23.download">
              <Button
                type="button"
                data-testid="user-export"
                onClick={() =>
                  void downloadAdmin(
                    `/api/admin/users/export?${query(search)}`,
                  ).catch((e) => setError(e as ApiFailure))
                }
              >
                엑셀 내려받기
              </Button>
            </Can>
          </form>
          <ErrorBanner
            error={
              users.error ??
              error ??
              roles.error ??
              parts.error ??
              projects.error ??
              developers.error
            }
            onRetry={() => void users.reload().catch(() => {})}
          />
          {error?.field && (
            <p role="alert">
              {error.field}: {error.message}
            </p>
          )}
          {success && <p role="status">{success}</p>}
          <div className={styles.layout}>
            <div className={styles.panel}>
              {users.loading ? (
                <p role="status">불러오는 중…</p>
              ) : !users.data?.items.length ? (
                <p>검색 결과가 없습니다.</p>
              ) : (
                <div className={styles.scroll}>
                  <table>
                    <thead>
                      <tr>
                        <th>아이디</th>
                        <th>이름</th>
                        <th>이메일</th>
                        <th>역할</th>
                        <th>상태</th>
                        <th>최근 로그인</th>
                        <th>퇴사일</th>
                      </tr>
                    </thead>
                    <tbody>
                      {users.data.items.map((u) => (
                        <tr
                          key={u.userId}
                          data-testid={`user-row-${u.userId}`}
                          className={
                            selected === u.userId
                              ? styles.rowSelected
                              : undefined
                          }
                        >
                          <td>
                            <button
                              data-testid={`user-open-${u.userId}`}
                              onClick={() => void select(u.userId)}
                            >
                              {u.loginId}
                            </button>
                          </td>
                          <td>{u.name}</td>
                          <td>{u.email}</td>
                          <td>
                            {u.roleIds
                              .map(
                                (id) =>
                                  roles.data?.find((r) => r.roleId === id)
                                    ?.name ?? id,
                              )
                              .join(", ")}
                          </td>
                          <td>{u.status}</td>
                          <td>{u.lastLoginAt ?? "–"}</td>
                          <td>{u.resignationDate ?? "–"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <div className={styles.toolbar}>
                <Button
                  data-testid="user-page-previous"
                  disabled={Number(search.page ?? 1) <= 1}
                  onClick={() =>
                    setSearch((q) => ({ ...q, page: Number(q.page ?? 1) - 1 }))
                  }
                >
                  이전
                </Button>
                <span>{users.data?.total ?? 0}건</span>
                <Button
                  data-testid="user-page-next"
                  disabled={
                    !users.data ||
                    Number(search.page ?? 1) * 20 >= users.data.total
                  }
                  onClick={() =>
                    setSearch((q) => ({ ...q, page: Number(q.page ?? 1) + 1 }))
                  }
                >
                  다음
                </Button>
              </div>
            </div>
            <aside
              className={`${styles.panel} ${styles.fields}`}
              data-testid="user-detail-panel"
            >
              {!creating && !selected ? (
                <p>사용자를 선택하세요.</p>
              ) : (
                <>
                  <h2>{creating ? "사용자 등록" : "사용자 상세"}</h2>
                  {(["loginId", "name", "email", "phone"] as const).map((k) => (
                    <label key={k}>
                      {
                        {
                          loginId: "아이디",
                          name: "이름",
                          email: "이메일",
                          phone: "전화번호",
                        }[k]
                      }
                      <input
                        data-testid={`user-field-${k.toLowerCase()}`}
                        required={k !== "phone"}
                        readOnly={k === "loginId" && !creating}
                        maxLength={
                          k === "email" ? 100 : k === "phone" ? 30 : 50
                        }
                        type={k === "email" ? "email" : "text"}
                        value={draft[k]}
                        onChange={(e) => change(k, e.target.value)}
                      />
                    </label>
                  ))}
                  <label>
                    기본 파트
                    <select
                      data-testid="user-part"
                      value={draft.partId ?? ""}
                      onChange={(e) =>
                        change(
                          "partId",
                          e.target.value ? Number(e.target.value) : null,
                        )
                      }
                    >
                      <option value="">없음</option>
                      {parts.data?.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  {creating && (
                    <label>
                      연결 개발자
                      <select
                        data-testid="user-developer"
                        value={draft.developerId ?? ""}
                        onChange={(e) =>
                          change(
                            "developerId",
                            e.target.value ? Number(e.target.value) : null,
                          )
                        }
                      >
                        <option value="">없음</option>
                        {developers.data?.map((d) => (
                          <option key={d.id} value={d.id}>
                            {d.name}
                          </option>
                        ))}
                      </select>
                    </label>
                  )}
                  {!creating && (
                    <>
                      <label>
                        상태
                        <select
                          data-testid="user-status"
                          value={draft.status}
                          onChange={(e) => change("status", e.target.value)}
                        >
                          {["ACTIVE", "LOCKED", "DISABLED"].map((v) => (
                            <option key={v}>{v}</option>
                          ))}
                        </select>
                      </label>
                      <label>
                        퇴사일
                        <input
                          data-testid="user-resignation-date"
                          type="date"
                          value={draft.resignationDate}
                          onChange={(e) =>
                            change("resignationDate", e.target.value)
                          }
                        />
                      </label>
                    </>
                  )}
                  <Can perm={creating ? "UI-23.create" : "UI-23.update"}>
                    <Button
                      variant="primary"
                      data-testid="user-save"
                      pending={busy}
                      onClick={save}
                    >
                      기본 정보 저장
                    </Button>
                  </Can>
                  {multi(
                    "roleIds",
                    roles.data
                      ?.filter((r) => r.useYn === "Y")
                      .map((r) => ({ id: r.roleId, name: r.name })) ?? [],
                    "역할",
                  )}
                  <label className={styles.check}>
                    <input
                      data-testid="user-risk-confirmation"
                      type="checkbox"
                      checked={draft.riskConfirmed}
                      onChange={(e) =>
                        change("riskConfirmed", e.target.checked)
                      }
                    />
                    관리자 권한 부여 위험을 확인했습니다.
                  </label>
                  {!creating && selected && (
                    <Can perm="UI-23.update">
                      <Button
                        data-testid="user-save-roles"
                        pending={busy}
                        onClick={() =>
                          void run(() =>
                            command(
                              `/api/admin/users/${selected}/roles`,
                              "PUT",
                              {
                                roleIds: draft.roleIds,
                                riskConfirmed: draft.riskConfirmed,
                              },
                            ),
                          )
                        }
                      >
                        역할 저장
                      </Button>
                    </Can>
                  )}
                  {multi(
                    "partIds",
                    parts.data?.map((p) => ({
                      id: Number(p.id),
                      name: p.name,
                    })) ?? [],
                    "담당 파트",
                  )}
                  {multi(
                    "projectIds",
                    projects.data?.map((p) => ({ id: p.id, name: p.name })) ??
                      [],
                    "담당 프로젝트",
                  )}
                  {!creating && selected && (
                    <>
                      <Can perm="UI-23.update">
                        <Button
                          data-testid="user-save-assignments"
                          pending={busy}
                          onClick={() =>
                            void run(() =>
                              command(
                                `/api/admin/users/${selected}/assignments`,
                                "PUT",
                                {
                                  partIds: draft.partIds,
                                  projectIds: draft.projectIds,
                                },
                              ),
                            )
                          }
                        >
                          담당 저장
                        </Button>
                        <Button
                          data-testid="user-unlock"
                          onClick={() => setConfirm("unlock")}
                        >
                          잠금 해제
                        </Button>
                        <Button
                          data-testid="user-reset"
                          onClick={() => setConfirm("password-reset")}
                        >
                          재설정 링크 보내기
                        </Button>
                      </Can>
                      <Can perm="UI-23.delete">
                        <Button
                          data-testid="user-disable"
                          onClick={() => setConfirm("disable")}
                        >
                          사용 중지
                        </Button>
                      </Can>
                    </>
                  )}
                  {confirm && selected && (
                    <div
                      className={styles.confirm}
                      data-testid="user-action-confirmation"
                    >
                      <p>선택 계정에 작업을 적용하고 기존 세션을 종료합니다.</p>
                      <Button
                        data-testid="user-action-confirm"
                        pending={busy}
                        onClick={() =>
                          void run(() =>
                            command(
                              `/api/admin/users/${selected}/${confirm}`,
                              "POST",
                            ),
                          )
                        }
                      >
                        확인
                      </Button>
                      <Button
                        data-testid="user-action-cancel"
                        onClick={() => setConfirm(null)}
                      >
                        취소
                      </Button>
                    </div>
                  )}
                  {dirty && (
                    <p>
                      저장하지 않은 변경이 있습니다. 역할과 담당 정보는 각각
                      저장하세요.
                    </p>
                  )}
                </>
              )}
            </aside>
          </div>
        </div>
      )}
    </section>
  );
}
