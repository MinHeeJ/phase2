package com.sicost.staffrecommendations;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.staffrecommendations.StaffRecommendationScorer.*;

class StaffRecommendationScorerTest {
    ObjectMapper json=new ObjectMapper();ObjectNode p,c;
    LocalDate today=LocalDate.of(2026,12,1);
    @BeforeEach void setup() throws Exception {
        p=(ObjectNode)json.readTree("{\"enabled\":true,\"job\":\"BE\",\"grade\":\"SR\",\"jobs\":[\"FE\"],\"skills\":[\"JAVA\",\"SPRING\"],\"leaves\":[],\"assignments\":[],\"temporary\":[]}");
        c=(ObjectNode)json.readTree("{\"jobCode\":\"BE\",\"startYm\":\"2027-01\",\"durationMonths\":6,\"requestedMm\":1,\"skillCodes\":[\"JAVA\",\"SPRING\"]}");
    }
    @ParameterizedTest @CsvSource({"BE,25","FE,20","DBA,0"}) void exactJobNoGuessedSimilarity(String job,String expected){c.put("jobCode",job);assertThat(score(p,c,List.of(),number("5"),today).job_scr()).isEqualByComparingTo(expected);}
    @ParameterizedTest @CsvSource({"JAVA|SPRING,20","JAVA,10","SQL,0"}) void structuredSkills(String skills,String expected){p.set("skills",json.valueToTree(skills.split("\\|")));assertThat(score(p,c,List.of(),number("5"),today).skl_scr()).isEqualByComparingTo(expected);}
    @Test void noRequiredSkillsNeutralTen(){c.remove("skillCodes");assertThat(score(p,c,List.of(),number("5"),today).skl_scr()).isEqualByComparingTo("10");}
    @Test void dedupSkillsNoDoubleCounting(){c.set("skillCodes",json.valueToTree(List.of("JAVA","JAVA")));assertThat(score(p,c,List.of(),number("5"),today).skl_scr()).isEqualByComparingTo("20");}
    @Test void noCustomerConditionNeutralAndNoPerformanceEvidence(){var score=score(p,c,List.of(),number("5"),today);assertThat(score.cust_scr()).isEqualByComparingTo("7.5");assertThat(score.achv_scr()).isEqualByComparingTo("5");assertThat(score.total()).isLessThanOrEqualTo(number("100"));}
    @Test void customerAndIndustryDoNotStack() throws Exception {
        p.set("assignments",json.readTree("[{\"id\":1,\"start\":\"2025-01-01\",\"end\":\"2025-12-31\",\"customerId\":9,\"industry\":\"FIN\",\"extended\":true,\"early\":false,\"months\":[]}]"));c.put("customerId",9).put("industryCode","FIN");
        var s=score(p,c,List.of(),number("5"),today);assertThat(s.cust_scr()).isEqualByComparingTo("15");assertThat(s.achv_scr()).isEqualByComparingTo("10");
        c.put("customerId",8);assertThat(score(p,c,List.of(),number("5"),today).cust_scr()).isEqualByComparingTo("10");
    }
    @Test void gradeAdjacencyRequiresActualCommonOrder(){c.put("gradeCode","MD");assertThat(score(p,c,List.of(),number("5"),today).grd_scr()).isZero();assertThat(score(p,c,List.of("JR","MD","SR"),number("5"),today).grd_scr()).isEqualByComparingTo("5");c.put("gradeCode","SR");assertThat(score(p,c,List.of(),number("5"),today).grd_scr()).isEqualByComparingTo("10");}
    @ParameterizedTest @CsvSource({"100,110,0","100,100,0","100,90,5","100,80,10","100,0,10","0,10,5"}) void isolatedCostFormula(String price,String cost,String expected){assertThat(cost(new BigDecimal(price),new BigDecimal(cost))).isEqualByComparingTo(expected);}
    @Test void absentPriceAndCostNeutral(){assertThat(cost(null,null)).isEqualByComparingTo("5");}
    @Test void laterConfirmedMonthAndTemporaryOverlapSeparate() throws Exception {
        p.set("assignments",json.readTree("[{\"start\":\"2026-01-01\",\"end\":\"2027-02-28\",\"projectName\":\"실제\",\"extension\":\"H\",\"months\":[{\"month\":\"2027-02\",\"mm\":1}]}]"));
        p.set("temporary",json.readTree("[{\"start\":\"2027-01\",\"end\":\"2027-03\",\"mm\":0.5}]"));var result=availability(json,p,c);
        assertThat(strings(result.path("availabilityWarnings"))).contains("CONFIRMED_OVERLAP","TEMP_OVERLAP","연장 가능성 높음, 일정 확인 필요");
        assertThat(result.path("monthByMonthMm").get(0).path("confirmedMm").decimalValue()).isZero();assertThat(result.path("monthByMonthMm").get(1).path("confirmedMm").decimalValue()).isEqualByComparingTo("1");
        var before=score(p,c,List.of(),number("5"),today);((ObjectNode)p.path("assignments").get(0)).put("extension","L");assertThat(score(p,c,List.of(),number("5"),today)).isEqualTo(before);
    }
    @Test void currentFullMmIsNotExcluded() throws Exception {
        p.set("assignments",json.readTree("[{\"start\":\"2026-01-01\",\"end\":\"2026-12-31\",\"projectName\":\"실제\",\"extension\":null,\"months\":[{\"month\":\"2026-12\",\"mm\":1}]}]"));
        assertThat(excluded(p,YearMonth.of(2027,1),6)).isFalse();assertThat(score(p,c,List.of(),number("5"),today).asgn_psbl_scr()).isEqualByComparingTo("10");
    }
    @Test void partialLeaveRetainedFullLeaveExcluded() throws Exception {
        p.set("leaves",json.readTree("[{\"code\":\"LEAVE\",\"start\":\"2027-01-01\",\"end\":\"2027-01-31\"}]"));assertThat(excluded(p,YearMonth.of(2027,1),6)).isFalse();
        assertThat(score(p,c,List.of(),number("5"),today).asgn_psbl_scr()).isEqualByComparingTo("5");assertThat(strings(availability(json,p,c).path("availabilityWarnings"))).contains("CONFIRMED_UNAVAILABLE");
        ((ObjectNode)p.path("leaves").get(0)).put("end","2027-06-30");assertThat(excluded(p,YearMonth.of(2027,1),6)).isTrue();
    }
    @Test void resignationAndDisabledExclude(){p.put("resign","2026-12-31");assertThat(excluded(p,YearMonth.of(2027,1),6)).isTrue();p.remove("resign");p.put("enabled",false);assertThat(excluded(p,YearMonth.of(2027,1),6)).isTrue();}
}
