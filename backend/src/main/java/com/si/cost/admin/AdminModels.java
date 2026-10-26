package com.si.cost.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class AdminModels {
    private AdminModels() {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record Group(@JsonProperty("cd_grp_id") String groupId, @JsonProperty("cd_grp_nm") String name,
            @JsonProperty("cd_grp_se_cd") String kind, @JsonProperty("attr1_nm") String attr1Name,
            @JsonProperty("attr2_nm") String attr2Name, @JsonProperty("attr3_nm") String attr3Name,
            @JsonProperty("sort_seq") int order, @JsonProperty("use_yn") String use, int codeCount) {}
    public record Code(@JsonProperty("dtl_cd") String code, @JsonProperty("cd_nm") String name,
            @JsonProperty("sort_seq") Integer order, @JsonProperty("use_yn") String use,
            @JsonProperty("attr1_val") String attr1, @JsonProperty("attr2_val") String attr2,
            @JsonProperty("attr3_val") String attr3, @JsonProperty("aply_bgn_dt") LocalDate date) {}
    public record GroupCreate(@JsonProperty("cd_grp_id") String groupId, @JsonProperty("cd_grp_nm") String name,
            @JsonProperty("attr1_nm") String attr1Name, @JsonProperty("attr2_nm") String attr2Name,
            @JsonProperty("attr3_nm") String attr3Name) {}
    public record ExcelMap(@JsonProperty("map_id") String id, @JsonProperty("excel_lbl_nm") String label,
            @JsonProperty("trns_cd_grp_id") String groupId, @JsonProperty("trns_cd") String code,
            @JsonProperty("cd_grp_nm") String groupName, @JsonProperty("cd_nm") String codeName,
            @JsonProperty("map_role_cd") String role, @JsonProperty("rmrk_desc") String memo,
            @JsonProperty("use_yn") String use) {}
    public record MapCreate(@JsonProperty("excel_lbl_nm") String label, @JsonProperty("trns_cd_grp_id") String groupId,
            @JsonProperty("trns_cd") String code, @JsonProperty("map_role_cd") String role,
            @JsonProperty("rmrk_desc") String memo) {}
    public record Param(@JsonProperty("oprt_std_id") String key, @JsonProperty("aply_bgn_ym") String month,
            @JsonProperty("oprt_std_nm") String name, @JsonProperty("num_val") BigDecimal number,
            @JsonProperty("txt_val") String text, @JsonProperty("unit_nm") String unit,
            @JsonProperty("use_plc_desc") String usage) {}
    public record ParamSource(String key, String month, String name, BigDecimal number, String text,
            String unit, String usage, String description) {}
    public record ParamSave(@JsonProperty("num_val") BigDecimal number, @JsonProperty("txt_val") String text) {}
    public record Change(@JsonProperty("chg_at") LocalDateTime changedAt, @JsonProperty("cd_grp_id") String groupId,
            @JsonProperty("cd_grp_nm") String groupName, @JsonProperty("dtl_cd") String code,
            @JsonProperty("cd_chg_ty_cd") String type, @JsonProperty("item_nm") String item,
            @JsonProperty("bfr_val") String before, @JsonProperty("aft_val") String after,
            @JsonProperty("chg_user_id") Long userId) {}
    public record Audit(String groupId, String code, String type, String item, String before, String after) {}
}
