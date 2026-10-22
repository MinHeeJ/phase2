import { useEffect, useRef, useState } from "react";
import { Lock, Plus } from "lucide-react";
import {
  api,
  allPages,
  groupPath,
  ApiError,
  type Group,
  type CodeRow,
} from "../../services/api";
import {
  Busy,
  Can,
  Empty,
  ErrorBanner,
  Loading,
} from "../../components/Common";
export function CodeEditor({
  groups,
  guard,
  setDirty,
  toast,
}: {
  groups: Group[];
  guard: (action: () => void) => void;
  setDirty: (dirty: boolean) => void;
  toast: (text: string) => void;
}) {
  const [groupId, setGroupId] = useState("");
  const [rows, setRows] = useState<CodeRow[]>([]);
  const [saved, setSaved] = useState<CodeRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const [revision, setRevision] = useState(0);
  const group = groups.find((g) => g.cd_grp_id === groupId);
  const system = group?.cd_grp_se_cd === "SYSTEM";
  const [loadedGroup, setLoadedGroup] = useState("");
  const edited = useRef(false);
  useEffect(() => {
    if (!groupId && groups.length) setGroupId(groups[0].cd_grp_id);
  }, [groups, groupId]);
  useEffect(() => {
    if (!groupId) return;
    const c = new AbortController();
    setLoading(true);
    if (loadedGroup !== groupId) {
      setRows([]);
      setSaved([]);
      edited.current = false;
    }
    setError(undefined);
    allPages<CodeRow>(groupPath(groupId), c.signal)
      .then((data) => {
        if (!edited.current) {
          setRows(data);
          setDirty(false);
        }
        setSaved(data);
        setLoadedGroup(groupId);
      })
      .catch((e) => {
        if (!c.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [groupId, revision]);
  function edit(
    index: number,
    field: keyof CodeRow,
    value: string | number | null,
  ) {
    edited.current = true;
    setRows((prev) =>
      prev.map((row, i) => (i === index ? { ...row, [field]: value } : row)),
    );
    setDirty(true);
  }
  const attrs = ([1, 2, 3] as const).filter((n) => group?.[`attr${n}_nm`]);
  useEffect(() => {
    const discard = () => {
      edited.current = false;
      setRows(saved);
      setDirty(false);
      setError(undefined);
    };
    window.addEventListener("si.discard", discard);
    return () => window.removeEventListener("si.discard", discard);
  }, [saved, setDirty]);
  async function save() {
    if (busy || loading || loadedGroup !== groupId) return;
    const first = rows.findIndex((r) => !r.dtl_cd.trim() || !r.cd_nm.trim());
    if (first >= 0) {
      const field = !rows[first].dtl_cd.trim() ? "dtl_cd" : "cd_nm";
      setError(
        new ApiError(
          field === "dtl_cd" ? "코드를 입력하세요." : "이름을 입력하세요.",
          field,
        ),
      );
      document.getElementById(`code-${first}-${field}`)?.focus();
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      await api(
        groupPath(groupId),
        "PUT",
        rows.map(
          ({
            dtl_cd,
            cd_nm,
            sort_seq,
            use_yn,
            attr1_val,
            attr2_val,
            attr3_val,
            aply_bgn_dt,
          }) => ({
            dtl_cd,
            cd_nm,
            sort_seq,
            use_yn,
            attr1_val,
            attr2_val,
            attr3_val,
            aply_bgn_dt,
          }),
        ),
      );
      edited.current = false;
      setDirty(false);
      setRevision((v) => v + 1);
      toast("공통코드를 저장했습니다.");
    } catch (e) {
      setError(e);
      if (e instanceof ApiError && e.field)
        document
          .querySelector<HTMLInputElement>(`[data-code-field="${e.field}"]`)
          ?.focus();
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="codeLayout">
      <aside className="panel groups" aria-label="코드 그룹">
        <div className="groupHeading">
          <h2>코드 그룹</h2>
          <small>총 {groups.length}개</small>
        </div>
        {!groups.length && <Empty />}
        {groups.map((g) => (
          <button
            key={g.cd_grp_id}
            disabled={busy}
            aria-pressed={groupId === g.cd_grp_id}
            className={groupId === g.cd_grp_id ? "selected" : ""}
            onClick={() => {
              if (g.cd_grp_id !== groupId) guard(() => setGroupId(g.cd_grp_id));
            }}
          >
            <span>{g.cd_grp_nm}</span>
            {g.cd_grp_se_cd === "SYSTEM" ? (
              <Lock size={14} />
            ) : (
              <small>{g.codeCount}</small>
            )}
          </button>
        ))}
      </aside>
      <section className="panel codeEditor">
        <ErrorBanner error={error} retry={() => setRevision((v) => v + 1)} />
        <h2>
          {group?.cd_grp_nm} <small>총 {rows.length}건</small>
        </h2>
        {system && (
          <div className="notice">
            <Lock size={14} /> 계산 규칙에 쓰는 시스템 코드입니다. 이름만 바꿀
            수 있고 추가·삭제는 할 수 없습니다.
          </div>
        )}
        {loading ? (
          <Loading />
        ) : (
          <>
            <div className="tableScroll">
              <table>
                <thead>
                  <tr>
                    <th className="center">코드</th>
                    <th>
                      이름 <b className="danger">*</b>
                    </th>
                    {attrs.map((n) => (
                      <th key={n}>{group?.[`attr${n}_nm`]}</th>
                    ))}
                    <th>순서</th>
                    <th className="center">사용</th>
                    <th className="center">적용 시작일</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r, i) => {
                    const existing = i < saved.length;
                    return (
                      <tr key={i}>
                        <td>
                          <input
                            id={`code-${i}-dtl_cd`}
                            data-code-field="dtl_cd"
                            className="center"
                            aria-invalid={
                              error instanceof ApiError &&
                              error.field === "dtl_cd"
                            }
                            aria-label={`코드 ${i + 1}`}
                            value={r.dtl_cd}
                            maxLength={30}
                            readOnly={existing}
                            disabled={busy}
                            onChange={(e) => edit(i, "dtl_cd", e.target.value)}
                            onBlur={(e) => {
                              if (!e.target.value.trim())
                                setError(
                                  new ApiError("코드를 입력하세요.", "dtl_cd"),
                                );
                            }}
                          />
                          {error instanceof ApiError &&
                            error.field === "dtl_cd" &&
                            !r.dtl_cd.trim() && (
                              <small className="danger cellError">
                                코드를 입력하세요.
                              </small>
                            )}
                        </td>
                        <td>
                          <input
                            id={`code-${i}-cd_nm`}
                            data-code-field="cd_nm"
                            aria-invalid={
                              error instanceof ApiError &&
                              error.field === "cd_nm"
                            }
                            aria-label={`이름 ${i + 1}`}
                            value={r.cd_nm}
                            maxLength={100}
                            disabled={busy}
                            onChange={(e) => edit(i, "cd_nm", e.target.value)}
                            onBlur={(e) => {
                              if (!e.target.value.trim())
                                setError(
                                  new ApiError("이름을 입력하세요.", "cd_nm"),
                                );
                            }}
                          />
                          {error instanceof ApiError &&
                            error.field === "cd_nm" &&
                            !r.cd_nm.trim() && (
                              <small className="danger cellError">
                                이름을 입력하세요.
                              </small>
                            )}
                        </td>
                        {attrs.map((n) => (
                          <td key={n}>
                            <input
                              aria-label={`${group?.[`attr${n}_nm`]} ${i + 1}`}
                              value={r[`attr${n}_val`] ?? ""}
                              maxLength={100}
                              readOnly={system}
                              disabled={busy}
                              onChange={(e) =>
                                edit(i, `attr${n}_val`, e.target.value || null)
                              }
                            />
                          </td>
                        ))}
                        <td>
                          <input
                            aria-label={`순서 ${i + 1}`}
                            className="numeric"
                            type="number"
                            step="1"
                            value={r.sort_seq}
                            disabled={busy}
                            onChange={(e) =>
                              edit(i, "sort_seq", Number(e.target.value))
                            }
                          />
                        </td>
                        <td className="center">
                          <Can perm="UI-27.delete">
                            <input
                              aria-label={`사용 ${i + 1}`}
                              type="checkbox"
                              checked={r.use_yn === "Y"}
                              disabled={system || busy}
                              onChange={(e) =>
                                edit(i, "use_yn", e.target.checked ? "Y" : "N")
                              }
                            />
                          </Can>
                        </td>
                        <td>
                          <input
                            type="date"
                            aria-label={`적용 시작일 ${i + 1}`}
                            value={r.aply_bgn_dt ?? ""}
                            readOnly={system}
                            disabled={busy}
                            onChange={(e) =>
                              edit(i, "aply_bgn_dt", e.target.value || null)
                            }
                          />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {!rows.length && <Empty />}
            <div className="toolbar">
              {!system && group && (
                <Can perm="UI-27.create">
                  <button
                    disabled={busy || loading || loadedGroup !== groupId}
                    onClick={() => {
                      edited.current = true;
                      setRows((prev) => [
                        ...prev,
                        {
                          dtl_cd: "",
                          cd_nm: "",
                          sort_seq:
                            Math.max(0, ...prev.map((r) => r.sort_seq)) + 1,
                          use_yn: "Y",
                          attr1_val: null,
                          attr2_val: null,
                          attr3_val: null,
                          aply_bgn_dt: null,
                        },
                      ]);
                      setDirty(true);
                    }}
                  >
                    <Plus size={16} />
                    코드 추가
                  </button>
                </Can>
              )}
            </div>
            <div className="actions">
              <button
                disabled={busy}
                onClick={() => {
                  edited.current = false;
                  setRows(saved);
                  setDirty(false);
                  setError(undefined);
                }}
              >
                취소
              </button>
              <Can perm="UI-27.update">
                <Busy
                  className="primary"
                  busy={busy}
                  disabled={!group || loadedGroup !== groupId}
                  onClick={save}
                >
                  저장
                </Busy>
              </Can>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
