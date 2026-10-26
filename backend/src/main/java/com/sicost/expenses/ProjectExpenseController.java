package com.sicost.expenses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import static com.sicost.expenses.ProjectExpenseModels.*;

@RestController
public class ProjectExpenseController {
    private final ProjectExpenseService service;
    private final ObjectReader reader;
    public ProjectExpenseController(ProjectExpenseService service, ObjectMapper json) {
        this.service=service;
        this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private ExpenseInput input(String body) {
        try { return ProjectExpenseInput.parse(reader.readTree(body)); }
        catch (JsonProcessingException ex) { throw ApiException.badRequest("입력값을 확인하세요.",null); }
    }
    @GetMapping("/api/projects/{id}/expenses")
    public ApiResponse<List<ProjectExpense>> listProjectExpenses(@PathVariable long id) {
        return ApiResponse.ok(service.list(id));
    }
    @PostMapping("/api/projects/{id}/expenses")
    public ApiResponse<ProjectExpense> createProjectExpense(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.create(id,input(body)));
    }
    @PutMapping("/api/projects/{id}/expenses/{expnsId}")
    public ApiResponse<ProjectExpense> updateProjectExpense(@PathVariable long id,
            @PathVariable long expnsId, @RequestBody String body) {
        return ApiResponse.ok(service.update(id,expnsId,input(body)));
    }
    @DeleteMapping("/api/projects/{id}/expenses/{expnsId}")
    public ApiResponse<ExpenseRemoval> updateProjectExpenseRemoval(@PathVariable long id,
            @PathVariable long expnsId) {
        return ApiResponse.ok(service.delete(id,expnsId));
    }
}
