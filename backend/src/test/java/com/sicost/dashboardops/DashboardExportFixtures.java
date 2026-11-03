package com.sicost.dashboardops;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sicost.common.dashboard.DashboardReadContext;
import java.time.YearMonth;

final class DashboardExportFixtures {
    private DashboardExportFixtures() {}
    static ObjectNode response(ObjectMapper json,DashboardReadContext c) {
        try {
            ObjectNode data=(ObjectNode)json.readTree("""
                {"lastClosedMonth":null,"closeStatus":"OPEN",
                 "kpis":{
                   "revenue":{"state":"READY","message":null,"error":null,"amount":123456789,"previous":{"previousAmount":0,"changePercent":null,"isReferenceRate":true,"label":"비교 불가"},"targetAmount":null,"achievementPercent":null},
                   "operatingProfit":{"state":"READY","message":null,"error":null,"amount":-21555,"marginPercent":-1.7,"previous":{"previousAmount":-12000,"changePercent":null,"isReferenceRate":true,"label":"참고 증감"}},
                   "ytdOperatingProfit":{"state":"READY","message":null,"error":null,"amount":0,"targetAmount":100000,"achievementPercent":0,"closedMonths":[],"openMonths":[]},
                   "utilization":{"state":"MISSING","message":"마감 자료 확인 필요","error":null,"confirmedMm":null,"availableMm":null,"utilizationPercent":null,"targetPercent":90,"targetDifferencePp":null,"idleDeveloperCount":null},
                   "receivables":{"state":"READY","message":null,"error":null,"amount":9223372036854775807,"overdueCount":1,"overdueAmount":1111}},
                 "actions":{"state":"PARTIAL","message":"청구 누락 판정 불가","error":null,"totalKinds":2,"urgentKinds":1,"items":[
                   {"type":"OVERDUE_RECEIVABLE","severity":"URGENT","state":"READY","title":"미수 연체","affectedCount":1,"unit":"CASE","description":"확정 청구","sourceMonth":null,"withoutNextAssignmentCount":null,"recommendedDeveloperCount":null},
                   {"type":"BILLING_GAP","severity":"CAUTION","state":"UNDETERMINED","title":"청구 누락","affectedCount":null,"unit":"CASE","description":"원천 확인 필요","sourceMonth":null,"withoutNextAssignmentCount":null,"recommendedDeveloperCount":null}]},
                 "closeProgress":{"state":"READY","message":null,"error":null,"status":"NOT_CHECKED","completedCount":0,"totalCount":9,"checkedAt":null,"checks":[],"incompleteChecks":[]},
                 "projectAlerts":{"state":"READY","message":null,"error":null,"items":[{"projectId":17,"projectName":"=1+1","severity":"RISK","statuses":["예산 위험"],"marginPercent":null,"budgetUsagePercent":101.3,"primaryCostCause":{"sourceId":17,"name":"투입 초과","amount":12345},"dataState":"MISSING_COST"}]},
                 "revenueCostTrend":{"state":"READY","message":null,"error":null,"vatIncluded":false,"months":[]},
                 "workforceForecast":{"state":"READY","message":null,"error":null,"assumptions":["확정 투입 + 확률 가중 영업 기회","임시 가배정 제외"],"shortageThresholdMm":0.4,"months":[]},
                 "partSummary":{"state":"PARTIAL","message":"본사 정산 없음","error":null,"marginLabel":"파트 이익률","items":[{"partId":3,"partName":"영업 파트","classification":"SALES_PART","revenue":100001,"partMarginPercent":12.3,"marginState":"READY"},{"partId":2,"partName":"본사","classification":"HEADQUARTERS","revenue":0,"partMarginPercent":null,"marginState":"MISSING_COST"},{"partId":null,"partName":"미지정","classification":"UNASSIGNED","revenue":null,"partMarginPercent":null,"marginState":"MISSING_COST"}]},
                 "pipeline":{"state":"READY","message":null,"error":null,"openOpportunityCount":3,"weightedExpectedRevenue":101,"startingOpportunityTotal":1,"startingOpportunities":[{"opportunityId":31,"name":"예상 기회","stage":"NEGO","probabilityPercent":33.33,"expectedAmount":101,"weightedExpectedAmount":33}]},
                 "cashForecast":{"state":"ERROR","message":"기초 잔액 확인 필요","error":{"code":"SOURCE_ERROR","message":"기초 잔액 확인 필요"},"vatIncluded":true,"months":[]},
                 "warnings":[{"section":"cashForecast","code":"SOURCE_ERROR","message":"기초 잔액 확인 필요","basisMonth":null}]}
                """);
            data.put("asOf",c.asOf().toString());data.put("basisMonth",c.month().toString());
            for (String key:new String[]{"revenue","operatingProfit","utilization"})
                ((ObjectNode)data.path("kpis").path(key)).put("basisMonth",c.month().toString());
            ((ObjectNode)data.path("kpis").path("ytdOperatingProfit")).put("fromMonth",c.month().getYear()+"-01").put("toMonth",c.month().toString());
            ((ObjectNode)data.path("kpis").path("receivables")).put("basisDate",c.today().toString());
            for(String key:new String[]{"closeProgress","revenueCostTrend","partSummary"}) ((ObjectNode)data.path(key)).put("basisMonth",c.month().toString());
            for(String key:new String[]{"actions","projectAlerts","workforceForecast","pipeline","cashForecast"}) ((ObjectNode)data.path(key)).put("basisDate",c.today().toString());
            for(String key:new String[]{"workforceForecast","pipeline","cashForecast"}) {
                ((ObjectNode)data.path(key)).put("fromMonth",YearMonth.from(c.today()).plusMonths(1).toString())
                    .put("toMonth",YearMonth.from(c.today()).plusMonths(key.equals("workforceForecast")?6:3).toString());
            }
            ((ObjectNode)data.path("pipeline").path("startingOpportunities").get(0)).put("startDate",YearMonth.from(c.today()).plusMonths(1).atDay(1).toString());
            var trend=((ObjectNode)data.path("revenueCostTrend")).putArray("months");
            for(int i=1;i<=12;i++) {
                var p=trend.addObject().put("month",YearMonth.of(c.month().getYear(),i).toString()).put("closeStatus","OPEN").put("targetRevenue",100001+i).put("targetState","READY");
                if(i<=c.month().getMonthValue()) p.put("revenue",100000+i).put("cost",70000+i).put("actualState","READY");
                else p.putNull("revenue").putNull("cost").put("actualState","FUTURE");
            }
            var workforce=((ObjectNode)data.path("workforceForecast")).putArray("months");
            for(int i=1;i<=6;i++) workforce.addObject().put("month",YearMonth.from(c.today()).plusMonths(i).toString())
                .put("availableMm",2.81).put("requiredMm",2.40).put("shortageMm",-0.41).put("isShortage",false).put("state","READY");
            var cash=((ObjectNode)data.path("cashForecast")).putArray("months");
            for(int i=1;i<=3;i++) cash.addObject().put("month",YearMonth.from(c.today()).plusMonths(i).toString())
                .putNull("expectedFinalBalance").put("minimumOperatingCash",100000).putNull("isShortage").put("state","MISSING");
            return data;
        } catch(Exception e) {throw new IllegalStateException(e);}
    }
}
