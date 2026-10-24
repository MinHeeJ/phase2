package com.sicost.partnersops;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class PartnerLifecycleModels {
    private PartnerLifecycleModels() {}
    public record PartnerOption(String id, String no, String name) {}
    public record PartnerAssignment(String projectName, String developerName, String affiliation,
        String jobCode, String jobName, BigDecimal assignmentRate, LocalDate endDate) {}
    public record PartnerChange(LocalDateTime changedAt, String column, String itemName, String before,
        String after, String changeType, String actorName) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PartnerDetail(String ptnr_id, String ptnr_no, String ptnr_se_cd, String ptnr_nm,
        String biz_reg_no, String ceo_nm, String indty_cd, String chrg_nm, String chrg_tel_no,
        String rmrk_desc, String txinv_email_addr, @Sensitive String bank_cd,
        @Sensitive String acnt_no, @Sensitive String dpstr_nm, String use_yn) {}
    public record PartnerBlock(String id, String name, long projectCount, long peopleCount, String reason) {}
    public record PartnerDisableResult(List<String> disabledIds, List<PartnerBlock> blocked) {}
    public record PartnerStateWrite(String id, String use, Long actor) {}
    public record PartnerAudit(String id, String type, String before, String after, Long actor) {}
    public record PartnerDateQuery(String id, LocalDate today) {}
    public record PartnerExportQuery(String kind, String useYn, String q, List<String> ids, LocalDate today) {}
    public record PartnerAccess(String filename, String detail, Long actor) {}
    public record PartnerFile(String filename, byte[] bytes) {}
}
