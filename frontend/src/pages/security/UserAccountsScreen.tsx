import { useCallback, useState } from "react";
import { Button } from "../../shared/Controls";
import { can } from "../../shared/types";
import { useAdminRead } from "../admin/useAdminResource";
import {
  all,
  download,
  get,
  write,
  type Page,
  type Row,
  type SecurityRole,
  type SecurityUser,
} from "./client";
import styles from "./Security.module.css";

export default function UserAccountsScreen() {
  const [query, setQuery] = useState(""),
    [search, setSearch] = useState(""),
    [page, setPage] = useState(0),
    [selected, setSelected] = useState<SecurityUser | null>(null),
    [creating, setCreating] = useState(false),
    [history, setHistory] = useState(false),
    [error, setError] = useState(""),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false),
    [confirmation, setConfirmation] = useState("");
  const [form, setForm] = useState({
    loginId: "",
    name: "",
    email: "",
    phone: "",
    status: "ACTIVE",
    resignationDate: "",
  });
  const [roleIds, setRoleIds] = useState<number[]>([]),
    [partIds, setPartIds] = useState<number[]>([]),
    [projectIds, setProjectIds] = useState<number[]>([]);
  const [basePartId, setBasePartId] = useState<number | null>(null);
  const [roleRiskConfirmed, setRoleRiskConfirmed] = useState(false);
  const params = new URLSearchParams({
    ...(history ? { action: search } : { name: search }),
    page: String(page),
    size: "20",
  });
  const path = `/api/admin/${history ? "access-history" : "users"}`;
  const list = useAdminRead(
    useCallback(
      () => get<Page<SecurityUser & Row>>(`${path}?${params}`),
      [path, search, page],
    ),
  );
  const options = useAdminRead(
    useCallback(async () => {
      const [roles, parts, projects] = await Promise.all([
        all<SecurityRole>("/api/admin/roles"),
        get<
          {
            id?: number;
            bsn_part_id?: number;
            name?: string;
            bsn_part_nm?: string;
          }[]
        >("/api/sales-parts/options"),
        get<{ id?: number; prj_id?: number; name?: string; prj_nm?: string }[]>(
          "/api/projects/options",
        ),
      ]);
      return {
        roles,
        parts: parts.map((row) => ({
          id: Number(row.id ?? row.bsn_part_id),
          name: row.name ?? row.bsn_part_nm,
        })),
        projects: projects.map((row) => ({
          id: Number(row.id ?? row.prj_id),
          name: row.name ?? row.prj_nm,
        })),
      };
    }, []),
  );
  const fill = (row: SecurityUser | null) => {
    setRoleRiskConfirmed(false);
    setSelected(row);
    setCreating(!row);
    setForm({
      loginId: row?.loginId ?? "",
      name: row?.name ?? "",
      email: row?.email ?? "",
      phone: row?.phone ?? "",
      status: row?.status ?? "ACTIVE",
      resignationDate: row?.resignationDate ?? "",
    });
    setRoleIds(row?.roleIds ?? []);
    setPartIds(row?.partIds ?? []);
    setProjectIds(row?.projectIds ?? []);
    setBasePartId(row?.basePartId ?? null);
    setConfirmation("");
  };
  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await action();
      await list.reload();
      setMessage("저장했습니다.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "처리 실패");
    } finally {
      setBusy(false);
    }
  };
  const select = (id: number) =>
    run(async () => fill(await get<SecurityUser>(`/api/admin/users/${id}`)));
  const checkboxes = (
    label: string,
    rows: { id: number; name?: string }[],
    values: number[],
    change: (ids: number[]) => void,
  ) => (
    <fieldset>
      <legend>{label}</legend>
      {rows.map((row) => (
        <label className={styles.check} key={row.id}>
          <input
            data-testid={`user-${label === "역할" ? "role" : label === "담당 파트" ? "part" : "project"}-${row.id}`}
            type="checkbox"
            checked={values.includes(row.id)}
            onChange={(e) =>
              change(
                e.target.checked
                  ? [...values, row.id]
                  : values.filter((id) => id !== row.id),
              )
            }
          />
          {row.name}
        </label>
      ))}
    </fieldset>
  );
  return (
    <section
      className={styles.screen}
      data-screen-id="SCR-UI-23"
      data-testid="user-accounts-screen"
    >
      <h1>사용자 관리</h1>
      <div className={styles.toolbar}>
        <Button
          data-testid="users-tab-button"
          onClick={() => {
            setHistory(false);
            setSearch("");
            setPage(0);
          }}
        >
          사용자
        </Button>
        <Button
          data-testid="access-history-tab-button"
          onClick={() => {
            setHistory(true);
            setSearch("");
            setPage(0);
            setSelected(null);
            setCreating(false);
          }}
        >
          접근 기록
        </Button>
      </div>
      <form
        className={styles.toolbar}
        onSubmit={(e) => {
          e.preventDefault();
          setSearch(query);
          setPage(0);
          setSelected(null);
          setCreating(false);
        }}
      >
        <label>
          {history ? "작업유형" : "이름"}
          <input
            data-testid="users-search-input"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
        <Button type="submit" data-testid="users-search-button">
          검색
        </Button>
        {!history && can("UI-23.create") && (
          <Button data-testid="user-create-button" onClick={() => fill(null)}>
            사용자 등록
          </Button>
        )}
        {can("UI-23.download") && (
          <Button
            data-testid="users-export-button"
            onClick={() =>
              void run(() =>
                download(
                  `${path}/export?${new URLSearchParams(history ? { action: search } : { name: search })}`,
                  "관리목록.xlsx",
                ),
              )
            }
          >
            엑셀 내려받기
          </Button>
        )}
      </form>
      {(error || list.error) && (
        <p role="alert">{error || list.error?.message}</p>
      )}
      {message && <p role="status">{message}</p>}
      {list.loading ? (
        <p role="status">불러오는 중…</p>
      ) : (
        <div className={styles.split}>
          <div className={styles.panel}>
            <div className={styles.scroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    {(history
                      ? ["시각", "사용자", "유형", "화면", "대상", "IP"]
                      : ["아이디", "이름", "역할", "상태", "최근 로그인"]
                    ).map((label) => (
                      <th key={label}>{label}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {list.data?.items.map((row) => (
                    <tr key={row.id} data-testid={`users-row-${row.id}`}>
                      {history ? (
                        [
                          row.occurredAt,
                          row.userId,
                          row.action,
                          row.screenId,
                          row.target,
                          row.ip,
                        ].map((value, index) => (
                          <td key={index}>{String(value ?? "–")}</td>
                        ))
                      ) : (
                        <>
                          <td>
                            <Button
                              data-testid={`user-open-${row.id}`}
                              onClick={() => void select(row.id)}
                            >
                              {row.loginId}
                            </Button>
                          </td>
                          <td>{row.name}</td>
                          <td>
                            {row.roleIds
                              ?.map(
                                (id) =>
                                  options.data?.roles.find(
                                    (role) => role.id === id,
                                  )?.name ?? id,
                              )
                              .join(", ")}
                          </td>
                          <td>{row.status}</td>
                          <td>{row.lastLoginAt ?? "–"}</td>
                        </>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {list.data?.items.length === 0 && <p>검색 결과가 없습니다.</p>}
            <div className={styles.toolbar}>
              <Button
                data-testid="users-previous-button"
                disabled={page === 0}
                onClick={() => setPage(page - 1)}
              >
                이전
              </Button>
              <span>
                {page + 1} · 전체 {list.data?.total ?? 0}건
              </span>
              <Button
                data-testid="users-next-button"
                disabled={!list.data || (page + 1) * 20 >= list.data.total}
                onClick={() => setPage(page + 1)}
              >
                다음
              </Button>
            </div>
          </div>
          {!history && (selected || creating) && (
            <aside className={styles.panel} data-testid="user-detail-panel">
              <h2>{creating ? "계정 등록" : "계정 상세"}</h2>
              <form
                className={styles.form}
                onSubmit={(e) => {
                  e.preventDefault();
                  void run(async () => {
                    if (creating) {
                      const created = await write<{ user: SecurityUser }>(
                        "/api/admin/users",
                        "POST",
                        {
                          loginId: form.loginId,
                          name: form.name,
                          email: form.email,
                          basePartId,
                          partIds,
                          projectIds,
                          developerId: null,
                          roleIds,
                        },
                      );
                      fill(created.user);
                    } else if (selected)
                      fill(
                        await write<SecurityUser>(
                          `/api/admin/users/${selected.id}`,
                          "PATCH",
                          {
                            name: form.name,
                            email: form.email,
                            phone: form.phone,
                            basePartId,
                            status: form.status,
                            resignationDate: form.resignationDate || null,
                          },
                        ),
                      );
                  });
                }}
              >
                {(["loginId", "name", "email", "phone"] as const)
                  .filter((key) => !creating || key !== "phone")
                  .map((key) => (
                    <label key={key}>
                      {
                        {
                          loginId: "아이디",
                          name: "이름",
                          email: "이메일",
                          phone: "연락처",
                        }[key]
                      }
                      <input
                        data-testid={`user-${key.replace("loginId", "login-id")}-input`}
                        readOnly={!creating && key === "loginId"}
                        type={key === "email" ? "email" : "text"}
                        value={form[key]}
                        required={key !== "phone"}
                        maxLength={key === "email" ? 100 : 50}
                        onChange={(e) =>
                          setForm({ ...form, [key]: e.target.value })
                        }
                      />
                    </label>
                  ))}
                <label>
                  기본 파트
                  <select
                    data-testid="user-base-part-select"
                    value={basePartId ?? ""}
                    onChange={(e) =>
                      setBasePartId(
                        e.target.value ? Number(e.target.value) : null,
                      )
                    }
                  >
                    <option value="">미지정</option>
                    {options.data?.parts.map((part) => (
                      <option key={part.id} value={part.id}>
                        {part.name}
                      </option>
                    ))}
                  </select>
                </label>
                {!creating && (
                  <>
                    <label>
                      상태
                      <select
                        data-testid="user-status-select"
                        value={form.status}
                        onChange={(e) =>
                          setForm({ ...form, status: e.target.value })
                        }
                      >
                        {["ACTIVE", "LOCKED", "DISABLED"].map((value) => (
                          <option key={value}>{value}</option>
                        ))}
                      </select>
                    </label>
                    <label>
                      퇴사일
                      <input
                        data-testid="user-resignation-date-input"
                        type="date"
                        value={form.resignationDate}
                        onChange={(e) =>
                          setForm({ ...form, resignationDate: e.target.value })
                        }
                      />
                    </label>
                  </>
                )}
                {creating && options.data && (
                  <>
                    {checkboxes(
                      "역할",
                      options.data.roles,
                      roleIds,
                      setRoleIds,
                    )}
                    {checkboxes(
                      "담당 파트",
                      options.data.parts,
                      partIds,
                      setPartIds,
                    )}
                    {checkboxes(
                      "담당 프로젝트",
                      options.data.projects,
                      projectIds,
                      setProjectIds,
                    )}
                  </>
                )}
                {can(`UI-23.${creating ? "create" : "update"}`) && (
                  <Button
                    type="submit"
                    data-testid="user-save-button"
                    disabled={busy}
                  >
                    저장
                  </Button>
                )}
              </form>
              {selected && can("UI-23.update") && options.data && (
                <div className={styles.form}>
                  {checkboxes("역할", options.data.roles, roleIds, setRoleIds)}
                  <label className={styles.check}>
                    <input
                      data-testid="user-admin-risk-checkbox"
                      type="checkbox"
                      checked={roleRiskConfirmed}
                      onChange={(e) => setRoleRiskConfirmed(e.target.checked)}
                    />
                    새 관리자 역할 부여의 위험을 확인했습니다.
                  </label>
                  <Button
                    data-testid="user-roles-save-button"
                    disabled={busy}
                    onClick={() =>
                      void run(async () => {
                        fill(
                          await write<SecurityUser>(
                            `/api/admin/users/${selected.id}/roles`,
                            "PUT",
                            { roleIds, riskConfirmed: roleRiskConfirmed },
                          ),
                        );
                        setRoleRiskConfirmed(false);
                      })
                    }
                  >
                    역할 저장
                  </Button>
                  {checkboxes(
                    "담당 파트",
                    options.data.parts,
                    partIds,
                    setPartIds,
                  )}
                  {checkboxes(
                    "담당 프로젝트",
                    options.data.projects,
                    projectIds,
                    setProjectIds,
                  )}
                  <p>
                    연결 개발자: {selected.developerId ?? "미지정"} (읽기 전용)
                  </p>
                  <Button
                    data-testid="user-assignments-save-button"
                    disabled={busy}
                    onClick={() =>
                      void run(async () =>
                        fill(
                          await write<SecurityUser>(
                            `/api/admin/users/${selected.id}/assignments`,
                            "PUT",
                            {
                              basePartId,
                              partIds,
                              projectIds,
                              developerId: selected.developerId ?? null,
                            },
                          ),
                        ),
                      )
                    }
                  >
                    담당 저장
                  </Button>
                  <div className={styles.toolbar}>
                    {["unlock", "password-reset", "disable"].map((action) => (
                      <Button
                        key={action}
                        data-testid={`user-${action}-button`}
                        disabled={
                          busy || (action === "disable" && !can("UI-23.delete"))
                        }
                        onClick={() => setConfirmation(action)}
                      >
                        {
                          {
                            unlock: "잠금 해제",
                            "password-reset": "재설정 메일",
                            disable: "사용 중지",
                          }[action]
                        }
                      </Button>
                    ))}
                  </div>
                  {confirmation && (
                    <div
                      className={styles.confirm}
                      data-testid="user-action-confirmation"
                    >
                      <p>
                        선택 계정에 작업을 실행할까요? 기존 세션이 종료될 수
                        있습니다.
                      </p>
                      <Button
                        data-testid="user-action-confirm-button"
                        onClick={() =>
                          void run(async () => {
                            await write(
                              `/api/admin/users/${selected.id}/${confirmation}`,
                              "POST",
                            );
                            setConfirmation("");
                            fill(
                              await get<SecurityUser>(
                                `/api/admin/users/${selected.id}`,
                              ),
                            );
                          })
                        }
                      >
                        확인
                      </Button>
                      <Button
                        data-testid="user-action-cancel-button"
                        onClick={() => setConfirmation("")}
                      >
                        취소
                      </Button>
                    </div>
                  )}
                </div>
              )}
            </aside>
          )}
        </div>
      )}
    </section>
  );
}
