package com.sicost.partnersops;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class PartnerActivityModels {
    private PartnerActivityModels() {}
    public record PartnerRecord(String ptnr_id, String ptnr_no, String ptnr_se_cd, String ptnr_nm,
        String biz_reg_no, String ceo_nm, String indty_cd, String chrg_nm, String chrg_tel_no,
        String rmrk_desc, String txinv_email_addr, @Sensitive String bank_cd,
        @Sensitive String acnt_no, @Sensitive String dpstr_nm, String use_yn) {
        public Map<String,Object> detail() {
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("ptnr_id",ptnr_id);data.put("ptnr_no",ptnr_no);data.put("ptnr_se_cd",ptnr_se_cd);
            data.put("ptnr_nm",ptnr_nm);data.put("biz_reg_no",biz_reg_no);data.put("ceo_nm",ceo_nm);
            data.put("indty_cd",indty_cd);data.put("chrg_nm",chrg_nm);data.put("chrg_tel_no",chrg_tel_no);
            data.put("rmrk_desc",rmrk_desc);data.put("use_yn",use_yn);
            if(!"VEND".equals(ptnr_se_cd)) data.put("txinv_email_addr",txinv_email_addr);
            if(!"CUST".equals(ptnr_se_cd)) {data.put("bank_cd",bank_cd);data.put("acnt_no",acnt_no);data.put("dpstr_nm",dpstr_nm);}
            return data;
        }
    }
    public record PartnerOption(String id,String no,String name) {}
    public record PartnerAssignment(String projectName,String developerName,String affiliation,
        String jobCode,String jobName,BigDecimal assignmentRate,LocalDate endDate) {}
    public record PartnerChange(LocalDateTime changedAt,String column,String itemName,String before,
        String after,String changeType,String actorName) {}
    public record PartnerCounts(long projectCount,long peopleCount) {}
    public record PartnerBlocked(String id,String name,long projectCount,long peopleCount,String reason) {}
    public record PartnerDisableResult(List<String> disabledIds,List<PartnerBlocked> blocked) {}
    public record PartnerStateWrite(String id,String useYn,Long actor) {}
    public record PartnerHistoryWrite(String id,String type,String before,String after,Long actor) {}
    public record PartnerDateQuery(String id,LocalDate today) {}
    public record PartnerExportQuery(String kind,String useYn,String q,LocalDate today) {}
    public record PartnerDownloadWrite(String filename,String detail,Long actor) {}
    public record PartnerDownload(String filename,byte[] bytes) {}
}
