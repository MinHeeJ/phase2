import type { BillingDetailLine } from "./client";
import tables from "../developers/Developers.module.css";

export function BillingLines({ lines }: { lines: BillingDetailLine[] }) {
  return (
    <div className={tables.tableScroll} data-testid="billing-lines">
      <table className={tables.table} aria-label="인력별 청구 상세">
        <thead>
          <tr>
            {[
              "인력 / TK 단계",
              "귀속월",
              "산출 MM",
              "단가",
              "근무일수",
              "정규시간",
              "초과근무금액",
              "공급가액",
            ].map((title) => (
              <th
                key={title}
                data-testid={`billing-lines-header-${["인력 / TK 단계", "귀속월", "산출 MM", "단가", "근무일수", "정규시간", "초과근무금액", "공급가액"].indexOf(title)}`}
              >
                {title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {lines.map((line, index) => (
            <tr
              key={`${line.asgn_id ?? "stage"}-${line.work_ym}-${index}`}
              data-testid={`billing-line-${line.asgn_id ?? "stage"}-${index}`}
            >
              <td>{line.display_name}</td>
              <td>{line.work_ym}</td>
              <td>{line.mm_qty ?? "—"}</td>
              <td className={tables.amount}>
                {line.untprc_amt?.toLocaleString() ?? "—"}
              </td>
              <td>{line.work_day_qty ?? "—"}</td>
              <td>{line.work_tm_qty ?? "—"}</td>
              <td className={tables.amount}>
                {line.ovtm_amt.toLocaleString()}
              </td>
              <td className={tables.amount}>
                {line.bill_amt.toLocaleString()}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
