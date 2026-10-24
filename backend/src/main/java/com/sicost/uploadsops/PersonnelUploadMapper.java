package com.sicost.uploadsops;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.uploadsops.PersonnelUploadModels.*;
import static com.sicost.uploadsops.UploadModels.*;

@Mapper
public interface PersonnelUploadMapper {
    long nextUploadId();
    long nextDeveloperId();
    List<UploadCode> codes();
    List<UploadMapping> mappings();
    List<Map<String,Object>> partners(String label);
    List<Map<String,Object>> parts(String name);
    Map<String,Object> developer(String number);
    String allowance(String position);
    String monthStatus(String month);
    long salaryCount(String month);
    Map<String,Object> preview(Map<String,Object> input);
    void insertReview(ReviewWrite input);
    void insertDeveloper(DeveloperWrite input);
    void insertJob(ChildWrite input);
    void insertSkill(ChildWrite input);
    long insertStatus(DeveloperWrite input);
    void history(HistoryWrite input);
    void access(UploadAccess input);
    void deleteSalaries(String month);
    void deleteExpenses(String month);
    Map<String,Object> insertSalary(SalaryWrite input);
    void insertExpense(ExpenseWrite input);
    void difference(Map<String,Object> input);
}
