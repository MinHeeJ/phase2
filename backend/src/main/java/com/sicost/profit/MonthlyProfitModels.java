package com.sicost.profit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class MonthlyProfitModels {
    private MonthlyProfitModels() {}
    public record SnapshotKey(String month,String source) {}
    public record Entry(String category,String name,long amount) {}
    public record Facts(long payroll_count,long draft_count) {}
    public record TaxMonth(String month,long amount) {}
    public record ProjectMonth(String month,long projectId) {}
    public record Project(String contract,String contract_name,Long customer_id,String customer_name,Long part_id,String part_name) {}
    public record Detail(String dimension,String code,String name,long revenue,long cost,long gross_profit) {}
    public record ExportFile(String file_name,String content_type,String content_base64) {}
    public record Composition(String code,String name,Long revenue,Long cost,Long gross_profit,BigDecimal share,BigDecimal margin) {}
    public record Annual(int year,List<Map<String,Object>> months,List<Map<String,Object>> previous_months) {}
    public static final List<String> FIELDS=List.of("revenue","project_labor_cost","idle_cost","direct_expenses","cost","gross_profit","sga_input","indirect_labor_cost","rnd_cost","sga","previous_adjustment","operating_profit","non_operating_income","non_operating_expenses","corporate_tax","net_profit","allocation_revenue_reference");
    public static final List<String> LABELS=List.of("매출","배분 원가","대기 원가","직접 경비","매출원가","매출총이익","판관비 입력","간접 인건비","연구개발비","판관비","전월 조정","영업이익","영업외수익","영업외비용","법인세(월할)","당기순이익","배분 기준 매출(참고)");
}
