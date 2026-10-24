package com.sicost.partners;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sicost.common.security.Sensitive;
import java.util.Set;

public final class PartnerModels {
    private PartnerModels() {}
    public record PartnerSave(String ptnr_se_cd, String ptnr_nm, String biz_reg_no, String ceo_nm,
        String indty_cd, String chrg_nm, String chrg_tel_no, String rmrk_desc, String txinv_email_addr,
        @Sensitive String bank_cd, @Sensitive String acnt_no, @Sensitive String dpstr_nm) {}
    public record PartnerDetail(String ptnr_id, String ptnr_no, String ptnr_se_cd, String ptnr_nm,
        String biz_reg_no, String ceo_nm, String indty_cd, String chrg_nm, String chrg_tel_no, String rmrk_desc,
        @JsonInclude(JsonInclude.Include.NON_NULL) String txinv_email_addr,
        @Sensitive @JsonInclude(JsonInclude.Include.NON_NULL) String bank_cd,
        @Sensitive @JsonInclude(JsonInclude.Include.NON_NULL) String acnt_no,
        @Sensitive @JsonInclude(JsonInclude.Include.NON_NULL) String dpstr_nm, String use_yn) {
        public PartnerSave input() { return new PartnerSave(ptnr_se_cd,ptnr_nm,biz_reg_no,ceo_nm,indty_cd,chrg_nm,chrg_tel_no,rmrk_desc,txinv_email_addr,bank_cd,acnt_no,dpstr_nm); }
    }
    public record PartnerListItem(String ptnr_id, String ptnr_no, String ptnr_se_cd, String ptnr_nm,
        String biz_reg_no, String ceo_nm, String indty_cd, String chrg_nm, String chrg_tel_no,
        String txinv_email_addr, String use_yn, String kindName, String industryName,
        String paymentAccount, Long projectCount, long assignmentPeopleCount) {}
    public record PartnerWrite(long id, String no, PartnerSave input, Set<String> changed, Long actor) {}
    public record PartnerHistory(String id, String type, String column, String before, String after, Long actor) {}
    public record PartnerCode(String group, String code) {}
}
