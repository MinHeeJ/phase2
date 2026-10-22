import { useEffect, useRef, useState } from "react";
import {
  allPages,
  api,
  paramPath,
  ApiError,
  type Param,
} from "../../services/api";
import {
  Busy,
  Can,
  Empty,
  ErrorBanner,
  Loading,
  Modal,
  TextField,
} from "../../components/Common";
import { todayMonth } from "../../screens/registry";
const value = (r: Param) =>
  `${r.num_val !== null ? r.num_val.toLocaleString("ko-KR", { maximumFractionDigits: 4 }) : (r.txt_val ?? "–")} ${r.unit_nm ?? ""}`;
export function Parameters({ toast }: { toast: (s: string) => void }) {
  const [rows, setRows] = useState<Param[]>([]);
  const [versions, setVersions] = useState<Param[]>([]);
  const [key, setKey] = useState("");
  const [selected, setSelected] = useState<Param>();
  const [error, setError] = useState<unknown>();
  const [versionError, setVersionError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [versionLoading, setVersionLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  const versionRequest = useRef(0);
  useEffect(
    () => () => {
      versionRequest.current++;
    },
    [],
  );
  useEffect(() => {
    const c = new AbortController();
    setLoading(true);
    allPages<Param>("/api/admin/params", c.signal)
      .then(setRows)
      .catch((e) => {
        if (!c.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [revision]);
  async function showVersions(id: string) {
    const request = ++versionRequest.current;
    setKey(id);
    setVersions([]);
    setVersionLoading(true);
    setVersionError(undefined);
    try {
      const result = await allPages<Param>(paramPath(id));
      if (request === versionRequest.current) setVersions(result);
    } catch (e) {
      if (request === versionRequest.current) setVersionError(e);
    } finally {
      if (request === versionRequest.current) setVersionLoading(false);
    }
  }
  return (
    <section className="panel">
      <h2>기준값</h2>
      <ErrorBanner
        error={error}
        retry={() => {
          setError(undefined);
          setRevision((v) => v + 1);
        }}
      />
      {loading ? (
        <Loading />
      ) : (
        <>
          <div className="tableScroll">
            <table>
              <thead>
                <tr>
                  <th>항목</th>
                  <th>지금 값</th>
                  <th>적용 시작월</th>
                  <th>쓰는 곳</th>
                  <th>동작</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.oprt_std_id}>
                    <td>{r.oprt_std_nm}</td>
                    <td className="numeric">{value(r)}</td>
                    <td className="center">{r.aply_bgn_ym}</td>
                    <td>{r.use_plc_desc}</td>
                    <td>
                      <Can perm="UI-27.update">
                        <button onClick={() => setSelected(r)}>값 변경</button>
                      </Can>{" "}
                      <button onClick={() => void showVersions(r.oprt_std_id)}>
                        이력 보기
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!rows.length && <Empty />}
        </>
      )}
      {key && (
        <section className="versionRegion">
          <h2>
            {rows.find((r) => r.oprt_std_id === key)?.oprt_std_nm ?? key} 적용
            이력 <small>{key}</small>
          </h2>
          <ErrorBanner
            error={versionError}
            retry={() => void showVersions(key)}
          />
          {!versionLoading && !versionError && !versions.length && <Empty />}
          {versionLoading ? (
            <Loading />
          ) : (
            <div className="tableScroll">
              <table>
                <thead>
                  <tr>
                    <th>적용 시작월</th>
                    <th>값</th>
                  </tr>
                </thead>
                <tbody>
                  {versions
                    .filter((r) => r.oprt_std_id === key)
                    .map((r) => (
                      <tr key={r.aply_bgn_ym}>
                        <td className="center">{r.aply_bgn_ym}</td>
                        <td className="numeric">{value(r)}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
      {selected && (
        <ParamForm
          row={selected}
          onClose={() => setSelected(undefined)}
          onSaved={() => {
            setSelected(undefined);
            setRevision((v) => v + 1);
            if (key) void showVersions(key);
            toast("기준값을 저장했습니다.");
          }}
        />
      )}
    </section>
  );
}
function ParamForm({
  row,
  onClose,
  onSaved,
}: {
  row: Param;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [month, setMonth] = useState(todayMonth());
  const [min, setMin] = useState<string>();
  const [newValue, setValue] = useState(
    String(row.num_val ?? row.txt_val ?? ""),
  );
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    if (!busy && error instanceof ApiError && error.field)
      document.getElementsByName(error.field)[0]?.focus();
  }, [busy, error]);
  useEffect(() => {
    const c = new AbortController();
    setLoaded(false);
    setError(undefined);
    api<{ month: string; status: string }[]>(
      "/api/month-close?from=0001-01&to=9999-12",
      "GET",
      undefined,
      c.signal,
    )
      .then((rows) => {
        const last = rows
          .filter((r) => r.status === "CLOSED")
          .map((r) => r.month)
          .sort()
          .at(-1);
        if (last) {
          const [y, m] = last.split("-").map(Number);
          const next = `${m === 12 ? y + 1 : y}-${String(m === 12 ? 1 : m + 1).padStart(2, "0")}`;
          setMin(next);
          setMonth((current) => (current < next ? next : current));
        }
        setLoaded(true);
      })
      .catch((e) => {
        if (!c.signal.aborted) setError(e);
      });
    return () => c.abort();
  }, [revision]);
  return (
    <Modal
      title="값 변경"
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <p>
        {row.oprt_std_nm} · {row.unit_nm}
      </p>
      <ErrorBanner
        error={error}
        retry={!loaded ? () => setRevision((v) => v + 1) : undefined}
      />
      {!loaded && !error && <Loading />}
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy || !loaded) return;
          setBusy(true);
          setError(undefined);
          try {
            await api(
              `${paramPath(row.oprt_std_id)}/${encodeURIComponent(month)}`,
              "PUT",
              row.num_val !== null
                ? { num_val: Number(newValue) }
                : { txt_val: newValue },
            );
            onSaved();
          } catch (e) {
            setError(e);
          } finally {
            setBusy(false);
          }
        }}
      >
        <div className="formGrid">
          <TextField
            label="새 값"
            invalidMessage="올바른 값을 입력하세요."
            name={row.num_val !== null ? "num_val" : "txt_val"}
            error={
              error instanceof ApiError &&
              (error.field === "num_val" || error.field === "txt_val")
                ? error.message
                : undefined
            }
            value={newValue}
            required
            type={row.num_val !== null ? "number" : "text"}
            min={row.num_val !== null ? 0 : undefined}
            max={row.unit_nm === "%" || row.unit_nm === "%p" ? 100 : undefined}
            step="0.0001"
            maxLength={row.num_val === null ? 200 : undefined}
            disabled={busy}
            onChange={(e) => setValue(e.target.value)}
          />
          <TextField
            label="적용 시작월"
            name="month"
            error={
              error instanceof ApiError && error.field === "month"
                ? error.message
                : undefined
            }
            type="month"
            min={min}
            value={month}
            required
            disabled={busy || !loaded}
            onChange={(e) => setMonth(e.target.value)}
          />
        </div>
        <div className="actions">
          <button type="button" disabled={busy} onClick={onClose}>
            취소
          </button>
          <Busy busy={busy} disabled={!loaded} className="primary">
            저장
          </Busy>
        </div>
      </form>
    </Modal>
  );
}
