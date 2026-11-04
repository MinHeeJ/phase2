package com.sicost.staffrecommendations;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sicost.aiops.RecommendationOptionsService;
import com.sicost.aiops.RecommendationOptionsModels.*;
import com.sicost.common.ai.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.staffrecommendations.StaffRecommendationModels.*;

@WebMvcTest(controllers=StaffRecommendationController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,RecommendationErrors.class,StaffRecommendationService.class,
    RecommendationBoundary.class,AiOwnerSession.class,RecommendationCriteriaService.class,AiQuestionSanitizer.class,
    CareerCardService.class,ExcelExportService.class,StaffRecommendationApiTest.Time.class})
class StaffRecommendationApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean StaffRecommendationMapper mapper;
    @MockBean RecommendationOptionsService options;
    @MockBean AiAccessGuard access;
    @MockBean AiSensitiveGuard sensitive;
    @MockBean AiLocalIntentParser parser;
    Map<Long,Stored> requests;
    Map<String,ResultState> results;
    MockHttpSession session;
    ObjectNode person;
    static final String BASE="/api/ai/staff-recommendations";
    @BeforeEach void setup() throws Exception {
        requests=new LinkedHashMap<>();results=new LinkedHashMap<>();session=new MockHttpSession();AtomicLong ids=new AtomicLong();
        person=(ObjectNode)json.readTree("""
            {"job":"BE","grade":"SR","enabled":true,"resign":null,"jobs":["FE"],"skills":["JAVA","SPRING"],"leaves":[],"assignments":[],"temporary":[]}
            """);
        when(mapper.people()).thenAnswer(i->List.of(new Person(1,person.toString())));
        when(mapper.codes("JOB")).thenReturn(List.of("BE","FE"));when(mapper.codes("GRADE")).thenReturn(List.of("JR","MD","SR"));
        when(mapper.codes("SKILL")).thenReturn(List.of("JAVA","SPRING"));when(mapper.grades()).thenReturn(List.of("JR","MD","SR"));
        when(mapper.fingerprint()).thenReturn("source-before");when(mapper.career(anyLong())).thenReturn("[]");
        when(mapper.nextRequestId()).thenAnswer(i->ids.incrementAndGet());when(mapper.nextResultId(anyString())).thenAnswer(i->ids.incrementAndGet());
        doAnswer(i->{RequestWrite r=i.getArgument(0);requests.put(r.id(),new Stored(r.id(),r.owner(),r.document(),r.at()));return null;}).when(mapper).insertRequest(any());
        doAnswer(i->{save(i.getArgument(0),A);return null;}).when(mapper).insertA(any());
        doAnswer(i->{save(i.getArgument(0),B);return null;}).when(mapper).insertB(any());
        when(mapper.request(anyLong())).thenAnswer(i->requests.get(i.getArgument(0)));
        when(mapper.states(anyLong(),anyString())).thenAnswer(i->results.entrySet().stream().filter(e->e.getKey().startsWith(i.getArgument(1)+":"))
            .map(Map.Entry::getValue).filter(r->r.requestId()==(long)i.getArgument(0)).toList());
        when(mapper.result(anyLong(),anyString())).thenAnswer(i->results.get(i.getArgument(1)+":"+i.getArgument(0)));
        when(mapper.history(any())).thenAnswer(i->{HistoryQuery q=i.getArgument(0);return requests.values().stream().filter(r->r.owner().equals(q.owner()))
            .filter(r->q.direction()==null||r.document().contains("\"direction\":\""+q.direction()+"\""))
            .sorted(Comparator.comparingLong(Stored::id).reversed()).toList();});
        when(mapper.transition(any())).thenAnswer(i->{Transition t=i.getArgument(0);String key=t.direction()+":"+t.id();var r=results.get(key);
            if(!r.decision().equals("PENDING"))return 0;results.put(key,new ResultState(r.id(),r.requestId(),r.devId(),t.decision(),t.reason(),null,null));return 1;});
        when(parser.parseCriteria(any())).thenReturn(new AiLocalIntentParser.Parsed("MODEL_UNAVAILABLE",json.createObjectNode(),false));
    }
    private void save(ResultWrite row,String direction){results.put(direction+":"+row.id(),new ResultState(row.id(),row.requestId(),row.devId(),"PENDING",null,null,null));}
    ObjectNode input() {return json.createObjectNode().put("direction",A).set("criteria",json.createObjectNode().put("jobCode","BE").put("startYm","2027-01").put("durationMonths",6).put("headcount",3).put("requestedMm",1).set("skillCodes",json.valueToTree(List.of("JAVA","SPRING"))));}
    JsonNode run(ObjectNode body) throws Exception {return json.readTree(mvc.perform(post(BASE+"/search").session(session).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    @Test void deterministicNewRunsAndImmutableDetail() throws Exception {
        var first=run(input());var second=run(input());assertThat(first.path("requestId")).isNotEqualTo(second.path("requestId"));
        assertThat(first.path("results").get(0).path("score")).isEqualTo(second.path("results").get(0).path("score"));assertThat(requests).hasSize(2);
        person.put("grade","JR");mvc.perform(get(BASE+"/{id}",first.path("requestId").asLong()).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.criteria.startYm").value("2027-01")).andExpect(jsonPath("$.data.results[0].score").value(first.path("results").get(0).path("score").decimalValue()));
    }
    @Test void anonymousOwnerIsolatedAndNoLoginRequired() throws Exception {
        var run=run(input());mvc.perform(get(BASE+"/{id}",run.path("requestId").longValue()).session(new MockHttpSession())).andExpect(status().isForbidden());
        mvc.perform(get(BASE+"/history").session(new MockHttpSession())).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }
    @Test void productionDeniesBeforeReadsOrWrites() throws Exception {
        doThrow(AiAccessGuard.forbidden()).when(access).requireAction(anyString());
        mvc.perform(post(BASE+"/search").session(session).contentType("application/json").content(input().toString())).andExpect(status().isForbidden());verifyNoInteractions(mapper);
    }
    // 이 화면은 급여를 이미 아는 사람만 메뉴 권한(⑯)으로 들어온다. 실제 원가 자료가 있어도 추천은 거부하지 않고 비용 점수에 쓴다.
    @Test void personalCostIsUsedForScoringNotDenied() throws Exception {
        when(mapper.hasCostEvidence(1)).thenReturn(true);
        mvc.perform(post(BASE+"/search").session(session).contentType("application/json").content(input().toString())).andExpect(status().isOk());
        verify(mapper).syntheticCost(eq(1L),anyString());verify(sensitive,never()).requireSyntheticDatabase();
    }
    @ParameterizedTest @ValueSource(strings={"durationMonths:0","durationMonths:37","headcount:0","headcount:101","requestedMm:0","requestedMm:1.01","requestedMm:0.001"})
    void rangesRejectWithoutWrites(String value) throws Exception {
        var body=input();String[] pair=value.split(":");((ObjectNode)body.get("criteria")).set(pair[0],json.readTree(pair[1]));
        mvc.perform(post(BASE+"/search").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(pair[0]));assertThat(requests).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"jobCode","startYm"}) void mandatoryCriteria(String key) throws Exception {
        var body=input();((ObjectNode)body.get("criteria")).remove(key);mvc.perform(post(BASE+"/search").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());assertThat(requests).isEmpty();
    }
    @Test void optionalManualDefaultsAreDomainDefaults() throws Exception {
        var body=input();var c=(ObjectNode)body.get("criteria");c.remove(List.of("durationMonths","headcount","requestedMm"));var data=run(body);
        assertThat(data.path("criteria").path("durationMonths").asInt()).isEqualTo(1);assertThat(data.path("criteria").path("requestedMm").decimalValue()).isEqualByComparingTo("1");
    }
    @Test void missingSource404() throws Exception {
        when(options.resolveCurrentSource(any(),eq(99L))).thenThrow(ApiException.notFound("원천 없음"));
        mvc.perform(post(BASE+"/search").contentType("application/json").content(input().put("sourceId",99).toString())).andExpect(status().isNotFound());assertThat(requests).isEmpty();
    }
    @Test void nextUnconfirmedNeedsAssumption() throws Exception {
        mvc.perform(post(BASE+"/search").contentType("application/json").content("{\"direction\":\"PERSON_TO_POSITION\",\"devId\":1}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("assumedExitYm"));
    }
    @Test void decisionOnlyNoAssignmentAndRepeatConflict() throws Exception {
        var data=run(input());long result=data.path("results").get(0).path("resultId").longValue();
        mvc.perform(patch(BASE+"/results/{id}/decision",result).session(session).contentType("application/json").content("{\"direction\":\"PROJECT_TO_PERSON\",\"decision\":\"ADOPT\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.assignmentCreated").value(false));
        mvc.perform(get(BASE+"/{id}",data.path("requestId").longValue()).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.results[0].assignmentLinkStatus").value("채택됨·배정 미생성"));
        mvc.perform(patch(BASE+"/results/{id}/decision",result).session(session).contentType("application/json").content("{\"direction\":\"PROJECT_TO_PERSON\",\"decision\":\"REJECT\",\"reason\":\"변경\"}" )).andExpect(status().isConflict());
    }
    @Test void foreignDecisionLeavesStateUntouched() throws Exception {
        var data=run(input());long result=data.path("results").get(0).path("resultId").longValue();
        mvc.perform(patch(BASE+"/results/{id}/decision",result).session(new MockHttpSession()).contentType("application/json").content("{\"direction\":\"PROJECT_TO_PERSON\",\"decision\":\"ADOPT\"}" )).andExpect(status().isForbidden());assertThat(results.values()).allMatch(r->r.decision().equals("PENDING"));
    }
    @Test void staleDecision409PreservesSnapshot() throws Exception {
        var data=run(input());var before=new LinkedHashMap<>(requests);when(mapper.fingerprint()).thenReturn("changed");
        mvc.perform(patch(BASE+"/results/{id}/decision",data.path("results").get(0).path("resultId").longValue()).session(session).contentType("application/json").content("{\"direction\":\"PROJECT_TO_PERSON\",\"decision\":\"ADOPT\"}" )).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_RECOMMENDATION"));assertThat(requests).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={" ","long"}) void rejectReasonValidation(String reason) throws Exception {
        String text=reason.equals("long")?"가".repeat(501):reason;
        var body=json.createObjectNode().put("direction",A).put("decision","REJECT").put("reason",text);
        mvc.perform(patch(BASE+"/results/1/decision").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("reason"));verify(mapper,never()).transition(any());
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc"}) void invalidIds(String id) throws Exception {
        mvc.perform(get(BASE+"/"+id)).andExpect(status().isBadRequest());mvc.perform(get(BASE+"/developers/"+id+"/career-card")).andExpect(status().isBadRequest());
    }
    @Test void missingRequest404() throws Exception {mvc.perform(get(BASE+"/42").session(session)).andExpect(status().isNotFound());}
    @ParameterizedTest @ValueSource(strings={"size=7","page=-1","fromYm=2027-01&toYm=2026-12"}) void historyValidation(String query) throws Exception {
        mvc.perform(get(BASE+"/history?"+query)).andExpect(status().isBadRequest());verify(mapper,never()).history(any());
    }
    @Test void historyFilteredTotalAndNullQuality() throws Exception {
        run(input());mvc.perform(get(BASE+"/history").session(session).param("direction",A)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(50)).andExpect(jsonPath("$.data.quality.adoption.denominator").value(0)).andExpect(jsonPath("$.data.quality.adoption.rate").isEmpty());
    }
    @Test void draftUnavailableIsReadOnlyAndManualStillWorks() throws Exception {
        mvc.perform(post(BASE+"/criteria-draft").session(session).contentType("application/json").content("{\"question\":\"백엔드 2명 6개월\"}" )).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("MODEL_UNAVAILABLE"));
        assertThat(requests).isEmpty();run(input());assertThat(requests).hasSize(1);
    }
    @Test void validDraftDoesNotRunRecommendation() throws Exception {
        when(parser.parseCriteria(any())).thenReturn(new AiLocalIntentParser.Parsed("OK",json.createObjectNode().put("jobCode","BE").put("headcount",2).put("durationMonths",6),true));
        mvc.perform(post(BASE+"/criteria-draft").session(session).contentType("application/json").content("{\"question\":\"금융권 본인인증 백엔드 고급 2명 6개월\"}" )).andExpect(status().isOk()).andExpect(jsonPath("$.data.criteria.headcount").value(2)).andExpect(jsonPath("$.data.modelUsed").value(true));assertThat(requests).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={" ","long"}) void invalidDraft(String q) throws Exception {
        var body=json.createObjectNode().put("question",q.equals("long")?"가".repeat(1001):q);
        mvc.perform(post(BASE+"/criteria-draft").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());verifyNoInteractions(parser);
    }
    @Test void sensitiveDraftNeverForwardsRawQuestion() throws Exception {
        mvc.perform(post(BASE+"/criteria-draft").session(session).contentType("application/json").content("{\"question\":\"개인 원가 900만원\"}" )).andExpect(status().isOk());
        verify(parser).parseCriteria(argThat(p->p.needsClarification()&&p.modelQuestion().isEmpty()));
    }
    @Test void careerIsRealXlsxSafeAndOwned() throws Exception {
        run(input());when(mapper.career(1)).thenReturn("""
            [{"projectId":1,"projectName":"실제 프로젝트","startDate":"2026-01-01","endDate":"2026-06-30","jobCode":"BE","skillCodes":["JAVA"],"monthByMonthMm":[{"month":"2026-01","mm":1}]},
             {"projectId":2,"projectName":"다음 실수행","startDate":"2026-07-01","jobCode":"FE","skillCodes":[],"monthByMonthMm":[]}]
            """);
        byte[] bytes=mvc.perform(get(BASE+"/developers/1/career-card").session(session)).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition","attachment; filename=career-card.xlsx")).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(5);assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("실제 프로젝트");
            assertThat(sheet.getRow(5).getCell(2).getStringCellValue()).isEqualTo("미기록");assertThat(sheet.getRow(4).getCell(5).getStringCellValue()).contains("2026-01: 1 MM");
            StringBuilder text=new StringBuilder();workbook.forEach(s->s.forEach(r->r.forEach(c->text.append(c.toString()))));assertThat(text.toString()).doesNotContain("급여","연락처","원가","salary","tel_no");
        }
        mvc.perform(get(BASE+"/developers/1/career-card").session(new MockHttpSession())).andExpect(status().isForbidden());assertThat(requests).hasSize(1);
    }
    @Test void missingDeveloper404() throws Exception {mvc.perform(get(BASE+"/developers/99/career-card").session(session)).andExpect(status().isNotFound());}
    @Test void requiredDependencyFailure503() throws Exception {
        when(mapper.people()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private connection"));
        mvc.perform(post(BASE+"/search").contentType("application/json").content(input().toString())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("추천 원천을 조회할 수 없습니다."));
    }
}
