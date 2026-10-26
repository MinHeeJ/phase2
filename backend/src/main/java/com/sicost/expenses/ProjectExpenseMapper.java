package com.sicost.expenses;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.expenses.ProjectExpenseModels.*;

@Mapper
public interface ProjectExpenseMapper {
    String projectUse(long id);
    List<ProjectExpense> list(long id);
    ProjectExpense row(ExpenseKey key);
    boolean activeCode(String code);
    String monthStatus(String month);
    long insert(ExpenseWrite write);
    int update(ExpenseWrite write);
    int delete(ExpenseKey key);
    void audit(ExpenseAudit audit);
}
