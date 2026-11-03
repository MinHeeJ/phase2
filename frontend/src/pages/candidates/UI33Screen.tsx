import { useCallback, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  Button,
  ConfirmDialog,
  Modal,
  Pagination,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { ScreenHeading } from "../shared/ScreenHeading";
import { listPartnerOptions } from "../partnersops/client";
import { listSalesPartOptions } from "../salespartsops/client";
import { emptyDeveloper, type DeveloperInput } from "../developers/client";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { getShortages } from "../workforce/client";
import { ShortageTable } from "../workforce/UI17Screen";
import {
  addContact,
  candidateDefaults,
  candidatePayload,
  convertCandidate,
  emptyCandidate,
  getCandidate,
  listCandidates,
  saveCandidate,
  type CandidateDetail,
  type CandidateFilters,
  type CandidateInput,
} from "./client";
import styles from "../developers/Developers.module.css";

const states = [
  { code: "NONE", name: "연락 전" },
  { code: "DONE", name: "연락함" },
  { code: "INTV", name: "면접 예정" },
  { code: "HOLD", name: "보류" },
  { code: "SIGNED", name: "계약 완료" },
];
const readPartners = () => listPartnerOptions("VEND");
const noop = () => {};

function CandidateForm({
  initial,
  onCancel,
  onSaved,
  onDirtyChange,
}: {
  initial?: CandidateDetail;
  onCancel: () => void;
  onSaved: (row: CandidateDetail) => Promise<void>;
  onDirtyChange: (dirty: boolean) => void;
}) {
  const [draft, setDraft] = useState<CandidateInput>(() =>
    candidatePayload(initial ?? emptyCandidate),
  );
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE"),
    skills = useCodes("SKILL"),
    sources = useCodes("CANDIDATE_SOURCE"),
    partners = useAdminRead(readPartners);
  const saved = useRef<CandidateDetail>();
  const write = useAdminWrite(async () => {
    if (saved.current) await onSaved(await getCandidate(saved.current.cand_id));
  });
  const dirty =
    JSON.stringify(candidatePayload(draft)) !==
    JSON.stringify(candidatePayload(initial ?? emptyCandidate));
  useEffect(() => {
    onDirtyChange(dirty);
    return () => onDirtyChange(false);
  }, [dirty, onDirtyChange]);
  const change = <K extends keyof CandidateInput>(
    key: K,
    value: CandidateInput[K],
  ) => {
    write.clear();
    setDraft((d) => ({
      ...d,
      [key]: value,
      ...(key === "cand_se_cd" ? { vndr_ptnr_id: null } : {}),
    }));
  };
  const disabled = write.pending || write.uncertain;
  const sourceError =
    jobs.error ??
    grades.error ??
    skills.error ??
    sources.error ??
    partners.error;
  return (
    <form
      className={styles.form}
      data-testid="candidate-editor"
      onSubmit={(e) => {
        e.preventDefault();
        void write.save(
          async () => {
            saved.current = await saveCandidate(draft, initial?.cand_id);
          },
          () => onDirtyChange(false),
        );
      }}
    >
      <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
      <ErrorBanner
        error={sourceError}
        onRetry={() => {
          jobs.retry();
          grades.retry();
          skills.retry();
          sources.retry();
          void partners.reload().catch(noop);
        }}
      />
      {write.error?.field && (
        <p role="alert">
          {write.error.field}: {write.error.message}
        </p>
      )}
      <fieldset disabled={disabled} className={styles.card}>
        <legend>{initial ? "후보 수정" : "후보 등록"}</legend>
        <div className={styles.fields}>
          <label>
            이름
            <input
              data-testid="candidate-name"
              required
              maxLength={50}
              value={draft.cand_nm}
              onChange={(e) => change("cand_nm", e.target.value)}
            />
          </label>
          <label>
            구분
            <select
              data-testid="candidate-type"
              value={draft.cand_se_cd}
              onChange={(e) =>
                change("cand_se_cd", e.target.value as "FL" | "OS")
              }
            >
              <option value="FL">프리랜서</option>
              <option value="OS">외주</option>
            </select>
          </label>
          {draft.cand_se_cd === "OS" && (
            <label>
              협력사
              <select
                data-testid="candidate-vendor"
                required
                value={draft.vndr_ptnr_id ?? ""}
                onChange={(e) =>
                  change(
                    "vndr_ptnr_id",
                    e.target.value ? Number(e.target.value) : null,
                  )
                }
              >
                <option value="">선택</option>
                {partners.data?.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </label>
          )}
          <label>
            직무
            <select
              data-testid="candidate-job"
              required
              value={draft.job_cd}
              onChange={(e) => change("job_cd", e.target.value)}
            >
              <option value="">선택</option>
              {jobs.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
              {draft.job_cd &&
                !jobs.codes.some((c) => c.code === draft.job_cd) && (
                  <option value={draft.job_cd}>{draft.job_cd}</option>
                )}
            </select>
          </label>
          <label>
            등급
            <select
              data-testid="candidate-grade"
              value={draft.grd_cd ?? ""}
              onChange={(e) => change("grd_cd", e.target.value || null)}
            >
              <option value="">미지정</option>
              {grades.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            희망 월 단가
            <input
              data-testid="candidate-price"
              type="number"
              min={0}
              step={1}
              required
              value={draft.hope_untprc_amt}
              onChange={(e) =>
                change("hope_untprc_amt", Number(e.target.value))
              }
            />
          </label>
          <label>
            가능일
            <input
              data-testid="candidate-available"
              type="date"
              required
              value={draft.avail_dt}
              onChange={(e) => change("avail_dt", e.target.value)}
            />
          </label>
          <label>
            연락 상태
            <select
              data-testid="candidate-status"
              disabled={!!initial?.contacts.length || !!initial?.dev_id}
              value={draft.cntct_stts_cd}
              onChange={(e) => change("cntct_stts_cd", e.target.value)}
            >
              {states.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            연락처
            <input
              data-testid="candidate-phone"
              maxLength={30}
              value={draft.tel_no ?? ""}
              onChange={(e) => change("tel_no", e.target.value || null)}
            />
          </label>
          {initial?.tel_no === "***" && (
            <p className={styles.help}>
              연락처는 마스킹됩니다. 변경하지 않으면 기존 값을 보존합니다.
            </p>
          )}
          <label>
            출처
            <select
              data-testid="candidate-source"
              value={draft.source ?? ""}
              onChange={(e) => change("source", e.target.value || null)}
            >
              <option value="">선택</option>
              {sources.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            메모
            <textarea
              data-testid="candidate-notes"
              value={draft.rmrk_desc ?? ""}
              onChange={(e) => change("rmrk_desc", e.target.value || null)}
            />
          </label>
        </div>
        <fieldset className={styles.multiple}>
          <legend>스킬</legend>
          {skills.codes.map((c) => (
            <label key={c.code}>
              <input
                data-testid={`candidate-skill-${c.code.toLowerCase()}`}
                type="checkbox"
                checked={draft.skill.includes(c.code)}
                onChange={(e) =>
                  change(
                    "skill",
                    e.target.checked
                      ? [...draft.skill, c.code]
                      : draft.skill.filter((v) => v !== c.code),
                  )
                }
              />
              {c.name}
            </label>
          ))}
        </fieldset>
      </fieldset>
      <div className={styles.actions}>
        <Button
          data-testid="candidate-cancel"
          type="button"
          disabled={write.pending}
          onClick={onCancel}
        >
          취소
        </Button>
        <Button
          data-testid="candidate-save"
          type="submit"
          variant="primary"
          pending={write.pending}
          disabled={write.uncertain || !!sourceError}
        >
          저장
        </Button>
      </div>
    </form>
  );
}

function ContactForm({
  row,
  reload,
  close,
}: {
  row: CandidateDetail;
  reload: () => Promise<unknown>;
  close: () => void;
}) {
  const today = new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Seoul",
  }).format(new Date());
  const [date, setDate] = useState(today),
    [state, setState] = useState("DONE"),
    [memo, setMemo] = useState("");
  const write = useAdminWrite(reload);
  return (
    <Modal title="연락 기록" onCancel={close}>
      <form
        data-testid="candidate-contact-form"
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          void write.save(
            () =>
              addContact(row.cand_id, {
                cntct_dt: date,
                cntct_stts_cd: state,
                memo,
              }),
            close,
          );
        }}
      >
        <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
        <label>
          연락일
          <input
            data-testid="candidate-contact-date"
            type="date"
            max={today}
            required
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
        </label>
        <label>
          연락 결과
          <select
            data-testid="candidate-contact-state"
            value={state}
            onChange={(e) => setState(e.target.value)}
          >
            {states.map((s) => (
              <option key={s.code} value={s.code}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          연락 메모
          <textarea
            data-testid="candidate-contact-memo"
            required
            maxLength={1000}
            value={memo}
            onChange={(e) => setMemo(e.target.value)}
          />
        </label>
        <p>
          실제 연락일이 가장 최근인 기록으로 현재 상태를 갱신합니다. 이전 기록은
          수정·삭제하지 않습니다.
        </p>
        <Button
          data-testid="candidate-contact-save"
          type="submit"
          pending={write.pending}
          disabled={write.uncertain}
        >
          기록 저장
        </Button>
      </form>
    </Modal>
  );
}

function ConvertForm({
  row,
  reload,
  close,
}: {
  row: CandidateDetail;
  reload: () => Promise<unknown>;
  close: () => void;
}) {
  const [draft, setDraft] = useState<DeveloperInput>({
    ...emptyDeveloper,
    dev_nm: row.cand_nm,
    emp_se_cd: row.cand_se_cd,
    main_job_cd: row.job_cd,
    grd_cd: row.grd_cd,
    vndr_ptnr_id: row.vndr_ptnr_id,
    skill: row.skill,
    dflt_untprc_amt: row.hope_untprc_amt,
  });
  const [created, setCreated] = useState<number>();
  const parts = useAdminRead(listSalesPartOptions),
    positions = useCodes("POSITION"),
    duties = useCodes("WORK_TYPE"),
    write = useAdminWrite(reload),
    navigate = useNavigate();
  return (
    <Modal title="개발자로 등록" onCancel={close}>
      <form
        data-testid="candidate-conversion-form"
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          void write.save(async () => {
            const result = await convertCandidate(row.cand_id, draft);
            setCreated(result.developer.dev_id);
          }, noop);
        }}
      >
        <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
        <ErrorBanner
          error={parts.error ?? positions.error ?? duties.error}
          onRetry={() => {
            void parts.reload().catch(noop);
            positions.retry();
            duties.retry();
          }}
        />
        <p>
          이름: {row.cand_nm} · 구분: {row.cand_se_cd} · 직무: {row.job_cd} ·
          등급: {row.grd_cd ?? "미지정"}
        </p>
        <p>
          후보의 소속·직무·등급·스킬을 사용합니다. 급여와 투입은 자동 생성하지
          않습니다.
        </p>
        <label>
          관리번호
          <input
            data-testid="candidate-conversion-number"
            maxLength={20}
            value={draft.mng_no ?? ""}
            onChange={(e) =>
              setDraft((d) => ({ ...d, mng_no: e.target.value || null }))
            }
            placeholder="비우면 자동 발급"
          />
        </label>
        <label>
          업무 구분
          <select
            data-testid="candidate-conversion-duty"
            value={draft.duty_se_cd}
            onChange={(e) =>
              setDraft((d) => ({ ...d, duty_se_cd: e.target.value }))
            }
          >
            {duties.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          직위
          <select
            data-testid="candidate-conversion-position"
            value={draft.pstn_cd ?? ""}
            onChange={(e) =>
              setDraft((d) => ({ ...d, pstn_cd: e.target.value || null }))
            }
          >
            <option value="">미지정</option>
            {positions.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          영업 파트
          <select
            data-testid="candidate-conversion-part"
            value={draft.bsn_part_id ?? ""}
            onChange={(e) =>
              setDraft((d) => ({
                ...d,
                bsn_part_id: e.target.value ? Number(e.target.value) : null,
              }))
            }
          >
            <option value="">미지정</option>
            {parts.data?.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          입사일
          <input
            data-testid="candidate-conversion-hire"
            type="date"
            value={draft.hire_dt ?? ""}
            onChange={(e) =>
              setDraft((d) => ({ ...d, hire_dt: e.target.value || null }))
            }
          />
        </label>
        {created ? (
          <Button
            data-testid="candidate-created-developer"
            type="button"
            onClick={() =>
              navigate("/UI-02", { state: { developerCreated: created } })
            }
          >
            등록한 개발자 보기
          </Button>
        ) : (
          <Button
            data-testid="candidate-convert-confirm"
            type="submit"
            pending={write.pending}
            disabled={write.uncertain}
          >
            개발자 등록 확인
          </Button>
        )}
      </form>
    </Modal>
  );
}

export default function UI33Screen({
  onDirtyChange = noop,
}: {
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const location = useLocation(),
    navigate = useNavigate(),
    readOnly = useDeveloperReadOnly();
  const state = location.state as {
    candidateFilters?: Partial<CandidateFilters>;
  } | null;
  const [filters, setFilters] = useState<CandidateFilters>(() => ({
      ...candidateDefaults,
      ...state?.candidateFilters,
    })),
    [draftFilters, setDraftFilters] = useState(filters);
  const [selected, setSelected] = useState<number | null>(null),
    [editor, setEditor] = useState<CandidateDetail | "new" | null>(null),
    [dirty, setDirty] = useState(false),
    [confirm, setConfirm] = useState(false),
    [message, setMessage] = useState("");
  const [action, setAction] = useState<"contact" | "convert" | null>(null),
    [tab, setTab] = useState("basic");
  const notify = useRef(onDirtyChange);
  notify.current = onDirtyChange;
  const reportDirty = useCallback((value: boolean) => {
    setDirty(value);
    notify.current(value);
  }, []);
  const list = useAdminRead(
    useCallback(() => listCandidates(filters), [filters]),
  );
  const shortages = useAdminRead(useCallback(() => getShortages(), []));
  const detail = useAdminRead(
    useCallback(
      () =>
        selected === null ? Promise.resolve(null) : getCandidate(selected),
      [selected],
    ),
  );
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE"),
    skills = useCodes("SKILL"),
    vendors = useAdminRead(readPartners);
  useEffect(() => {
    if (state?.candidateFilters) {
      const next = { ...candidateDefaults, ...state.candidateFilters };
      setFilters(next);
      setDraftFilters(next);
    }
  }, [location.key]);
  useEffect(() => () => notify.current(false), []);
  const reload = useCallback(async () => {
    await list.reload();
    if (selected !== null) await detail.reload();
  }, [list.reload, detail.reload, selected]);
  const leaveEditor = () => (dirty ? setConfirm(true) : setEditor(null));
  const sourceError =
    jobs.error ?? grades.error ?? skills.error ?? vendors.error;
  return (
    <section data-testid="candidate-workspace" data-screen-id="SCR-UI-33">
      <ScreenHeading
        screenId="UI-33"
        name="외부 인력 후보"
        groupName="인력 운영"
        actions={
          <>
            <Button
              data-testid="candidate-project-workspace"
              disabled={dirty}
              onClick={() =>
                navigate("/UI-33", { state: { workspace: "projects" } })
              }
            >
              프로젝트 등록
            </Button>
            {!readOnly && (
              <Button
                data-testid="candidate-create"
                disabled={editor !== null}
                onClick={() => setEditor("new")}
              >
                + 후보 등록
              </Button>
            )}
          </>
        }
      />
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      {editor !== null && !readOnly ? (
        <CandidateForm
          key={editor === "new" ? "new" : editor.cand_id}
          initial={editor === "new" ? undefined : editor}
          onCancel={leaveEditor}
          onDirtyChange={reportDirty}
          onSaved={async (row) => {
            await list.reload();
            if (selected === row.cand_id) await detail.reload();
            reportDirty(false);
            setEditor(null);
            setSelected(row.cand_id);
            setMessage("저장했습니다.");
          }}
        />
      ) : (
        <>
          <ErrorBanner
            error={sourceError}
            onRetry={() => {
              jobs.retry();
              grades.retry();
              skills.retry();
              void vendors.reload().catch(noop);
            }}
          />
          <ErrorBanner
            error={shortages.error}
            onRetry={() => void shortages.reload().catch(noop)}
          />
          {shortages.loading ? (
            <Skeleton label="부족 직무 확인 중" />
          ) : (
            !shortages.error &&
            shortages.data && (
              <section data-testid="candidate-shortage-shortcuts">
                <h2>부족 직무와 맞는 후보</h2>
                <ShortageTable rows={shortages.data.items} />
              </section>
            )
          )}
          <form
            className={styles.search}
            data-testid="candidate-search"
            onSubmit={(e) => {
              e.preventDefault();
              setFilters({ ...draftFilters, page: 0 });
            }}
          >
            <label>
              이름·후보번호
              <input
                data-testid="candidate-query"
                value={draftFilters.q}
                onChange={(e) =>
                  setDraftFilters((d) => ({ ...d, q: e.target.value }))
                }
              />
            </label>
            {(
              [
                ["job", "조회 직무", jobs.codes],
                ["grade", "조회 등급", grades.codes],
                ["skill", "조회 스킬", skills.codes],
                ["cntct_stts_cd", "조회 상태", states],
                [
                  "cand_se_cd",
                  "조회 구분",
                  [
                    { code: "FL", name: "프리랜서" },
                    { code: "OS", name: "외주" },
                  ],
                ],
              ] as const
            ).map(([key, label, options]) => (
              <label key={key}>
                {label}
                <select
                  data-testid={`candidate-filter-${key.replaceAll("_", "-")}`}
                  value={draftFilters[key]}
                  onChange={(e) =>
                    setDraftFilters((d) => ({ ...d, [key]: e.target.value }))
                  }
                >
                  <option value="">전체</option>
                  {options.map((c) => (
                    <option key={c.code} value={c.code}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
            ))}
            <label>
              조회 협력사
              <select
                data-testid="candidate-filter-vendor"
                value={draftFilters.vndr_ptnr_id}
                onChange={(e) =>
                  setDraftFilters((d) => ({
                    ...d,
                    vndr_ptnr_id: e.target.value,
                  }))
                }
              >
                <option value="">전체</option>
                {vendors.data?.map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              가능일 이전
              <input
                data-testid="candidate-filter-available"
                type="date"
                value={draftFilters.avail_dt}
                onChange={(e) =>
                  setDraftFilters((d) => ({ ...d, avail_dt: e.target.value }))
                }
              />
            </label>
            <label>
              <input
                data-testid="candidate-filter-uncontacted"
                type="checkbox"
                checked={draftFilters.uncontacted_only}
                onChange={(e) =>
                  setDraftFilters((d) => ({
                    ...d,
                    uncontacted_only: e.target.checked,
                  }))
                }
              />
              30일 초과 미연락
            </label>
            <Button data-testid="candidate-search-submit" type="submit">
              조회
            </Button>
            <Button
              data-testid="candidate-search-reset"
              type="button"
              onClick={() => {
                setDraftFilters(candidateDefaults);
                setFilters(candidateDefaults);
              }}
            >
              조건 초기화
            </Button>
          </form>
          <ErrorBanner
            error={list.error}
            onRetry={() => void list.reload().catch(noop)}
          />
          {list.loading ? (
            <Skeleton label="후보 불러오는 중" />
          ) : (
            !list.error &&
            list.data && (
              <>
                <div
                  className={styles.search}
                  data-testid="candidate-kpi"
                  aria-label="후보 지표"
                >
                  <span>후보 수 {list.data.kpi.candidate_count}명</span>
                  <span>면접 예정 {list.data.kpi.interview_count}명</span>
                  <span>
                    다음 달까지 가용{" "}
                    {list.data.kpi.available_by_next_month_count}명
                  </span>
                  <span>
                    30일 초과 미연락{" "}
                    {list.data.kpi.uncontacted_over_30_days_count}명
                  </span>
                </div>
                <div className={styles.tableScroll}>
                  <table className={styles.table}>
                    <thead>
                      <tr>
                        {[
                          ["cand_no", "후보번호"],
                          ["cand_nm", "이름"],
                          ["cand_se_cd", "구분"],
                          ["job_cd", "직무"],
                          ["grd_cd", "등급"],
                          ["hope_untprc_amt", "희망 단가"],
                          ["avail_dt", "가능일"],
                          ["cntct_stts_cd", "연락 상태"],
                          ["last_cntct_dt", "최근 연락"],
                        ].map(([key, label]) => (
                          <th key={key}>
                            {[
                              "cand_no",
                              "cand_nm",
                              "hope_untprc_amt",
                              "avail_dt",
                              "last_cntct_dt",
                            ].includes(key) ? (
                              <button
                                data-testid={`candidate-sort-${key.replaceAll("_", "-")}`}
                                onClick={() =>
                                  setFilters((f) => ({
                                    ...f,
                                    sort: `${key},${f.sort === `${key},asc` ? "desc" : "asc"}`,
                                    page: 0,
                                  }))
                                }
                              >
                                {label}
                              </button>
                            ) : (
                              label
                            )}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {list.data.items.map((row) => (
                        <tr
                          key={row.cand_id}
                          data-testid={`candidate-row-${row.cand_id}`}
                        >
                          <td>{row.cand_no}</td>
                          <td>
                            <button
                              data-testid={`candidate-open-${row.cand_id}`}
                              className={styles.textButton}
                              onClick={() => {
                                setSelected(row.cand_id);
                                setTab("basic");
                              }}
                            >
                              {row.cand_nm}
                            </button>
                          </td>
                          <td>{row.cand_se_cd}</td>
                          <td>{row.job_cd}</td>
                          <td>{row.grd_cd ?? "–"}</td>
                          <td className={styles.amount}>
                            {row.hope_untprc_amt.toLocaleString("ko-KR")}
                          </td>
                          <td>{row.avail_dt}</td>
                          <td>
                            {states.find((s) => s.code === row.cntct_stts_cd)
                              ?.name ?? row.cntct_stts_cd}
                          </td>
                          <td>{row.last_cntct_dt ?? "–"}</td>
                        </tr>
                      ))}
                      {list.data.items.length === 0 && (
                        <tr data-testid="candidate-empty-row">
                          <td colSpan={9} className={styles.empty}>
                            조건에 맞는 결과가 없습니다.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
                <Pagination
                  page={filters.page + 1}
                  size={filters.size}
                  total={list.data.total}
                  onPage={(page) =>
                    setFilters((f) => ({ ...f, page: page - 1 }))
                  }
                  onSize={(size) =>
                    setFilters((f) => ({ ...f, size, page: 0 }))
                  }
                />
              </>
            )
          )}
        </>
      )}
      {selected !== null && editor === null && (
        <div className={styles.scrim}>
          <aside
            className={styles.drawer}
            role="dialog"
            aria-modal="true"
            aria-label="후보 상세"
            data-testid="candidate-detail"
          >
            <div className={styles.drawerHeader}>
              <h2>후보 상세</h2>
              <Button
                data-testid="candidate-detail-close"
                onClick={() => {
                  setSelected(null);
                  setAction(null);
                }}
              >
                닫기
              </Button>
            </div>
            <ErrorBanner
              error={detail.error}
              onRetry={() => void detail.reload().catch(noop)}
            />
            {detail.loading ? (
              <Skeleton />
            ) : (
              !detail.error &&
              detail.data && (
                <>
                  <h3>
                    {detail.data.cand_no} {detail.data.cand_nm}
                  </h3>
                  <div role="tablist" aria-label="후보 상세 영역">
                    {[
                      ["basic", "기본 정보"],
                      ["slots", "맞는 자리"],
                      ["contacts", "연락 기록"],
                    ].map(([key, label]) => (
                      <Button
                        key={key}
                        role="tab"
                        data-testid={`candidate-tab-${key}`}
                        aria-selected={tab === key}
                        onClick={() => setTab(key)}
                      >
                        {label}
                      </Button>
                    ))}
                  </div>
                  <div role="tabpanel" data-testid={`candidate-panel-${tab}`}>
                    {tab === "basic" ? (
                      <dl className={styles.details}>
                        <dt>직무 / 등급</dt>
                        <dd>
                          {detail.data.job_cd} /{" "}
                          {detail.data.grd_cd ?? "미지정"}
                        </dd>
                        <dt>희망 단가</dt>
                        <dd>
                          {detail.data.hope_untprc_amt.toLocaleString("ko-KR")}
                          원
                        </dd>
                        <dt>가능일</dt>
                        <dd>{detail.data.avail_dt}</dd>
                        <dt>연락처</dt>
                        <dd>{detail.data.tel_no ?? "–"}</dd>
                        <dt>메모</dt>
                        <dd>{detail.data.rmrk_desc ?? "–"}</dd>
                      </dl>
                    ) : tab === "slots" ? (
                      <div className={styles.tableScroll}>
                        <table className={styles.table}>
                          <thead>
                            <tr>
                              <th>기회</th>
                              <th>시작월</th>
                              <th>월 단가</th>
                              <th>예상 이익률</th>
                            </tr>
                          </thead>
                          <tbody>
                            {detail.data.matching_slots.map((slot) => (
                              <tr
                                data-testid={`candidate-slot-${slot.need_id}`}
                                key={slot.need_id}
                              >
                                <td>{slot.oppt_nm}</td>
                                <td>{slot.bgn_ym}</td>
                                <td>
                                  {slot.monthly_price.toLocaleString("ko-KR")}
                                </td>
                                <td>
                                  {slot.margin_rate === null
                                    ? "–"
                                    : `${slot.margin_rate}%`}{" "}
                                  · 추정 {slot.warnings.join(" · ")}
                                </td>
                              </tr>
                            ))}
                            {detail.data.matching_slots.length === 0 && (
                              <tr>
                                <td colSpan={4}>맞는 자리가 없습니다.</td>
                              </tr>
                            )}
                          </tbody>
                        </table>
                      </div>
                    ) : (
                      <div className={styles.tableScroll}>
                        <table className={styles.table}>
                          <thead>
                            <tr>
                              <th>연락일</th>
                              <th>상태</th>
                              <th>메모</th>
                              <th>기록 시각</th>
                            </tr>
                          </thead>
                          <tbody>
                            {detail.data.contacts.map((c, index) => (
                              <tr
                                key={`${c.created_at}-${index}`}
                                data-testid={`candidate-contact-row-${index}`}
                              >
                                <td>{c.cntct_dt}</td>
                                <td>{c.cntct_stts_cd}</td>
                                <td>{c.memo}</td>
                                <td>{c.created_at}</td>
                              </tr>
                            ))}
                            {detail.data.contacts.length === 0 && (
                              <tr>
                                <td colSpan={4}>연락 기록이 없습니다.</td>
                              </tr>
                            )}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </div>
                  {!readOnly && (
                    <div className={styles.actions}>
                      <Button
                        data-testid="candidate-edit"
                        onClick={() => setEditor(detail.data)}
                      >
                        수정
                      </Button>
                      <Button
                        data-testid="candidate-contact"
                        onClick={() => setAction("contact")}
                      >
                        연락 기록
                      </Button>
                      {detail.data.dev_id === null && (
                        <Button
                          data-testid="candidate-convert"
                          onClick={() => setAction("convert")}
                        >
                          개발자로 등록
                        </Button>
                      )}
                    </div>
                  )}
                  {detail.data.dev_id !== null && (
                    <Button
                      data-testid="candidate-linked-developer"
                      onClick={() =>
                        navigate("/UI-02", {
                          state: { developerCreated: detail.data!.dev_id },
                        })
                      }
                    >
                      연결된 개발자 보기
                    </Button>
                  )}
                  {action === "contact" && (
                    <ContactForm
                      row={detail.data}
                      reload={reload}
                      close={() => setAction(null)}
                    />
                  )}
                  {action === "convert" && (
                    <ConvertForm
                      row={detail.data}
                      reload={reload}
                      close={() => setAction(null)}
                    />
                  )}
                </>
              )
            )}
          </aside>
        </div>
      )}
      {confirm && (
        <ConfirmDialog
          title="저장하지 않은 내용"
          onCancel={() => setConfirm(false)}
          onConfirm={() => {
            reportDirty(false);
            setEditor(null);
            setConfirm(false);
          }}
        >
          입력한 내용을 버릴까요?
        </ConfirmDialog>
      )}
    </section>
  );
}
