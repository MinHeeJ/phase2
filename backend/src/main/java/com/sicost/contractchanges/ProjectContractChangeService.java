package com.sicost.contractchanges;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.ProjectValues;
import java.math.BigDecimal;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.contractchanges.ProjectContractChangeModels.*;

@Service
@Transactional(readOnly = true)
public class ProjectContractChangeService {
    private final ProjectContractChangeMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ObjectMapper json;

    public ProjectContractChangeService(ProjectContractChangeMapper mapper, CurrentUser user,
            PermissionService permissions, Clock clock, ObjectMapper json) {
        this.mapper = mapper;
        this.user = user;
        this.permissions = permissions;
        this.clock = clock;
        this.json = json;
    }
    private ProjectState require(long id, boolean lock) {
        ProjectContractChangeInput.id(id, "id");
        var project = lock ? mapper.lockProject(id) : mapper.project(id);
        if (project == null) throw ApiException.notFound("프로젝트가 없습니다.");
        return project;
    }
    private ProjectState writable(long id) {
        var project = require(id, true);
        if (!"Y".equals(project.use_yn())) {
            throw ApiException.badRequest("사용 안 함으로 바꾼 프로젝트입니다.", "use_yn");
        }
        return project;
    }
    private ChangeRow require(ChangeKey key) {
        ProjectContractChangeInput.id(key.chgId(), "chgId");
        var row = mapper.row(key);
        if (row == null) throw ApiException.notFound("계약 변경이 없습니다.");
        return row;
    }
    private void editable(ChangeRow row) {
        if ("CONFIRM".equals(row.cntrct_chg_stts_cd())) {
            throw ApiException.conflict("CHANGE_CONFIRMED", "확정된 계약 변경은 수정·삭제할 수 없습니다.", null);
        }
    }
    public Changes list(long id) {
        ProjectActions.can(permissions, "listProjectContractChanges", false);
        var project = require(id, false);
        return new Changes(project.cntrct_bgn_dt(), project.init_cntrct_amt(), mapper.list(id));
    }
    @Transactional
    public ChangeRow create(long id, ChangeInput input) {
        ProjectActions.can(permissions, "createProjectContractChange", "CONFIRM".equals(input.cntrct_chg_stts_cd()));
        var project = writable(id);
        var row = proposal(project, 0, input);
        if ("CONFIRM".equals(input.cntrct_chg_stts_cd())) row = confirmed(project, row);
        Long key = mapper.insert(new ChangeWrite(id, row, user.userId()));
        var saved = require(new ChangeKey(id, key));
        audit(id, "INSERT", null, saved);
        return saved;
    }
    @Transactional
    public ChangeRow update(long id, long chgId, ChangeInput input) {
        ProjectContractChangeInput.id(chgId, "chgId");
        ProjectActions.can(permissions, "updateProjectContractChange", "CONFIRM".equals(input.cntrct_chg_stts_cd()));
        var project = writable(id);
        var key = new ChangeKey(id, chgId);
        var before = require(key);
        editable(before);
        var row = proposal(project, chgId, input);
        if ("CONFIRM".equals(input.cntrct_chg_stts_cd())) row = confirmed(project, row);
        mapper.update(new ChangeWrite(id, row, user.userId()));
        audit(id, "UPDATE", before, row);
        return require(key);
    }
    @Transactional
    public Removal delete(long id, long chgId) {
        ProjectContractChangeInput.id(chgId, "chgId");
        ProjectActions.can(permissions, "updateProjectContractChangeRemoval", false);
        writable(id);
        var key = new ChangeKey(id, chgId);
        var before = require(key);
        editable(before);
        mapper.delete(key);
        audit(id, "DELETE", before, null);
        return new Removal(id, chgId);
    }
    @Transactional
    public ChangeRow confirm(long id, long chgId) {
        ProjectContractChangeInput.id(chgId, "chgId");
        ProjectActions.can(permissions, "updateProjectContractChangeConfirmation", true);
        var project = writable(id);
        var key = new ChangeKey(id, chgId);
        var before = require(key);
        if ("CONFIRM".equals(before.cntrct_chg_stts_cd())) return before;
        var row = confirmed(project, before);
        mapper.update(new ChangeWrite(id, row, user.userId()));
        audit(id, "UPDATE", before, row);
        return require(key);
    }
    private void type(ProjectState project, String type) {
        if ("IN".equals(project.cntrct_ty_cd()) && !"TERM".equals(type)) {
            throw ApiException.badRequest("내부 프로젝트는 기간 변경만 가능합니다.", "cntrct_chg_ty_cd");
        }
    }
    private LocalDate end(ProjectState project) {
        return project.cur_end_dt() == null ? project.cntrct_end_dt() : project.cur_end_dt();
    }
    private void term(ProjectState project, LocalDate requested) {
        LocalDate start = project.prfm_bgn_dt() == null ? project.cntrct_bgn_dt() : project.prfm_bgn_dt();
        if (requested == null || requested.equals(end(project)) || (start != null && requested.isBefore(start))) {
            throw ApiException.badRequest("변경 후 종료일은 현재 종료일과 다르고 수행 시작일 이후여야 합니다.", "aft_end_dt");
        }
    }
    private ChangeRow proposal(ProjectState project, long chgId, ChangeInput input) {
        String type = input.cntrct_chg_ty_cd();
        type(project, type);
        boolean money = type.equals("ADD") || type.equals("CUT");
        Long before = money ? project.cur_cntrct_amt() : null;
        Long after = money ? amount(before, input.amount(), type) : null;
        if (type.equals("TERM")) term(project, input.aft_end_dt());
        if (input.incdec_mm_qty() != null && (!money || !"TK".equals(project.cntrct_ty_cd()))) {
            throw ProjectContractChangeInput.error("incdec_mm_qty");
        }
        return new ChangeRow(chgId, type, "PLAN", input.chg_dt(), before, after,
            type.equals("TERM") ? end(project) : null,
            type.equals("TERM") ? input.aft_end_dt() : null,
            money ? input.incdec_mm_qty() : null, type.equals("FREE") ? input.free_mm_qty() : null,
            input.rsn_desc(), "N", null, null);
    }
    private long amount(Long before, Long delta, String type) {
        if (before == null || delta == null || delta < 1) throw ProjectContractChangeInput.error("amount");
        try { return type.equals("CUT") ? Math.subtractExact(before, delta) : Math.addExact(before, delta); }
        catch (ArithmeticException ex) { throw ProjectContractChangeInput.error("amount"); }
    }
    private ChangeRow confirmed(ProjectState project, ChangeRow row) {
        String type = row.cntrct_chg_ty_cd();
        type(project, type);
        boolean money = type.equals("ADD") || type.equals("CUT");
        Long before = money ? project.cur_cntrct_amt() : null;
        Long after = null;
        BigDecimal mm = project.cntrct_mm_qty() == null ? BigDecimal.ZERO : project.cntrct_mm_qty();
        if (money) {
            long delta;
            try {
                delta = type.equals("CUT")
                    ? Math.subtractExact(row.bfr_cntrct_amt(), row.aft_cntrct_amt())
                    : Math.subtractExact(row.aft_cntrct_amt(), row.bfr_cntrct_amt());
            } catch (ArithmeticException ex) { throw ProjectContractChangeInput.error("amount"); }
            after = amount(before, delta, type);
            if (type.equals("CUT") && after <= 0) {
                throw ApiException.badRequest("감액 뒤 계약금액이 0원 이하가 됩니다.", "amount");
            }
            mm = ProjectValues.decimal(mm.add(row.incdec_mm_qty() == null ? BigDecimal.ZERO : row.incdec_mm_qty()),
                8, "incdec_mm_qty");
        }
        if (type.equals("TERM")) term(project, row.aft_end_dt());
        if (money || type.equals("TERM")) {
            mapper.current(new CurrentWrite(project.prj_id(), money ? after : project.cur_cntrct_amt(),
                type.equals("TERM") ? row.aft_end_dt() : end(project),
                money ? mm : project.cntrct_mm_qty(), user.userId()));
        }
        return new ChangeRow(row.chgId(), type, "CONFIRM", row.chg_dt(), before, after,
            type.equals("TERM") ? end(project) : null, row.aft_end_dt(), row.incdec_mm_qty(), row.free_mm_qty(),
            row.rsn_desc(), "TK".equals(project.cntrct_ty_cd()) && !type.equals("FREE") ? "Y" : "N",
            user.userId(), LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
    }
    private String snapshot(ChangeRow row) {
        if (row == null) return null;
        try { return json.writeValueAsString(row); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("계약 변경 기록을 저장할 수 없습니다.", ex); }
    }
    private void audit(long id, String type, ChangeRow before, ChangeRow after) {
        mapper.audit(new ChangeAudit(Long.toString(id), type, snapshot(before), snapshot(after), user.userId()));
    }
}
