package com.sicost.projects;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.projects.ProjectModels.*;

@Service
@Transactional(readOnly = true)
public class ProjectService {
    private final ProjectMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public ProjectService(ProjectMapper mapper, CurrentUser user, PermissionService permissions, Clock clock) {
        this.mapper = mapper; this.user = user; this.permissions = permissions; this.clock = clock;
    }
    private LocalDate today() { return ProjectValues.today(clock); }
    public ProjectPage list(Map<String,String> parameters) {
        ProjectActions.can(permissions,"listProjects",false);
        var q = ProjectQuery.of(parameters,today());
        return new ProjectPage(mapper.list(q),mapper.count(q),q.page(),q.size(),mapper.unassignedCount(q));
    }
    private ProjectRow require(long id) {
        ProjectInput.id(id);
        var row = mapper.row(new ProjectRead(id,today()));
        if (row == null) throw ApiException.notFound("프로젝트가 없습니다.");
        return row;
    }
    public ProjectDetail get(long id) {
        ProjectActions.can(permissions,"getProject",false);
        return new ProjectDetail(require(id),mapper.skills(id));
    }
    public ProjectDetail get(String identifier) {
        if (identifier.matches("[0-9]{1,19}")) {
            try { return get(Long.parseLong(identifier)); }
            catch (NumberFormatException e) { throw ApiException.badRequest("식별자를 확인하세요.","id"); }
        }
        // D17: exact canonical project number, never guessed from a numeric suffix.
        if (!identifier.matches("P-[0-9]{4,5}")) throw ApiException.badRequest("식별자를 확인하세요.","id");
        Long id = mapper.resolveNumber(identifier);
        if (id == null) throw ApiException.notFound("프로젝트가 없습니다.");
        return get(id);
    }
    private void code(String group, String value, String old, String field) {
        if (value != null && !Objects.equals(value,old) && !mapper.activeCode(new ProjectCode(group,value))) {
            throw ApiException.badRequest("사용 중인 코드를 골라 주세요.",field);
        }
    }
    private void choices(ProjectSave input, ProjectSave before) {
        code("CONTRACT_TYPE",input.cntrct_ty_cd(),before == null ? null : before.cntrct_ty_cd(),"cntrct_ty_cd");
        code("PROJECT_STATUS",input.prj_stts_cd(),before == null ? null : before.prj_stts_cd(),"prj_stts_cd");
        code("SETTLE_METHOD",input.stlm_mthd_cd(),before == null ? null : before.stlm_mthd_cd(),"stlm_mthd_cd");
        code("INDUSTRY",input.indty_cd(),before == null ? null : before.indty_cd(),"indty_cd");
        for (String skill : input.prj_skl()) {
            code("SKILL",skill,before != null && before.prj_skl().contains(skill) ? skill : null,"prj_skl");
        }
        if (input.cust_ptnr_id() != null && (before == null || !Objects.equals(input.cust_ptnr_id(),before.cust_ptnr_id()))
                && !mapper.activeCustomer(input.cust_ptnr_id())) throw ApiException.badRequest("사용 중인 고객사를 골라 주세요.","cust_ptnr_id");
        if (input.bsn_part_id() != null && (before == null || !Objects.equals(input.bsn_part_id(),before.bsn_part_id()))
                && !mapper.activePart(input.bsn_part_id())) throw ApiException.badRequest("사용 중인 영업 파트를 골라 주세요.","bsn_part_id");
        if (input.pm_dev_id() != null && (before == null || !Objects.equals(input.pm_dev_id(),before.pm_dev_id()))
                && !mapper.activeDeveloper(input.pm_dev_id())) throw ApiException.badRequest("사용 중인 개발자를 골라 주세요.","pm_dev_id");
        if (input.prev_prj_id() != null && (before == null || !Objects.equals(input.prev_prj_id(),before.prev_prj_id()))
                && !mapper.activeProject(input.prev_prj_id())) throw ApiException.badRequest("사용 중인 선행 프로젝트를 골라 주세요.","prev_prj_id");
    }
    private BigDecimal margin(ProjectSave input, ProjectSave before) {
        if (input.trgt_mrgn_rt() != null) return input.trgt_mrgn_rt();
        if (before != null && before.trgt_mrgn_rt() != null) return before.trgt_mrgn_rt();
        return ProjectValues.defaultMargin(mapper.budgetRate(YearMonth.from(today()).toString()));
    }
    @Transactional
    public ProjectDetail create(ProjectSave input) {
        ProjectActions.can(permissions,"createProject",false);
        boolean internal = input.cntrct_ty_cd().equals("IN");
        if (!internal) {
            BigDecimal margin = margin(input,null);
            input = input.financial(input.bsn_part_id(),margin,input.trgt_cst_amt() == null
                ? ProjectValues.targetCost(input.init_cntrct_amt(),margin) : input.trgt_cst_amt());
        }
        choices(input,null);
        var period = ProjectValues.period(input.cntrct_bgn_dt(),input.cntrct_end_dt(),input.prfm_bgn_dt(),null);
        var phases = ProjectPhases.initialize(period,mapper.phases(ProjectPhases.codeGroup(input.cntrct_ty_cd())));
        mapper.numberLock();
        String prefix = String.format(Locale.ROOT,"P-%02d",input.cntrct_bgn_dt().getYear() % 100);
        String number = ProjectNumbers.allocated(input.cntrct_bgn_dt(),mapper.maximumSuffix(prefix));
        long id = mapper.insert(new ProjectWrite(number,input,user.userId()));
        for (String skill : input.prj_skl()) mapper.insertSkill(new ProjectChild(id,skill));
        mapper.insertPart(new ProjectPart(id,YearMonth.from(period.start()).toString(),input.bsn_part_id(),user.userId()));
        for (var phase : phases) mapper.insertPhase(new ProjectPhase(id,phase,user.userId()));
        mapper.appendHistory(new ProjectHistory(Long.toString(id),"INSERT",null,null,input.prj_nm(),user.userId()));
        return get(id);
    }
    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return Objects.equals(a,b);
    }
    private static String value(Object v) { return v == null ? null : v.toString(); }
    @Transactional
    public ProjectDetail update(long id, ProjectSave input) {
        ProjectActions.can(permissions,"updateProject",false);
        var row = require(id);
        if (row.use_yn().equals("N")) throw ApiException.badRequest("사용 안 함 프로젝트입니다. 다시 사용 후 입력하세요.","use_yn");
        var before = row.input(mapper.skills(id));
        boolean typeChange = !input.cntrct_ty_cd().equals(row.cntrct_ty_cd());
        if (typeChange && mapper.typeLocked(id)) throw ApiException.conflict("TYPE_LOCKED","청구 단계·배분표·투입이 있어 계약유형을 바꿀 수 없습니다.","cntrct_ty_cd");
        boolean internal = input.cntrct_ty_cd().equals("IN");
        Long part = internal ? null : row.bsn_part_id();
        if (typeChange && row.cntrct_ty_cd().equals("IN")) {
            part = mapper.latestPart(id);
            if (part == null || !mapper.activePart(part)) throw ApiException.badRequest("복원할 사용 중인 영업 파트가 없습니다.","bsn_part_id");
        }
        if (!internal) {
            BigDecimal margin = margin(input,before);
            boolean recalculate = !same(input.init_cntrct_amt(),before.init_cntrct_amt()) || !same(margin,before.trgt_mrgn_rt());
            long effectiveAmount = !same(input.init_cntrct_amt(),before.init_cntrct_amt()) || row.cur_cntrct_amt() == null
                ? input.init_cntrct_amt() : row.cur_cntrct_amt();
            Long cost = input.trgt_cst_amt();
            if (cost == null || recalculate && Objects.equals(cost,before.trgt_cst_amt())) {
                cost = recalculate || before.trgt_cst_amt() == null
                    ? ProjectValues.targetCost(effectiveAmount,margin) : before.trgt_cst_amt();
            }
            input = input.financial(part,margin,cost);
        } else input = input.financial(null,null,null);
        choices(input,before);
        if (Objects.equals(input.prev_prj_id(),id)) throw ApiException.badRequest("자신을 선행 프로젝트로 고를 수 없습니다.","prev_prj_id");
        boolean amountChanged = !Objects.equals(before.init_cntrct_amt(),input.init_cntrct_amt());
        boolean endChanged = !Objects.equals(before.cntrct_end_dt(),input.cntrct_end_dt());
        boolean confirmed = mapper.confirmed(id);
        if (confirmed && amountChanged) throw ApiException.badRequest("확정 계약 변경이 있어 최초 계약금액을 고칠 수 없습니다.","init_cntrct_amt");
        if (confirmed && endChanged) throw ApiException.badRequest("확정 계약 변경이 있어 계약 종료일을 고칠 수 없습니다.","cntrct_end_dt");
        ProjectValues.period(input.cntrct_bgn_dt(),input.cntrct_end_dt(),input.prfm_bgn_dt(),endChanged ? input.cntrct_end_dt() : row.cur_end_dt());
        Map<String,Object> old = before.columns(), after = input.columns(), changes = new LinkedHashMap<>();
        after.forEach((key,v) -> { if (!same(v,old.get(key))) changes.put(key,v); });
        // Only product-owned columns derived above can become SQL identifiers.
        if (amountChanged) { old.put("cur_cntrct_amt",row.cur_cntrct_amt()); changes.put("cur_cntrct_amt",input.init_cntrct_amt()); }
        if (endChanged) { old.put("cur_end_dt",row.cur_end_dt()); changes.put("cur_end_dt",input.cntrct_end_dt()); }
        if (typeChange && internal) { old.put("cntrct_mm_qty",row.cntrct_mm_qty()); changes.put("cntrct_mm_qty",null); }
        if (typeChange && row.cntrct_ty_cd().equals("IN")) { old.put("cntrct_mm_qty",row.cntrct_mm_qty()); changes.put("cntrct_mm_qty",new BigDecimal("0.00")); }
        if (!changes.isEmpty()) {
            mapper.update(new ProjectUpdate(id,changes,user.userId()));
            for (var entry : changes.entrySet()) audit(id,entry.getKey(),old.get(entry.getKey()),entry.getValue());
        }
        if (!new TreeSet<>(before.prj_skl()).equals(new TreeSet<>(input.prj_skl()))) {
            mapper.deleteSkills(id);
            for (String skill : input.prj_skl()) mapper.insertSkill(new ProjectChild(id,skill));
            audit(id,"prj_skl",String.join(",",before.prj_skl()),String.join(",",input.prj_skl()));
        }
        return get(id);
    }
    private void audit(long id, String field, Object before, Object after) {
        mapper.appendHistory(new ProjectHistory(Long.toString(id),"UPDATE",field,value(before),value(after),user.userId()));
    }
}
