package com.sicost.staffrecommendations;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.sicost.staffrecommendations.StaffRecommendationModels.*;

public final class StaffRecommendationScorer {
    private StaffRecommendationScorer() {}
    public static BigDecimal number(String value) { return new BigDecimal(value).setScale(2,RoundingMode.HALF_UP); }
    public static BigDecimal cost(BigDecimal price,BigDecimal cost) {
        if(price==null||price.signum()<=0||cost==null)return number("5");
        return price.subtract(cost).multiply(number("50")).divide(price,2,RoundingMode.HALF_UP).max(number("0")).min(number("10"));
    }
    public static Set<String> strings(JsonNode array) {var set=new LinkedHashSet<String>();array.forEach(n->set.add(n.asText()));return set;}
    public static boolean leave(JsonNode p,LocalDate date) {
        for(var l:p.path("leaves"))if(!l.path("code").asText().equals("RESIGN")&& !date.isBefore(LocalDate.parse(l.path("start").asText()))
                && (l.path("end").isNull()||!date.isAfter(LocalDate.parse(l.path("end").asText()))))return true;
        return false;
    }
    public static boolean excluded(JsonNode p,YearMonth start,int duration) {
        if(!p.path("enabled").asBoolean())return true;
        for(LocalDate day=start.atDay(1),end=start.plusMonths(duration-1).atEndOfMonth();!day.isAfter(end);day=day.plusDays(1)) {
            boolean resigned=p.hasNonNull("resign")&&!day.isBefore(LocalDate.parse(p.path("resign").asText()));
            boolean notHired=p.hasNonNull("hire")&&day.isBefore(LocalDate.parse(p.path("hire").asText()));
            boolean resignedHistory=false;
            for(var l:p.path("leaves"))if("RESIGN".equals(l.path("code").asText())&&!day.isBefore(LocalDate.parse(l.path("start").asText())))resignedHistory=true;
            if(!resigned&&!notHired&&!resignedHistory&&!leave(p,day))return false;
        }
        return true;
    }
    public static BigDecimal confirmed(JsonNode p,String month) {
        BigDecimal result=number("0");for(var a:p.path("assignments"))for(var m:a.path("months"))if(month.equals(m.path("month").asText()))result=result.add(m.path("mm").decimalValue());return result;
    }
    public static BigDecimal temporary(JsonNode p,String month) {
        BigDecimal result=number("0");for(var t:p.path("temporary"))if(month.compareTo(t.path("start").asText())>=0&&month.compareTo(t.path("end").asText())<=0)result=result.add(t.path("mm").decimalValue());return result;
    }
    public static boolean monthLeave(JsonNode p,YearMonth month) {
        for(LocalDate day=month.atDay(1);!day.isAfter(month.atEndOfMonth());day=day.plusDays(1))if(leave(p,day))return true;return false;
    }
    public static String earliest(JsonNode p,YearMonth start,int duration,BigDecimal requested) {
        for(int i=0;i<=duration;i++) {
            YearMonth month=start.plusMonths(i);
            if(confirmed(p,month.toString()).add(requested).compareTo(BigDecimal.ONE)<=0&&!monthLeave(p,month)&&!excluded(p,month,1))return month.toString();
        }return null;
    }
    public static Breakdown score(JsonNode p,ObjectNode criteria,List<String> grades,BigDecimal cost,LocalDate today) {
        String job=criteria.path("jobCode").asText(),grade=criteria.path("gradeCode").asText();
        BigDecimal jobScore=number(job.equals(p.path("job").asText())?"25":strings(p.path("jobs")).contains(job)?"20":"0");
        var required=strings(criteria.path("skillCodes"));var owned=strings(p.path("skills"));
        long matched=required.stream().filter(owned::contains).count();
        BigDecimal skills=required.isEmpty()?number("10"):number("20").multiply(BigDecimal.valueOf(matched)).divide(BigDecimal.valueOf(required.size()),2,RoundingMode.HALF_UP);
        boolean sameCustomer=false,sameIndustry=false;int ended=0,extended=0,noEarly=0;
        for(var a:p.path("assignments")) {
            if(!LocalDate.parse(a.path("start").asText()).isAfter(today)) {
                if(criteria.hasNonNull("customerId")&&a.path("customerId").equals(criteria.get("customerId")))sameCustomer=true;
                if(criteria.hasNonNull("industryCode")&&criteria.path("industryCode").asText().equals(a.path("industry").asText()))sameIndustry=true;
            }
            if(LocalDate.parse(a.path("end").asText()).isBefore(today)) { ended++;if(a.path("extended").asBoolean())extended++;if(!a.path("early").asBoolean())noEarly++; }
        }
        BigDecimal customer=!criteria.hasNonNull("customerId")&&!criteria.hasNonNull("industryCode")?number("7.5"):number(sameCustomer?"15":sameIndustry?"10":"0");
        String own=p.path("grade").asText();
        BigDecimal gradeScore=number(grade.isEmpty()?"5":grade.equals(own)?"10":grades.contains(grade)&&grades.contains(own)&&Math.abs(grades.indexOf(grade)-grades.indexOf(own))==1?"5":"0");
        BigDecimal performance=ended==0?number("5"):BigDecimal.valueOf(extended).multiply(number("6")).add(BigDecimal.valueOf(noEarly).multiply(number("4"))).divide(BigDecimal.valueOf(ended),2,RoundingMode.HALF_UP);
        YearMonth start=YearMonth.parse(criteria.path("startYm").asText());
        String earliest=earliest(p,start,criteria.path("durationMonths").asInt(1),criteria.path("requestedMm").decimalValue());
        BigDecimal available=number(start.toString().equals(earliest)?"10":start.plusMonths(1).toString().equals(earliest)?"5":"0");
        return new Breakdown(jobScore,skills,customer,gradeScore,performance,cost,available);
    }
    public static ObjectNode availability(ObjectMapper json,JsonNode p,ObjectNode criteria) {
        ObjectNode result=json.createObjectNode();var months=result.putArray("monthByMonthMm");Set<String> warnings=new LinkedHashSet<>();
        YearMonth start=YearMonth.parse(criteria.path("startYm").asText());BigDecimal mm=criteria.path("requestedMm").decimalValue();
        for(int i=0;i<criteria.path("durationMonths").asInt(1);i++) {
            YearMonth month=start.plusMonths(i);BigDecimal confirmed=confirmed(p,month.toString()),temporary=temporary(p,month.toString());
            ObjectNode row=months.addObject().put("month",month.toString()).put("confirmedMm",confirmed).put("activeTemporaryMm",temporary).put("requestedMm",mm);
            var flags=row.putArray("warnings");
            if(confirmed.add(mm).compareTo(BigDecimal.ONE)>0)flags.add("CONFIRMED_OVERLAP");
            if(temporary.signum()>0)warnings.add("ACTIVE 가배정 중첩: 일정 확인 필요");
            if(temporary.signum()>0&&confirmed.add(temporary).add(mm).compareTo(BigDecimal.ONE)>0)flags.add("TEMP_OVERLAP");
            if(monthLeave(p,month))flags.add("CONFIRMED_UNAVAILABLE");
            flags.forEach(n->warnings.add(n.asText()));
        }
        String earliest=earliest(p,start,criteria.path("durationMonths").asInt(1),mm);result.put("earliestStartYm",earliest);
        result.putNull("exitDate");result.putNull("extensionLikelihood");var current=result.putArray("currentProjectNames");
        LocalDate basis=start.atDay(1);
        for(var a:p.path("assignments"))if(!LocalDate.parse(a.path("start").asText()).isAfter(basis)&&!LocalDate.parse(a.path("end").asText()).isBefore(basis)) {
            current.add(a.path("projectName").asText());result.set("exitDate",a.get("end"));result.set("extensionLikelihood",a.get("extension"));
            if("H".equals(a.path("extension").asText()))warnings.add("연장 가능성 높음, 일정 확인 필요");
        }
        String status=warnings.contains("CONFIRMED_OVERLAP")?"확정 충돌":!warnings.isEmpty()?"주의":earliest==null?"판정 불가":"가능";
        result.put("availabilityStatus",status);result.set("availabilityWarnings",json.valueToTree(warnings));return result;
    }
}
