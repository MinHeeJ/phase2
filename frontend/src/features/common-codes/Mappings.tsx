import { useEffect, useState } from "react";
import {
  allPages,
  api,
  useCodes,
  ApiError,
  type Group,
  type ExcelMap,
} from "../../services/api";
import {
  Busy,
  Can,
  Empty,
  ErrorBanner,
  Loading,
  Modal,
  Pagination,
  TextField,
} from "../../components/Common";
import { usePreferences } from "../../stores/preferences";
export function Mappings({
  groups,
  toast,
}: {
  groups: Group[];
  toast: (s: string) => void;
}) {
  const [rows, setRows] = useState<ExcelMap[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>();
  const [revision, setRevision] = useState(0);
  const [open, setOpen] = useState(false);
  const [page, setPage] = useState(1);
  const pref = usePreferences();
  useEffect(() => {
    const c = new AbortController();
    setLoading(true);
    allPages<ExcelMap>("/api/admin/excel-maps", c.signal)
      .then(setRows)
      .catch((e) => {
        if (!c.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [revision]);
  return (
    <section className="panel">
      <ErrorBanner
        error={error}
        retry={() => {
          setError(undefined);
          setRevision((v) => v + 1);
        }}
      />
      <div className="toolbar">
        <h2>
          엑셀 표기 대응 <small>총 {rows.length}건</small>
        </h2>
        <Can perm="UI-27.create">
          <button className="primary" onClick={() => setOpen(true)}>
            대응 추가
          </button>
        </Can>
      </div>
      {loading ? (
        <Loading />
      ) : (
        <>
          <div className="tableScroll">
            <table>
              <thead>
                <tr>
                  <th>엑셀 표기</th>
                  <th>바꾸는 값</th>
                  <th>주/보조</th>
                  <th>메모</th>
                  <th>사용</th>
                </tr>
              </thead>
              <tbody>
                {rows
                  .slice((page - 1) * pref.size, page * pref.size)
                  .map((r) => (
                    <tr key={r.map_id}>
                      <td>{r.excel_lbl_nm}</td>
                      <td>
                        {r.cd_grp_nm} · {r.cd_nm}
                      </td>
                      <td>{r.map_role_cd === "MAIN" ? "주" : "보조"}</td>
                      <td>{r.rmrk_desc}</td>
                      <td className="center">
                        {r.use_yn === "Y" ? "사용" : "사용 안 함"}
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
          {!rows.length && <Empty />}
          <Pagination
            page={page}
            size={pref.size}
            total={rows.length}
            onPage={setPage}
            onSize={(size) => {
              pref.set({ size });
              setPage(1);
            }}
          />
          <p className="notice">
            기존 대응의 편집 확정 방식은 확인 중입니다. 표기와 대상은 변경할 수
            없습니다.
          </p>
        </>
      )}
      {open && (
        <MapForm
          groups={groups}
          onClose={() => setOpen(false)}
          onSaved={() => {
            setOpen(false);
            setRevision((v) => v + 1);
            toast("대응을 추가했습니다.");
          }}
        />
      )}
    </section>
  );
}
function MapForm({
  groups,
  onClose,
  onSaved,
}: {
  groups: Group[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState({
    excel_lbl_nm: "",
    trns_cd_grp_id: "",
    trns_cd: "",
    map_role_cd: "",
    rmrk_desc: "",
  });
  const {
    options,
    error: codeError,
    loading: codesLoading,
    retry,
  } = useCodes(form.trns_cd_grp_id);
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!busy && error instanceof ApiError && error.field)
      document.getElementsByName(error.field)[0]?.focus();
  }, [busy, error]);
  const fieldError = (field: string) =>
    error instanceof ApiError && error.field === field
      ? error.message
      : undefined;
  const set = (field: string, value: string) =>
    setForm((s) => ({ ...s, [field]: value }));
  return (
    <Modal
      title="대응 추가"
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <ErrorBanner error={error} />
      <ErrorBanner error={codeError} retry={retry} />
      {codesLoading && <Loading />}
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy || codesLoading || codeError) return;
          setBusy(true);
          try {
            await api("/api/admin/excel-maps", "POST", form);
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
            label="엑셀 표기"
            name="excel_lbl_nm"
            value={form.excel_lbl_nm}
            error={
              error instanceof ApiError && error.field === "excel_lbl_nm"
                ? error.message
                : undefined
            }
            maxLength={100}
            required
            disabled={busy}
            onChange={(e) => set("excel_lbl_nm", e.target.value)}
          />
          <label className="field">
            그룹 *
            <select
              name="trns_cd_grp_id"
              aria-invalid={!!fieldError("trns_cd_grp_id")}
              aria-describedby="map-group-error"
              required
              value={form.trns_cd_grp_id}
              disabled={busy}
              onChange={(e) =>
                setForm((s) => ({
                  ...s,
                  trns_cd_grp_id: e.target.value,
                  trns_cd: "",
                }))
              }
            >
              <option value="">선택</option>
              {groups.map((g) => (
                <option key={g.cd_grp_id} value={g.cd_grp_id}>
                  {g.cd_grp_nm}
                </option>
              ))}
            </select>
            <small id="map-group-error" className="danger">
              {fieldError("trns_cd_grp_id")}
            </small>
          </label>
          <label className="field">
            코드 *
            <select
              name="trns_cd"
              aria-invalid={!!fieldError("trns_cd")}
              aria-describedby="map-code-error"
              required
              value={form.trns_cd}
              disabled={
                busy || codesLoading || !form.trns_cd_grp_id || !!codeError
              }
              onChange={(e) => set("trns_cd", e.target.value)}
            >
              <option value="">선택</option>
              {options.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
            <small id="map-code-error" className="danger">
              {fieldError("trns_cd")}
            </small>
          </label>
          <label className="field">
            주/보조 *
            <select
              required
              name="map_role_cd"
              aria-invalid={!!fieldError("map_role_cd")}
              aria-describedby="map-role-error"
              value={form.map_role_cd}
              disabled={busy}
              onChange={(e) => set("map_role_cd", e.target.value)}
            >
              <option value="">선택</option>
              <option value="MAIN">주</option>
              <option value="SUB">보조</option>
            </select>
            <small id="map-role-error" className="danger">
              {fieldError("map_role_cd")}
            </small>
          </label>
          <label className="field fullWidth">
            <span>메모</span>
            <textarea
              name="rmrk_desc"
              aria-invalid={!!fieldError("rmrk_desc")}
              aria-describedby="map-memo-error"
              value={form.rmrk_desc}
              maxLength={200}
              rows={3}
              disabled={busy}
              onChange={(e) => set("rmrk_desc", e.target.value)}
            />
            <small>{form.rmrk_desc.length}/200</small>
            <small id="map-memo-error" className="danger">
              {fieldError("rmrk_desc")}
            </small>
          </label>
        </div>
        <div className="actions">
          <button type="button" disabled={busy} onClick={onClose}>
            취소
          </button>
          <Busy
            className="primary"
            busy={busy}
            disabled={codesLoading || !!codeError}
          >
            저장
          </Busy>
        </div>
      </form>
    </Modal>
  );
}
