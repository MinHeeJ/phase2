import { useCallback, useEffect, useId, useRef, useState } from "react";
import {
  Button,
  DataTable,
  Skeleton,
  LockedBanner,
  ActionBar,
  SuccessToast,
  type Column,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { isDate } from "../../lib/format";
import {
  listManagedCodes,
  saveGroupCodes,
  type CodeGroup,
  type CodeRow,
} from "./client";
import { useAdminRead, useAdminWrite } from "./useAdminResource";
import { useAdminGuard } from "./AdminDialogs";
import { useSharedPreferences } from "../shared/preferences";
import styles from "./CommonCodes.module.css";

export function CodeTab({
  group,
  onDirtyChange,
}: {
  group: CodeGroup;
  onDirtyChange: (dirty: boolean) => void;
}) {
  const read = useCallback(
    () => listManagedCodes(group.cd_grp_id),
    [group.cd_grp_id],
  );
  const resource = useAdminRead(read),
    write = useAdminWrite(resource.reload);
  const [draft, setDraft] = useState<CodeRow[]>([]),
    [dirty, setDirty] = useState(false),
    [saved, setSaved] = useState(false),
    [issues, setIssues] = useState<Record<string, string>>({});
  const dense = useSharedPreferences((state) => state.dense),
    configure = useSharedPreferences((state) => state.configure);
  const guard = useAdminGuard(dirty),
    system = group.cd_grp_se_cd === "SYSTEM";
  const region = useRef<HTMLElement>(null);
  const errorPrefix = useId();
  useEffect(() => {
    if (write.error?.field && !write.pending)
      Array.from(
        region.current?.querySelectorAll<HTMLInputElement>("[name]") ?? [],
      )
        .find((input) => input.name === write.error?.field)
        ?.focus();
  }, [write.error, write.pending]);
  useEffect(() => {
    if (resource.data && !dirty) setDraft(resource.data.map((r) => ({ ...r })));
  }, [resource.data, dirty]);
  useEffect(() => {
    onDirtyChange(dirty);
  }, [dirty, onDirtyChange]);
  const change = (
    index: number,
    key: keyof CodeRow,
    value: string | number,
  ) => {
    write.clear();
    setIssues({});
    setDirty(true);
    setDraft((rows) =>
      rows.map((r, i) => (i === index ? { ...r, [key]: value } : r)),
    );
  };
  const validate = (r: CodeRow, key: keyof CodeRow) =>
    key === "dtl_cd"
      ? !r.dtl_cd
        ? "코드를 입력하세요."
        : !/^[A-Z0-9_]{1,30}$/.test(r.dtl_cd)
          ? "영문 대문자·숫자·_ 1~30자로 입력하세요."
          : ""
      : key === "cd_nm" && !r.cd_nm.trim()
        ? "이름을 입력하세요."
        : key === "aply_bgn_dt" && r.aply_bgn_dt && !isDate(r.aply_bgn_dt)
          ? "올바른 날짜를 입력하세요."
          : "";
  const input = (
    r: CodeRow,
    index: number,
    key: keyof CodeRow,
    label: string,
    readOnly = false,
    type = "text",
  ) => {
    const field = `[${index}].${key}`,
      error =
        issues[field] ||
        (write.error?.field === field ? write.error.message : null);
    return (
      <div data-testid={`code-field-${index}-${key.replace(/_/g, "-")}`}>
        <input
          data-testid={`code-input-${index}-${key.replace(/_/g, "-")}`}
          aria-label={`${label} ${index + 1}`}
          name={field}
          value={r[key] ?? ""}
          readOnly={readOnly}
          disabled={write.pending}
          type={type}
          maxLength={
            key === "dtl_cd"
              ? 30
              : key === "cd_nm" || key.startsWith("attr")
                ? 100
                : undefined
          }
          aria-invalid={!!error}
          aria-describedby={
            error ? `${errorPrefix}-${index}-${key}` : undefined
          }
          onBlur={() => {
            const e = validate(r, key);
            if (e) setIssues((v) => ({ ...v, [field]: e }));
          }}
          onChange={(e) =>
            change(
              index,
              key,
              key === "sort_seq"
                ? e.target.value === ""
                  ? ""
                  : Number(e.target.value)
                : e.target.value,
            )
          }
        />
        {error && (
          <span id={`${errorPrefix}-${index}-${key}`} role="alert">
            {error}
          </span>
        )}
      </div>
    );
  };
  const columns: Column<CodeRow>[] = [
    {
      key: "dtl_cd",
      label: "코드",
      kind: "code",
      render: (r) =>
        input(
          r,
          draft.indexOf(r),
          "dtl_cd",
          "코드",
          draft.indexOf(r) < (resource.data?.length ?? 0),
        ),
    },
    {
      key: "cd_nm",
      label: "이름",
      render: (r) => input(r, draft.indexOf(r), "cd_nm", "이름"),
    },
    {
      key: "sort_seq",
      label: "순서",
      kind: "number",
      render: (r) =>
        input(r, draft.indexOf(r), "sort_seq", "순서", false, "number"),
    },
    {
      key: "use_yn",
      label: "사용",
      kind: "status",
      render: (r) => (
        <Can perm="UI-27.delete">
          <input
            data-testid={`code-use-${draft.indexOf(r)}`}
            aria-label={`사용 ${draft.indexOf(r) + 1}`}
            type="checkbox"
            checked={r.use_yn === "Y"}
            disabled={system || write.pending}
            onChange={(e) =>
              change(draft.indexOf(r), "use_yn", e.target.checked ? "Y" : "N")
            }
          />
        </Can>
      ),
    },
    ...([1, 2, 3] as const)
      .filter((n) => group[`attr${n}_nm`])
      .map((n) => ({
        key: `attr${n}_val`,
        label: group[`attr${n}_nm`]!,
        render: (r: CodeRow) =>
          input(
            r,
            draft.indexOf(r),
            `attr${n}_val`,
            group[`attr${n}_nm`]!,
            system,
          ),
      })),
    {
      key: "aply_bgn_dt",
      label: "적용 시작일",
      kind: "date",
      render: (r) =>
        input(
          r,
          draft.indexOf(r),
          "aply_bgn_dt",
          "적용 시작일",
          system,
          "date",
        ),
    },
  ];
  const save = () => {
    const next: Record<string, string> = {};
    draft.forEach((r, i) =>
      (["dtl_cd", "cd_nm", "aply_bgn_dt"] as const).forEach((k) => {
        const e = validate(r, k);
        if (e) next[`[${i}].${k}`] = e;
      }),
    );
    setIssues(next);
    if (Object.keys(next).length) {
      queueMicrotask(() =>
        document.getElementsByName(Object.keys(next)[0])[0]?.focus(),
      );
      return;
    }
    void write.save(
      () =>
        saveGroupCodes(
          group.cd_grp_id,
          draft.map((r) => ({ ...r, aply_bgn_dt: r.aply_bgn_dt || null })),
        ),
      () => {
        setDirty(false);
        setSaved(true);
      },
    );
  };
  if (resource.loading && !resource.data) return <Skeleton />;
  return (
    <section ref={region} data-testid="code-tab" className={styles.region}>
      <h2>
        {group.cd_grp_nm} · {resource.data?.length ?? 0}건
      </h2>
      {system && (
        <LockedBanner>
          계산 규칙에 쓰는 시스템 코드입니다. 이름과 순서만 바꿀 수 있고
          추가·삭제는 할 수 없습니다.
        </LockedBanner>
      )}
      <ErrorBanner
        error={write.error ?? resource.error}
        pending={write.pending}
        onRetry={() => {
          void (write.error ? write.recover() : resource.reload()).catch(
            () => {},
          );
        }}
      />
      <DataTable
        columns={columns}
        showEmpty={!resource.loading && !resource.error}
        rows={draft}
        rowKey={(r) => String(draft.indexOf(r))}
        total={draft.length}
        dense={dense}
        onDenseChange={(v) => configure({ dense: v })}
      />
      {!system && (
        <Can perm="UI-27.create">
          <Button
            disabled={resource.loading || !!resource.error || write.pending}
            onClick={() => {
              setDirty(true);
              setDraft((rows) => [
                ...rows,
                {
                  dtl_cd: "",
                  cd_nm: "",
                  sort_seq: Math.max(0, ...rows.map((r) => r.sort_seq)) + 1,
                  use_yn: "Y",
                  attr1_val: null,
                  attr2_val: null,
                  attr3_val: null,
                  aply_bgn_dt: null,
                },
              ]);
            }}
          >
            코드 추가
          </Button>
        </Can>
      )}
      <ActionBar
        primary={
          <Can perm="UI-27.update">
            <Button
              variant="primary"
              pending={write.pending}
              disabled={resource.loading || !!resource.error || write.uncertain}
              onClick={save}
            >
              저장
            </Button>
          </Can>
        }
      >
        <Button
          disabled={write.pending}
          onClick={() =>
            guard.request(() => {
              setDirty(false);
              setIssues({});
              write.clear();
            })
          }
        >
          취소
        </Button>
      </ActionBar>
      {saved && (
        <SuccessToast
          message="공통코드를 저장했습니다."
          onDismiss={() => setSaved(false)}
        />
      )}{" "}
      {guard.confirmation}
    </section>
  );
}
