package com.si.cost.shared;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public final class SharedReadModels {
    private SharedReadModels() { }

    public record MenuRow(long menuId, Long parentId, String name, String screenId, String icon, boolean mobile) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MenuNode(long menuId, String name, String screenId, String icon, boolean mobile,
                           List<MenuNode> children) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CodeOption(String code, String name, String attr1, String attr2, String attr3) { }

    public record MonthClose(String month, String status) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EffectiveParam(
            @JsonProperty("oprt_std_id") String key,
            @JsonProperty("aply_bgn_ym") String effectiveMonth,
            @JsonProperty("oprt_std_nm") String name,
            @JsonProperty("num_val") BigDecimal numberValue,
            @JsonProperty("txt_val") String textValue,
            @JsonProperty("unit_nm") String unit,
            @JsonProperty("use_plc_desc") String usage) { }
}
