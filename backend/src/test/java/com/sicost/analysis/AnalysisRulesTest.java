package com.sicost.analysis;

import com.sicost.common.ai.*;
import com.sicost.common.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import static com.sicost.analysis.AnalysisModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisRulesTest {
    final ObjectMapper json=new ObjectMapper();
    final Clock clock=Clock.fixed(Instant.parse("2026-12-31T15:00:00Z"),ZoneId.of("Asia/Seoul"));
    @Test void futurePeriodsCrossYearAndHaveSixOrThreeMonths()throws Exception {
        var revenue=AnalysisInput.structured(json.readTree("{\"intent\":\"SALES_PIPELINE_FORECAST\",\"baseYm\":\"2026-11\"}"),clock);
        assertThat(AnalysisRouter.months(revenue.query())).containsExactly("2026-12","2027-01","2027-02","2027-03","2027-04","2027-05");
        var cash=AnalysisInput.structured(json.readTree("{\"intent\":\"CASH_FORECAST\",\"baseYm\":\"2026-12\"}"),clock);
        assertThat(AnalysisRouter.months(cash.query())).containsExactly("2027-01","2027-02","2027-03");
    }
    @Test void allIdListsTogetherAreLimitedToTwenty()throws Exception {
        var n=json.createObjectNode().put("intent","PROJECT_PROFIT_RANKING");var f=n.putObject("filters");var ids=f.putArray("projectIds");for(int i=1;i<=20;i++)ids.add(i);f.putArray("customerIds").add(99);
        assertThatThrownBy(()->AnalysisInput.structured(n,clock)).isInstanceOf(ApiException.class).hasMessageContaining("조건");
    }
    @Test void unsupportedScenarioAndFourAssumptionsAreRejected()throws Exception {
        var n=json.createObjectNode().put("intent","WHAT_IF_WORKFORCE");var a=n.putArray("workforceAssumptions");for(int i=1;i<=4;i++)a.addObject().put("type","EXTENSION").put("assignmentId",i).put("probability",80);
        assertThatThrownBy(()->AnalysisInput.structured(n,clock)).isInstanceOf(ApiException.class);
        a.removeAll();a.addObject().put("type","HEADCOUNT").put("probability",80);assertThatThrownBy(()->AnalysisInput.structured(n,clock)).isInstanceOf(ApiException.class);
    }
    @Test void zeroActualHasAbsoluteDifferenceAndNoInventedPercent(){var result=ForecastAnalysisTools.evaluate(new SavedForecast("2026-10","2026-11","REVENUE",new BigDecimal("120")),BigDecimal.ZERO,true);assertThat(result.absoluteDifference()).isEqualByComparingTo("120");assertThat(result.errorPercent()).isNull();assertThat(result.evaluationStatus()).isEqualTo("평가 완료");}
    @Test void unclosedActualIsNeverEvaluated(){var result=ForecastAnalysisTools.evaluate(new SavedForecast("2026-10","2026-11","OP_PROFIT",new BigDecimal("120")),new BigDecimal("50"),false);assertThat(result.actualValue()).isNull();assertThat(result.errorPercent()).isNull();assertThat(result.evaluationStatus()).isEqualTo("평가 대기");}
    @Test void forecastsDoNotRegenerateSavedValuesForEvaluation(){var saved=new SavedForecast("2026-10","2026-11","REVENUE",new BigDecimal("120"));var result=ForecastAnalysisTools.evaluate(saved,new BigDecimal("100"),true);assertThat(result.errorPercent()).isEqualByComparingTo("20.00");assertThat(saved.forecastValue()).isEqualByComparingTo("120");}
    @Test void modelDisabledAndPublicEndpointsAreUnavailableWithoutNetwork(){var env=new MockEnvironment().withProperty("AI_OLLAMA_ENABLED","true").withProperty("AI_OLLAMA_MODEL","explicit-model").withProperty("AI_OLLAMA_BASE_URL","https://example.com");try(var model=new AnalysisModelClient(env,json,clock)){assertThat(model.ready()).isFalse();assertThat(model.interpret("마감 상태",null,List.of()).status()).isEqualTo("MODEL_UNAVAILABLE");}}
    @Test void sourceMissingDoesNotBecomeZero(){assertThat(FinancialAnalysisTools.sum(Arrays.asList(BigDecimal.ZERO,null))).isNull();assertThat(FinancialAnalysisTools.sum(List.of(BigDecimal.ZERO))).isEqualByComparingTo("0");}
    @Test void sourceLinksUseExistingClosingAndBillingScreens(){
        assertThat(AnalysisRouter.screens(Intent.CLOSE_STATUS)).containsExactly("UI-14");
        assertThat(AnalysisRouter.screens(Intent.RECEIVABLES_RISK)).containsExactly("UI-13");
        assertThat(AnalysisRouter.screens(Intent.FORECAST_RECEIVABLES)).containsExactly("UI-13");
    }
    @Test void retentionOnlyClearsChatChildrenAndParentsInOrder(){var mapper=mock(ConversationMapper.class);new ConversationRetention(mapper,clock).sweep();var ordered=inOrder(mapper);var at=LocalDateTime.now(clock);ordered.verify(mapper).clearContext(at);ordered.verify(mapper).cleanupMessages(at);ordered.verify(mapper).cleanupConversations(at);verifyNoMoreInteractions(mapper);}
    @Test void explicitProductionCannotBeOverriddenByTestProfile(){var env=new MockEnvironment();env.setActiveProfiles("test","production");assertThatThrownBy(()->new AiAccessGuard(env).requireAction(AiActions.ASK)).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).status()).isEqualTo(403));}
}
