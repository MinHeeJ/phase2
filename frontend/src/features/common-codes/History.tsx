import { useEffect, useState } from "react";
import { api, type Change, type Group, type Page } from "../../services/api";
import {
  Empty,
  ErrorBanner,
  Loading,
  Pagination,
} from "../../components/Common";
import { usePreferences } from "../../stores/preferences";
import { date } from "../../lib/format";
const typeName = {
  ADD: "추가",
  UPDATE: "수정",
  DISABLE: "사용 중지",
  ENABLE: "다시 사용",
};
export function History({ groups }: { groups: Group[] }) {
  const pref = usePreferences();
  const [filter, setFilter] = useState("");
  const [group, setGroup] = useState("");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<Page<Change>>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const c = new AbortController();
    setLoading(true);
    setError(undefined);
    api<Page<Change>>(
      `/api/admin/code-history?page=${page}&size=${pref.size}${group ? `&group=${encodeURIComponent(group)}` : ""}`,
      "GET",
      undefined,
      c.signal,
    )
      .then(setData)
      .catch((e) => {
        if (!c.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [page, pref.size, group, revision]);
  return (
    <section className="panel">
      <h2>변경 이력</h2>
      <ErrorBanner
        error={error}
        retry={() => {
          setError(undefined);
          setRevision((v) => v + 1);
        }}
      />
      <form
        className="toolbar filterBar"
        onSubmit={(e) => {
          e.preventDefault();
          setGroup(filter);
          setPage(1);
          setRevision((v) => v + 1);
        }}
      >
        <label>
          그룹{" "}
          <select
            aria-label="이력 그룹"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          >
            <option value="">전체</option>
            {groups.map((g) => (
              <option key={g.cd_grp_id} value={g.cd_grp_id}>
                {g.cd_grp_nm}
              </option>
            ))}
            <option value="PARAM">기준값</option>
          </select>
        </label>
        <div className="actions">
          <button
            type="button"
            onClick={() => {
              setFilter("");
              setGroup("");
              setPage(1);
            }}
          >
            초기화
          </button>
          <button className="primary">조회</button>
        </div>
      </form>
      {loading ? (
        <Loading />
      ) : (
        <>
          <p>총 {data?.total ?? 0}건</p>
          <div className="tableScroll">
            <table>
              <thead>
                <tr>
                  {[
                    "일시",
                    "그룹",
                    "코드",
                    "구분",
                    "항목",
                    "이전",
                    "이후",
                    "변경자",
                  ].map((t) => (
                    <th key={t}>{t}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data?.items.map((r, i) => (
                  <tr key={i}>
                    <td className="center">{date(r.chg_at, "datetime")}</td>
                    <td>{r.cd_grp_id === "PARAM" ? "기준값" : r.cd_grp_nm}</td>
                    <td className="center">{r.dtl_cd ?? "–"}</td>
                    <td className="center">
                      <span className="badge">{typeName[r.cd_chg_ty_cd]}</span>
                    </td>
                    <td>{r.item_nm}</td>
                    <td>{r.bfr_val ?? "–"}</td>
                    <td>{r.aft_val ?? "–"}</td>
                    <td>
                      {r.chg_user_id === null ? "기본 사용자" : r.chg_user_id}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!data?.items.length && <Empty />}
          {data && (
            <Pagination
              page={page}
              size={pref.size}
              total={data.total}
              onPage={setPage}
              onSize={(size) => {
                pref.set({ size });
                setPage(1);
              }}
            />
          )}
        </>
      )}
    </section>
  );
}
