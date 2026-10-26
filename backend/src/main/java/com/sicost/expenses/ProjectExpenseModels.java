package com.sicost.expenses;

import java.time.LocalDate;

public final class ProjectExpenseModels {
    private ProjectExpenseModels() {}
    public record ExpenseInput(String expns_ym, String expns_item_cd, long expns_amt,
            long vat_amt, LocalDate pay_dt, String rmrk_desc) {}
    public record ProjectExpense(long expns_id, String expns_ym, String expns_item_cd,
            long expns_amt, long vat_amt, LocalDate pay_dt, String rmrk_desc) {}
    public record ExpenseKey(long projectId, long expenseId) {}
    public record ExpenseWrite(long projectId, long expenseId, ExpenseInput input, Long actor) {}
    public record ExpenseAudit(String projectId, String type, String before, String after, Long actor) {}
    public record ExpenseRemoval(long prj_id, long expns_id) {}
}
