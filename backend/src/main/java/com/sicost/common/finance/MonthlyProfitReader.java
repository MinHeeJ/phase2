package com.sicost.common.finance;

import java.util.List;

/** Read-only projection implemented by the shared monthly profit calculation. */
public interface MonthlyProfitReader {
    record Month(String month, Long revenue, Long operatingProfit, List<String> warnings) {}
    Month read(String month);
}
