import type { ReactNode } from "react";
import type { Column } from "./permissions";
import styles from "./ui.module.css";
import { EmptyState, Skeleton } from "./ui";
import { usePreferences } from "./preferences";

export function DataTable<T>({
  rows,
  columns,
  rowKey,
  dense,
  total,
  empty,
  loading = false,
  error = false,
}: {
  rows: T[];
  columns: Column<T>[];
  rowKey: (row: T) => string;
  dense?: boolean;
  total: number;
  empty?: ReactNode;
  loading?: boolean;
  error?: boolean;
}) {
  const preferences = usePreferences();
  if (loading && rows.length === 0)
    return <Skeleton label="목록 불러오는 중" />;
  if (error && rows.length === 0) return null;
  return (
    <section className={styles.tableSection} aria-busy={loading}>
      <div className={styles.tableToolbar}>
        <div className={styles.total}>총 {total.toLocaleString("ko-KR")}건</div>
        <label className={styles.density}>
          <input
            type="checkbox"
            checked={preferences.dense}
            onChange={(event) =>
              preferences.update({ dense: event.target.checked })
            }
          />
          좁게 보기 <span>32px</span>
        </label>
      </div>
      <div className={styles.tableScroll}>
        <table
          className={`${styles.table} ${(dense ?? preferences.dense) ? styles.dense : ""}`}
        >
          <thead>
            <tr>
              {columns.map((column) => (
                <th key={column.key} className={styles[column.kind ?? "text"]}>
                  {column.title}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={rowKey(row)}>
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={styles[column.kind ?? "text"]}
                  >
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.length === 0 &&
        (empty ?? (
          <EmptyState reason="조건에 맞는 결과가 없습니다" nextAction={null} />
        ))}
    </section>
  );
}
export function Pagination({
  page,
  size,
  total,
  onPage,
  onSize,
}: {
  page: number;
  size: 20 | 50 | 100;
  total: number;
  onPage: (page: number) => void;
  onSize: (size: 20 | 50 | 100) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  const start = Math.floor(page / 10) * 10;
  return (
    <nav aria-label="목록 페이지" className={styles.pagination}>
      <span className={styles.pageRange}>
        {total === 0 ? 0 : page * size + 1}-{Math.min((page + 1) * size, total)}{" "}
        / {total.toLocaleString("ko-KR")}건
      </span>
      <button
        type="button"
        aria-label="첫 페이지"
        disabled={page === 0}
        onClick={() => onPage(0)}
      >
        «
      </button>
      <button
        type="button"
        disabled={page === 0}
        onClick={() => onPage(page - 1)}
      >
        이전
      </button>
      {Array.from(
        { length: Math.min(10, pages - start) },
        (_, index) => start + index,
      ).map((number) => (
        <button
          type="button"
          key={number}
          aria-current={page === number ? "page" : undefined}
          onClick={() => onPage(number)}
        >
          {number + 1}
        </button>
      ))}
      <button
        type="button"
        disabled={page >= pages - 1}
        onClick={() => onPage(page + 1)}
      >
        다음
      </button>
      <button
        type="button"
        aria-label="마지막 페이지"
        disabled={page >= pages - 1}
        onClick={() => onPage(pages - 1)}
      >
        »
      </button>
      <select
        aria-label="목록 건수"
        value={size}
        onChange={(event) => {
          onSize(Number(event.target.value) as 20 | 50 | 100);
          onPage(0);
        }}
      >
        {[20, 50, 100].map((count) => (
          <option key={count} value={count}>
            {count}건씩
          </option>
        ))}
      </select>
    </nav>
  );
}
