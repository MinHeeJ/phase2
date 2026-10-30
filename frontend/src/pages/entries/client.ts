import { api, readData } from "../../shared/api";

export interface FinanceEntryInput {
  entry_ym: string;
  fin_se_cd: string;
  item_nm: string;
  entry_amt: number;
  vat_amt: number;
  inout_dt: string | null;
  rpt_yn: "Y" | "N";
  inout_cmpl_yn: "Y" | "N";
  remark: string | null;
  copy_entry_id: number | null;
  auto_fill_yn: "Y" | "N";
}
export interface FinanceEntry extends FinanceEntryInput {
  entry_id: number | null;
  already_copied: boolean;
}
export interface EntryList {
  items: FinanceEntry[];
  saved_count: number;
  total: number;
  drafts: FinanceEntry[];
  month_status: "OPEN" | "CLOSED";
  total_amount: number;
  total_vat: number;
  months: { month: string; status: string; saved_count: number }[];
}
export const listFinanceEntries = (month: string) =>
  readData<EntryList>(
    `/api/finance/entries?${new URLSearchParams({ year: String(Number(month.slice(0, 4))), month: String(Number(month.slice(5))) })}`,
  );
export function entryPayload(p: FinanceEntryInput): FinanceEntryInput {
  return {
    entry_ym: p.entry_ym,
    fin_se_cd: p.fin_se_cd,
    item_nm: p.item_nm,
    entry_amt: p.entry_amt,
    vat_amt: p.vat_amt,
    inout_dt: p.inout_dt || null,
    rpt_yn: p.rpt_yn,
    inout_cmpl_yn: p.inout_cmpl_yn,
    remark: p.remark || null,
    copy_entry_id: p.copy_entry_id,
    auto_fill_yn: p.auto_fill_yn,
  };
}
export const createFinanceEntry = async (input: FinanceEntryInput) =>
  (
    await api<FinanceEntry>("/api/finance/entries", {
      method: "POST",
      body: JSON.stringify(entryPayload(input)),
    })
  ).data!;
export const updateFinanceEntry = async (
  id: number,
  input: FinanceEntryInput,
) =>
  (
    await api<FinanceEntry>(`/api/finance/entries/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(entryPayload(input)),
    })
  ).data!;
export const deleteFinanceEntry = async (id: number) =>
  (
    await api(`/api/finance/entries/${encodeURIComponent(id)}`, {
      method: "DELETE",
    })
  ).data;
export const previewPreviousFinanceEntries = async (
  month: string,
  includeNonRecurring: boolean,
  entryIds?: number[],
) =>
  (
    await api<{ items: FinanceEntry[] }>(
      `/api/finance/entries/copy-previous?${new URLSearchParams({ month, includeNonRecurring: String(includeNonRecurring) })}`,
      {
        method: "POST",
        body: JSON.stringify(
          entryIds === undefined ? {} : { entry_ids: entryIds },
        ),
      },
    )
  ).data!;
