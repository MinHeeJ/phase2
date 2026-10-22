package com.sicost.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

public final class AdminModels {
    private AdminModels() {}
    public record CodeGroup(String cd_grp_id, String cd_grp_nm, String cd_grp_se_cd, String attr1_nm,
        String attr2_nm, String attr3_nm, int sort_seq, String use_yn, long codeCount) {}
    public record CodeRow(String dtl_cd, String cd_nm, int sort_seq, String use_yn,
        String attr1_val, String attr2_val, String attr3_val, LocalDate aply_bgn_dt) {}
    public record CodeGroupCreate(String cd_grp_id, String cd_grp_nm, String attr1_nm, String attr2_nm, String attr3_nm) {}
    public record ExcelMap(String map_id, String excel_lbl_nm, String trns_cd_grp_id, String trns_cd,
        String cd_grp_nm, String cd_nm, String map_role_cd, String rmrk_desc, String use_yn) {}
    public record ExcelMapCreate(String excel_lbl_nm, String trns_cd_grp_id, String trns_cd, String map_role_cd, String rmrk_desc) {}
    public record ExcelMapPatch(String map_role_cd, String rmrk_desc, String use_yn, Set<String> supplied) {}
    public record OperatingParam(String oprt_std_id, String aply_bgn_ym, String oprt_std_nm,
        BigDecimal num_val, String txt_val, String unit_nm, String use_plc_desc) {}
    public record ParamValueSave(BigDecimal num_val, String txt_val) {}
    public record CodeChange(LocalDateTime chg_at, String cd_grp_id, String cd_grp_nm, String dtl_cd,
        String cd_chg_ty_cd, String item_nm, String bfr_val, String aft_val, Long chg_user_id) {}
    public record HistoryEntry(String group, String code, String type, String item, String before, String after, Long actor) {}
    public record CodeWrite(String group, CodeRow row, Set<String> changed, Long actor, LocalDateTime now) {}
    public record ParamWrite(OperatingParam source, String month, ParamValueSave value, Long actor) {}
    public record MapWrite(ExcelMap original, ExcelMapPatch patch) {}
    public record GroupWrite(CodeGroupCreate input, Long actor) {}
    public record MapCreateWrite(ExcelMapCreate input, Long actor) {}
}
