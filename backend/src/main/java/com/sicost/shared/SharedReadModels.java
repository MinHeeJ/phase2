package com.sicost.shared;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public final class SharedReadModels {
    private SharedReadModels() {}

    public record CodeOption(String code, String name, String attr1, String attr2, String attr3) {}
    public record MenuRow(long menuId, Long parentId, String name, String screenId, String icon, boolean mobile) {}
    public record MenuNode(long menuId, String name, String screenId, String icon, boolean mobile, List<MenuNode> children) {}
    public record MonthClose(String month, String status) {}
    public record OperatingParam(
        @JsonProperty("oprt_std_id") String key,
        @JsonProperty("aply_bgn_ym") String effectiveMonth,
        @JsonProperty("oprt_std_nm") String name,
        @JsonProperty("num_val") BigDecimal numericValue,
        @JsonProperty("txt_val") String textValue,
        @JsonProperty("unit_nm") String unit,
        @JsonProperty("use_plc_desc") String usage) {}
}
