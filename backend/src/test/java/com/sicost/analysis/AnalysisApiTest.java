package com.sicost.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.ai.*;
import com.sicost.common.api.*;
import com.sicost.apiops.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static com.sicost.analysis.AnalysisModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=AnalysisController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,AnalysisRouter.class,ConversationService.class,
    AnalysisExportService.class,com.sicost.common.excel.ExcelExportService.class,AiAccessGuard.class,AiOwnerSession.class,
    AiSensitiveGuard.class,AiQuestionSanitizer.class,AnalysisApiTest.Time.class})
class AnalysisApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"),ZoneId.of("Asia/Seoul"));} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AiOwnerSession owners;
    @MockBean DataSource database;
    @MockBean ConversationMapper mapper;
    @MockBean FinancialAnalysisTools financial;
    @MockBean WorkforceAnalysisTools workforce;
    @MockBean ForecastAnalysisTools forecasts;
    @MockBean AiSourceReads reads;
    @MockBean AnalysisModelClient model;
    @MockBean ClosingQueryService closing;
    @MockBean ProjectBoardService board;
    @MockBean ProjectBoardMapper boardMapper;
    @MockBean PlatformTransactionManager transactions;
    Map<Long,ConversationRow> conversations;
    Map<Long,MessageRow> messages;
    long sequence;
    @BeforeEach void setup() {
        conversations=new HashMap<>();messages=new HashMap<>();sequence=0;
        when(transactions.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
        when(reads.required(any())).thenAnswer(i->new AiSourceReads.Result<>("OK",((java.util.function.Supplier<?>)i.getArgument(0)).get(),null));
        when(reads.optional(any())).thenAnswer(i->new AiSourceReads.Result<>("OK",((java.util.function.Supplier<?>)i.getArgument(0)).get(),null));
        when(mapper.create(any())).thenAnswer(i->{ConversationWrite w=i.getArgument(0);long id=++sequence;conversations.put(id,new ConversationRow(id,w.ownerHash(),w.now(),w.now(),null,w.retentionUntil(),null,null));return id;});
        when(mapper.conversation(anyLong())).thenAnswer(i->conversations.get(i.getArgument(0)));
        when(mapper.conversations(any())).thenAnswer(i->{ConversationQuery q=i.getArgument(0);return conversations.values().stream().filter(c->c.ownerHash().equals(q.ownerHash())&&c.hiddenAt()==null).sorted(Comparator.comparingLong(ConversationRow::id).reversed()).skip(q.offset()).limit(q.size()).toList();});
        when(mapper.count(any())).thenAnswer(i->{ConversationQuery q=i.getArgument(0);return conversations.values().stream().filter(c->c.ownerHash().equals(q.ownerHash())&&c.hiddenAt()==null).count();});
        when(mapper.hide(any())).thenAnswer(i->{MessageQuery q=i.getArgument(0);var c=conversations.get(q.id());conversations.put(c.id(),new ConversationRow(c.id(),c.ownerHash(),c.createdAt(),c.updatedAt(),c.updatedAt(),c.retentionUntil(),c.questionSummary(),c.answerSummary()));return 1;});
        when(mapper.messages(any())).thenAnswer(i->{MessageQuery q=i.getArgument(0);return messages.values().stream().filter(m->m.conversationId()==q.id()).sorted(Comparator.comparingLong(MessageRow::id)).skip(q.offset()).limit(q.size()).toList();});
        when(mapper.messageCount(anyLong())).thenAnswer(i->messages.values().stream().filter(m->m.conversationId()==(long)i.getArgument(0)).count());
        when(mapper.message(anyLong())).thenAnswer(i->messages.get(i.getArgument(0)));
        when(mapper.context(anyLong())).thenAnswer(i->messages.values().stream().filter(m->m.conversationId()==(long)i.getArgument(0)&&Set.of("OK","PARTIAL").contains(m.status())).sorted(Comparator.comparingLong(MessageRow::id).reversed()).limit(5).toList());
        when(mapper.updateTime(any())).thenReturn(1);
        when(mapper.append(any())).thenAnswer(i->{MessageWrite w=i.getArgument(0);long id=++sequence;messages.put(id,new MessageRow(id,w.conversationId(),w.question(),w.answer(),w.criteria()==null?null:w.criteria().intent().name(),w.criteriaJson(),w.criteria()==null?null:w.criteria().baseYm(),w.sourcesJson(),w.status(),w.now(),null,null));return id;});
        when(mapper.feedback(any())).thenAnswer(i->{FeedbackWrite w=i.getArgument(0);var m=messages.get(w.id());messages.put(m.id(),new MessageRow(m.id(),m.conversationId(),m.question(),m.answer(),m.intent(),m.criteria(),m.baseYm(),m.sources(),m.status(),m.createdAt(),w.rating(),w.reason()));return 1;});
        when(financial.projects(any(),any())).thenReturn(List.of(new ProfitRow(7,"검증 프로젝트","2026-11",new BigDecimal("100000000"),new BigDecimal("110000000"),new BigDecimal("-10000000"),new BigDecimal("-10.00"),"OPEN")));
        when(model.interpret(anyString(),any(),anyList())).thenAnswer(i->new AnalysisModelClient.Interpretation(AnalysisInput.structured(json.readTree("{\"intent\":\"PROJECT_PROFIT_RANKING\",\"sort\":\"PROFIT_ASC\",\"limit\":3}"),TimeHolder.CLOCK),"OK",true));
    }
    static class TimeHolder {static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"),ZoneId.of("Asia/Seoul"));}
    long create(MockHttpSession session)throws Exception{return json.readTree(mvc.perform(post("/api/ai/analysis/conversations").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();}
    long ask(MockHttpSession session,long id,String body)throws Exception {
        var result=mvc.perform(post("/api/ai/analysis/conversations/{id}/messages",id).session(session).contentType("application/json").content(body)).andExpect(request().asyncStarted()).andReturn();
        return json.readTree(mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("messageId").asLong();
    }
    @Test void capabilitiesHaveAllSeventeenAndDisabledModelTruthfully()throws Exception {mvc.perform(get("/api/ai/analysis/capabilities")).andExpect(status().isOk()).andExpect(jsonPath("$.data.intents.length()").value(17)).andExpect(jsonPath("$.data.modelStatus").value("연결불가")).andExpect(jsonPath("$.data.modelUsed").value(false)).andExpect(jsonPath("$.data.suggestedQuestions.length()").value(6));verifyNoInteractions(financial,workforce,forecasts);}
    @Test void createListDetailAndIdempotentHidePreserveRetainedRows()throws Exception {
        var s=new MockHttpSession();long id=create(s);create(s);
        mvc.perform(get("/api/ai/analysis/conversations").session(s)).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/ai/analysis/conversations/{id}",id).session(s)).andExpect(jsonPath("$.data.total").value(0));
        for(int i=0;i<2;i++)mvc.perform(delete("/api/ai/analysis/conversations/{id}",id).session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.data.visible").value(false));
        mvc.perform(get("/api/ai/analysis/conversations").session(s)).andExpect(jsonPath("$.data.total").value(1));assertThat(conversations).hasSize(2);
        mvc.perform(get("/api/ai/analysis/conversations/{id}",id).session(s)).andExpect(status().isNotFound());
    }
    @Test void foreignOwnershipRejectsEveryReferenceBeforeTools()throws Exception {
        var a=new MockHttpSession();long id=create(a);var b=new MockHttpSession();
        mvc.perform(get("/api/ai/analysis/conversations/{id}",id).session(b)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/ai/analysis/conversations/{id}",id).session(b)).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/analysis/conversations/{id}/messages",id).session(b).contentType("application/json").content("{\"question\":\"이번 달 적자\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/analysis/conversations/{id}/messages/1/feedback",id).session(b).contentType("application/json").content("{\"rating\":\"HELPFUL\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/analysis/conversations/{id}/messages/1/export",id).session(b)).andExpect(status().isForbidden());
        verifyNoInteractions(financial,workforce,forecasts,model);assertThat(conversations.get(id).hiddenAt()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"{\"intent\":\"SQL\"}","{\"intent\":\"MONTHLY_FINANCE\",\"sql\":\"select\"}","{\"intent\":\"MONTHLY_FINANCE\",\"limit\":0}","{\"intent\":\"MONTHLY_FINANCE\",\"limit\":21}","{\"intent\":\"PROJECT_PROFIT_TREND\",\"fromYm\":\"2025-01\",\"toYm\":\"2026-01\"}","{\"intent\":\"CLOSE_STATUS\",\"fromYm\":\"2023-01\",\"toYm\":\"2026-01\"}","{\"intent\":\"CLOSE_STATUS\",\"baseYm\":\"2026-13\"}"})
    void invalidStructuredInputNeverCallsSources(String body)throws Exception{mvc.perform(post("/api/ai/analysis/structured").contentType("application/json").content(body)).andExpect(status().isBadRequest());verifyNoInteractions(financial,workforce,forecasts,model);}
    @Test void rankingUsesSameMetricsRowsAndModelIsNeverCalledForStructured()throws Exception {mvc.perform(post("/api/ai/analysis/structured").contentType("application/json").content("{\"intent\":\"PROJECT_PROFIT_RANKING\",\"baseYm\":\"2026-11\",\"limit\":3,\"sort\":\"PROFIT_ASC\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.metrics.profit").value(-10000000)).andExpect(jsonPath("$.data.table[0].rows[0].profit").value(-10000000)).andExpect(jsonPath("$.data.modelUsed").value(false));verifyNoInteractions(model,mapper);}
    @Test void virtualAmountsAreCalculatedAndNotPersisted()throws Exception {
        var s=new MockHttpSession();long id=create(s);long message=ask(s,id,"{\"question\":\"가상 매출100000000 원가80000000 +10%\"}");
        assertThat(messages.get(message).criteria()).doesNotContain("80000000","100000000","virtualCostAssumption");assertThat(messages.get(message).question()).doesNotContain("80000000");
        mvc.perform(get("/api/ai/analysis/conversations/{id}/messages/{m}/export",id,message).session(s)).andExpect(status().isBadRequest());
        verifyNoInteractions(model,financial,workforce,forecasts);
    }
    @Test void messageSuccessStoresOnlySafeSummaryAndFeedbackIsLatestOne()throws Exception {
        var s=new MockHttpSession();long id=create(s);long m=ask(s,id,"{\"question\":\"이번 달 적자가 큰 프로젝트 3개\"}");
        for(String rating:List.of("HELPFUL","INACCURATE"))mvc.perform(post("/api/ai/analysis/conversations/{id}/messages/{m}/feedback",id,m).session(s).contentType("application/json").content("{\"rating\":\""+rating+"\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/ai/analysis/conversations/{id}",id).session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].feedback.rating").value("INACCURATE")).andExpect(jsonPath("$.data.items[0].rows").doesNotExist());assertThat(messages).hasSize(1);assertThat(messages.get(m).answer()).doesNotContain("110000000");
    }
    @Test void feedbackReasonLengthAndRatingAreValidated()throws Exception {
        var s=new MockHttpSession();long id=create(s),m=ask(s,id,"{\"question\":\"적자 순위\"}");
        mvc.perform(post("/api/ai/analysis/conversations/{id}/messages/{m}/feedback",id,m).session(s).contentType("application/json").content("{\"rating\":\"HELPFUL\",\"reason\":\""+"가".repeat(501)+"\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("reason"));
        mvc.perform(post("/api/ai/analysis/conversations/{id}/messages/{m}/feedback",id,m).session(s).contentType("application/json").content("{\"rating\":\"OTHER\"}")).andExpect(status().isBadRequest());
        assertThat(messages.get(m).rating()).isNull();
    }
    @Test void blankAndLongQuestionsHaveNoStorageSideEffects()throws Exception {
        var s=new MockHttpSession();long id=create(s);for(String question:List.of(" ","가".repeat(1001)))mvc.perform(post("/api/ai/analysis/conversations/{id}/messages",id).session(s).contentType("application/json").content(json.writeValueAsString(Map.of("question",question)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("question"));assertThat(messages).isEmpty();verifyNoInteractions(model);
    }
    @Test void sensitivePersonalQueryIs403WithoutModelOrWrites()throws Exception {var s=new MockHttpSession();long id=create(s);var r=mvc.perform(post("/api/ai/analysis/conversations/{id}/messages",id).session(s).contentType("application/json").content("{\"question\":\"실제 개인 급여 조회\"}")).andReturn();mvc.perform(asyncDispatch(r)).andExpect(status().isForbidden());verifyNoInteractions(model,financial);assertThat(messages).isEmpty();}
    @Test void independentVirtualWithBusinessReferenceIs403()throws Exception {mvc.perform(post("/api/ai/analysis/structured/export").contentType("application/json").content("{\"intent\":\"WHAT_IF_PROJECT_COST\",\"filters\":{\"projectIds\":[1]},\"virtualCostAssumption\":{\"revenue\":100000000,\"cost\":80000000,\"costChangePercent\":10}}")).andExpect(status().isForbidden());}
    @Test void structuredExportIsRealXlsxWithSameDecimalValues()throws Exception {
        var response=mvc.perform(post("/api/ai/analysis/structured/export").contentType("application/json").content("{\"intent\":\"WHAT_IF_PROJECT_COST\",\"virtualCostAssumption\":{\"revenue\":100000000,\"cost\":80000000,\"costChangePercent\":10}}")).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andExpect(header().string("Content-Disposition","attachment; filename=analysis.xlsx")).andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response))){assertThat(book.getSheetAt(0).getRow(4).getCell(3).getNumericCellValue()).isEqualTo(88000000);assertThat(book.getSheet("근거")).isNotNull();}verifyNoInteractions(model,mapper);
    }
    @ParameterizedTest @ValueSource(strings={"page=-1","size=7","role=admin"}) void invalidPageAndUnknownFieldsAre400(String pair)throws Exception {var p=pair.split("=");mvc.perform(get("/api/ai/analysis/conversations").param(p[0],p[1])).andExpect(status().isBadRequest());}
    @Test void unknownIdsAndNoBodyContract()throws Exception {mvc.perform(get("/api/ai/analysis/conversations/0")).andExpect(status().isBadRequest());mvc.perform(get("/api/ai/analysis/conversations/99")).andExpect(status().isNotFound());mvc.perform(post("/api/ai/analysis/conversations").contentType("application/json").content("{\"ownerId\":1}")).andExpect(status().isBadRequest());}
}
