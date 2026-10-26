package com.sicost.expenses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import static com.sicost.expenses.ProjectExpenseModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=ProjectExpenseController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,ProjectExpenseService.class,CurrentUser.class,PermissionService.class})
class ProjectExpenseApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ProjectExpenseMapper mapper;
    final Map<Long,ProjectExpense> rows=new LinkedHashMap<>();
    final List<ExpenseAudit> audits=new ArrayList<>();
    @BeforeEach void setup() {
        rows.clear();audits.clear();rows.put(7L,row(7,"2026-10",100));
        when(mapper.projectUse(1)).thenReturn("Y");
        when(mapper.activeCode("TRIP")).thenReturn(true);
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        when(mapper.list(1)).thenAnswer(i->rows.values().stream()
            .sorted(Comparator.comparing(ProjectExpense::expns_ym).reversed()).toList());
        when(mapper.row(any())).thenAnswer(i->{var key=i.getArgument(0,ExpenseKey.class);
            return key.projectId()==1?rows.get(key.expenseId()):null;});
        when(mapper.insert(any())).thenAnswer(i->{var w=i.getArgument(0,ExpenseWrite.class);
            rows.put(8L,saved(8,w.input()));return 8L;});
        when(mapper.update(any())).thenAnswer(i->{var w=i.getArgument(0,ExpenseWrite.class);
            rows.put(w.expenseId(),saved(w.expenseId(),w.input()));return 1;});
        when(mapper.delete(any())).thenAnswer(i->{var k=i.getArgument(0,ExpenseKey.class);
            rows.remove(k.expenseId());return 1;});
        doAnswer(i->{audits.add(i.getArgument(0));return null;}).when(mapper).audit(any());
    }
    ProjectExpense row(long id,String month,long amount) {
        return new ProjectExpense(id,month,"TRIP",amount,amount/10,null,null);
    }
    ProjectExpense saved(long id,ExpenseInput input) {
        return new ProjectExpense(id,input.expns_ym(),input.expns_item_cd(),input.expns_amt(),
            input.vat_amt(),input.pay_dt(),input.rmrk_desc());
    }
    Map<String,Object> body() {
        return new LinkedHashMap<>(Map.of("expns_ym","2026-10","expns_item_cd","TRIP","expns_amt",300000));
    }
    ResultActions write(String method,Map<String,Object> input)throws Exception {
        var builder=switch(method) {
            case "POST" -> post("/api/projects/1/expenses");
            case "PUT" -> put("/api/projects/1/expenses/7");
            default -> delete("/api/projects/1/expenses/7");
        };
        if (!method.equals("DELETE")) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input));
        return mvc.perform(builder);
    }
    void untouched() {
        verify(mapper,never()).insert(any());verify(mapper,never()).update(any());
        verify(mapper,never()).delete(any());verify(mapper,never()).audit(any());
    }
    @Test void anonymousListIsFullArrayAndReadOnly()throws Exception {
        rows.put(6L,row(6,"2026-09",20));
        mvc.perform(get("/api/projects/1/expenses")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].expns_ym").value("2026-10"));untouched();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void anonymousSaveDefaultsVatAndReadsSameIdentity(String method)throws Exception {
        write(method,body()).andExpect(status().isOk()).andExpect(jsonPath("$.data.vat_amt").value(30000));
        long id=method.equals("POST")?8:7;
        mvc.perform(get("/api/projects/1/expenses")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.expns_id == "+id+")].expns_amt").value(300000));
    }
    @Test void anonymousDeleteReadsAbsence()throws Exception {
        mvc.perform(delete("/api/projects/1/expenses/7")).andExpect(status().isOk()).andExpect(jsonPath("$.data.prj_id").value(1))
            .andExpect(jsonPath("$.data.expns_id").value(7));
        mvc.perform(get("/api/projects/1/expenses")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        verify(mapper).delete(new ExpenseKey(1, 7));
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).type()).isEqualTo("DELETE");
        assertThat(audits.get(0).after()).isNull();
    }
    @Test void anonymousPostHappyHasOwnedExpenseAndAuditSideEffect() throws Exception {
        mvc.perform(post("/api/projects/1/expenses").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body()))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.expns_id").value(8))
            .andExpect(jsonPath("$.data.expns_amt").value(300000))
            .andExpect(jsonPath("$.data.vat_amt").value(30000));
        verify(mapper).insert(argThat(w -> w.projectId() == 1 && w.actor() == null));
        assertThat(audits).hasSize(1); assertThat(audits.get(0).type()).isEqualTo("INSERT");
        assertThat(rows.get(7L).expns_amt()).isEqualTo(100);
    }
    @Test void anonymousPutHappyHasOwnedExpenseAndAuditSideEffect() throws Exception {
        mvc.perform(put("/api/projects/1/expenses/7").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body()))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.expns_id").value(7))
            .andExpect(jsonPath("$.data.expns_amt").value(300000))
            .andExpect(jsonPath("$.data.vat_amt").value(30000));
        verify(mapper).update(argThat(w -> w.projectId() == 1 && w.expenseId() == 7 && w.actor() == null));
        assertThat(audits).hasSize(1); assertThat(audits.get(0).type()).isEqualTo("UPDATE");
    }
    @Test void postBusinessClosedMonthHasNoSideEffects() throws Exception {
        var input = body(); input.put("expns_ym", "2026-09");
        mvc.perform(post("/api/projects/1/expenses").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        untouched(); assertThat(rows.get(7L).expns_amt()).isEqualTo(100);
    }
    @Test void putBusinessClosedOriginalCannotMoveToOpenMonth() throws Exception {
        rows.put(7L, row(7, "2026-09", 100));
        mvc.perform(put("/api/projects/1/expenses/7").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body()))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        untouched(); assertThat(rows.get(7L).expns_ym()).isEqualTo("2026-09");
    }
    @Test void deleteBusinessClosedMonthPreservesExpenseAndAudit() throws Exception {
        rows.put(7L, row(7, "2026-09", 100));
        mvc.perform(delete("/api/projects/1/expenses/7")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        untouched(); assertThat(rows).containsKey(7L);
    }
    @Test void postValidationZeroAmountHasNoSideEffects() throws Exception {
        var input = body(); input.put("expns_amt", 0);
        mvc.perform(post("/api/projects/1/expenses").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("expns_amt")).andExpect(jsonPath("$.requestId").isNotEmpty());
        untouched();
    }
    @Test void putValidationZeroAmountHasNoSideEffects() throws Exception {
        var input = body(); input.put("expns_amt", 0);
        mvc.perform(put("/api/projects/1/expenses/7").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("expns_amt")).andExpect(jsonPath("$.requestId").isNotEmpty());
        untouched();
    }
    @Test void deleteValidationZeroExpenseIdHasNoSideEffects() throws Exception {
        mvc.perform(delete("/api/projects/1/expenses/0")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("expnsId")).andExpect(jsonPath("$.requestId").isNotEmpty());
        untouched();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","DELETE"})
    void eachSaveAppendsExactlyOneProjectSummaryWithNullableActor(String method)throws Exception {
        write(method,body()).andExpect(status().isOk());assertThat(audits).hasSize(1);
        var audit=audits.get(0);assertThat(audit.projectId()).isEqualTo("1");assertThat(audit.actor()).isNull();
        assertThat(audit.type()).isEqualTo(switch(method){case "POST"->"INSERT";case "PUT"->"UPDATE";default->"DELETE";});
        if (!method.equals("POST")) assertThat(json.readTree(audit.before()).get("expns_amt").asLong()).isEqualTo(100);
        if (!method.equals("DELETE")) assertThat(json.readTree(audit.after()).get("expns_amt").asLong()).isEqualTo(300000);
    }
    @ParameterizedTest @CsvSource({"POST,expns_amt,0","PUT,expns_amt,0","POST,expns_item_cd,blank","PUT,expns_item_cd,blank"})
    void requiredValuesRejectBeforeAnyWrite(String method,String field,String value)throws Exception {
        var input=body();input.put(field,field.equals("expns_amt")?0:" ");
        write(method,input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();
        assertThat(rows.get(7L).expns_amt()).isEqualTo(100);
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","DELETE"})
    void closedMonthPreservesRowAndAudit(String method)throws Exception {
        rows.put(7L,row(7,"2026-09",100));var input=body();input.put("expns_ym","2026-09");
        write(method,input).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"))
            .andExpect(jsonPath("$.message").value("2026-09은 마감된 달이라 입력할 수 없습니다."));untouched();
        mvc.perform(get("/api/projects/1/expenses")).andExpect(jsonPath("$.data[0].expns_amt").value(100));
    }
    @Test void cannotMoveClosedOriginalIntoOpenMonth()throws Exception {
        rows.put(7L,row(7,"2026-09",100));write("PUT",body()).andExpect(status().isConflict());untouched();
    }
    @Test void cannotMoveOpenOriginalIntoClosedMonth()throws Exception {
        var input=body();input.put("expns_ym","2026-09");write("PUT",input).andExpect(status().isConflict());untouched();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","DELETE"})
    void disabledProjectRejectsMutationWithoutHistory(String method)throws Exception {
        when(mapper.projectUse(1)).thenReturn("N");write(method,body()).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("use_yn"));untouched();
    }
    @Test void disabledProjectStillReads()throws Exception {
        when(mapper.projectUse(1)).thenReturn("N");mvc.perform(get("/api/projects/1/expenses")).andExpect(status().isOk());untouched();
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"})
    void nestedRowNotOwnedIs404(String method)throws Exception {
        rows.clear();write(method,body()).andExpect(status().isNotFound());untouched();
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"})
    void zeroExpenseIdIsFieldError(String method)throws Exception {
        var builder=method.equals("PUT")?put("/api/projects/1/expenses/0"):delete("/api/projects/1/expenses/0");
        mvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body())))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("expnsId"));untouched();
    }
    @Test void missingProjectIs404()throws Exception {
        mvc.perform(get("/api/projects/99/expenses")).andExpect(status().isNotFound());untouched();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void inactiveNewSelectionRejected(String method)throws Exception {
        var input=body();input.put("expns_item_cd","OLD");write(method,input).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("expns_item_cd"));untouched();
    }
    @Test void historicalUnchangedInactiveCodeCanBeRetained()throws Exception {
        when(mapper.activeCode("TRIP")).thenReturn(false);write("PUT",body()).andExpect(status().isOk());
    }
    @Test void manualVatAndTwoHundredCharactersArePreserved()throws Exception {
        var input=body();input.put("vat_amt",123);input.put("rmrk_desc","가".repeat(200));input.put("pay_dt","2026-10-31");
        write("POST",input).andExpect(status().isOk()).andExpect(jsonPath("$.data.vat_amt").value(123))
            .andExpect(jsonPath("$.data.pay_dt").value("2026-10-31"));
    }
    @ParameterizedTest @CsvSource({"expns_ym,2026-13","pay_dt,2026-02-30","expns_amt,9223372036854775808","vat_amt,9223372036854775808","expns_amt,1.5"})
    void malformedTypedValuesAre400(String field,String value)throws Exception {
        var input=json.valueToTree(body());((com.fasterxml.jackson.databind.node.ObjectNode)input).set(field,
            field.endsWith("amt")?json.readTree(value):json.valueToTree(value));
        mvc.perform(post("/api/projects/1/expenses").contentType(MediaType.APPLICATION_JSON).content(input.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();
    }
    @Test void excessiveRemarkIsRejected()throws Exception {
        var input=body();input.put("rmrk_desc","가".repeat(201));write("POST",input).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("rmrk_desc"));untouched();
    }
    @Test void rejectsPersonIdentityInjection()throws Exception {
        var input=body();input.put("dev_id",1);write("POST",input).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("dev_id"));untouched();
    }
    @Test void vatFloorsWithoutFloatingPoint()throws Exception {
        var input=body();input.put("expns_amt",300009);write("POST",input).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.vat_amt").value(30000));
    }
}
