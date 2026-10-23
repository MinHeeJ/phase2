package com.sicost.admin;

import com.sicost.common.api.ApiException;
import com.sicost.common.api.PageRequest;
import java.util.Set;

public record AdminQuery(int page, int size, long offset, String order, String group, String key, String month) {
    public static AdminQuery of(PageRequest p, String resource, String group, String key, String month) {
        Set<String> fields = switch(resource) {
            case "groups" -> Set.of("cd_grp_id","cd_grp_nm","cd_grp_se_cd","sort_seq","use_yn","codeCount");
            case "codes" -> Set.of("dtl_cd","cd_nm","sort_seq","use_yn","aply_bgn_dt","attr1_val","attr2_val","attr3_val");
            case "maps" -> Set.of("map_id","excel_lbl_nm","cd_grp_nm","cd_nm","map_role_cd","use_yn","rmrk_desc");
            case "history" -> Set.of("chg_at","cd_grp_id","cd_grp_nm","dtl_cd","cd_chg_ty_cd","item_nm","chg_user_id");
            default -> Set.of("oprt_std_id","aply_bgn_ym","oprt_std_nm","num_val","txt_val","unit_nm","use_plc_desc");
        };
        String fallback=switch(resource) { case "groups" -> "sort_seq ASC, cd_grp_id ASC"; case "codes" -> "sort_seq ASC, dtl_cd ASC"; case "maps" -> "excel_lbl_nm ASC, map_id ASC"; case "history" -> "chg_at DESC, cd_hist_id DESC"; case "versions" -> "aply_bgn_ym DESC"; default -> "oprt_std_id ASC"; };
        String order=fallback;
        if(p.sort()!=null && !p.sort().isBlank()) {
            String[] bits=p.sort().split(",",-1);
            if(bits.length!=2 || !fields.contains(bits[0]) || !Set.of("asc","desc").contains(bits[1])) throw ApiException.badRequest("정렬을 확인하세요.","sort");
            String field=bits[0].equals("codeCount")?"\"codeCount\"":bits[0];
            order=field+" "+bits[1]+", "+fallback;
        }
        return new AdminQuery(p.page(),p.size(),p.offset(),order,group,key,month);
    }
}
