package com.sicost.cashflow;

import java.time.LocalDate;
import java.util.List;

public final class CashFlowModels {
    private CashFlowModels() {}
    public record Opening(String month, Long amount) {}
    public record Balance(String month, Long amount, Long closing, Long minimum, String initial, String status) {}
    public record Inflow(Long inflw_id, String month, long amount, String inflw_se_cd, String inflw_item_nm,
            LocalDate planned_date, LocalDate actual_date, String remark) {}
    public record Inflows(String month, long amount, List<Inflow> items, int total) {}
    public record InflowWrite(Inflow input, Long actor) {}
    public record OpeningWrite(Opening input, Long actor) {}
    public record Audit(String table, String id, String type, String before, String after, Long actor) {}
    public record Event(LocalDate planned_date, long planned_amount, LocalDate actual_date,
            long actual_amount, long remaining_amount, boolean inflow, String category) {
        public Event(LocalDate planned_date,long planned_amount,LocalDate actual_date,long actual_amount,long remaining_amount,boolean inflow) {
            this(planned_date,planned_amount,actual_date,actual_amount,remaining_amount,inflow,"ETC");
        }
    }
    public record Category(String code, long planned_inflow, long planned_outflow,
            Long actual_inflow, Long actual_outflow) {}
    public record Month(String month, Long planned_opening, Long planned_closing, Long actual_closing,
            Long forecast_closing, String warning, Long actual_opening, long planned_inflow,
            long planned_outflow, Long actual_inflow, Long actual_outflow, Long forecast_opening,
            long forecast_inflow, long forecast_outflow, boolean closed, List<Category> categories) {}
    public record CashFlowFile(String file_name, String content_type, String content_base64) {}
    public record Flow(int year, String mode, List<Month> months, CashFlowFile export_file,
            String first_risk_month, String initial_month) {}
}
