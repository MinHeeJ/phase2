package com.si.cost.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.cost.common.api.*;
import com.si.cost.common.user.DataScope;
import com.si.cost.common.user.PermissionService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.si.cost.admin.AdminModels.*;

@Service
@Transactional(readOnly = true)
public class AdminService {
    private final AdminMapper mapper;
    private final Clock clock;
    private final ObjectMapper json;
    private final PermissionService permissions;
    public AdminService(AdminMapper mapper, Clock clock, ObjectMapper json, PermissionService permissions) {
        this.mapper = mapper; this.clock = clock; this.json = json; this.permissions = permissions;
    }
    public Page<Group> groups(PageRequest page, DataScope scope) {
        permissions.can("UI-27.read");
        return page(mapper.groups(page, scope), mapper.groupCount(), page);
    }
    public Page<Code> codes(String group, PageRequest page, DataScope scope) {
        requireGroup(group); permissions.can("UI-27.read");
        return page(mapper.codes(group, page, scope), mapper.codeCount(group), page);
    }
    public Page<ExcelMap> maps(PageRequest page, DataScope scope) {
        permissions.can("UI-27.read");
        return page(mapper.maps(page, scope), mapper.mapCount(), page);
    }
    public Page<Change> history(String group, PageRequest page, DataScope scope) {
        if (group != null) key(group, "group", 30);
        permissions.can("UI-27.read");
        return page(mapper.history(group, page, scope), mapper.historyCount(group), page);
    }
    public Page<Param> currentParams(PageRequest page, DataScope scope) {
        String month = YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString();
        permissions.can("UI-27.read");
        return page(mapper.currentParams(month, page, scope), mapper.currentParamCount(month), page);
    }
    public Page<Param> versions(String key, PageRequest page, DataScope scope) {
        source(key); permissions.can("UI-27.read");
        return page(mapper.versions(key, page, scope), mapper.versionCount(key), page);
    }
    private <T> Page<T> page(List<T> rows, long count, PageRequest page) {
        return new Page<>(rows, count, page.page(), page.size());
    }
    @Transactional
    public void createGroup(GroupCreate row) {
        permissions.can("UI-27.create");
        required(row.groupId(), 30, "cd_grp_id", "입력값을 확인하세요.");
        if (!row.groupId().matches("[A-Z_]{1,30}")) throw BusinessException.invalid("cd_grp_id");
        required(row.name(), 100, "cd_grp_nm", "입력값을 확인하세요.");
        optional(row.attr1Name(), 50, "attr1_nm"); optional(row.attr2Name(), 50, "attr2_nm"); optional(row.attr3Name(), 50, "attr3_nm");
        try { mapper.insertGroup(row); }
        catch (DuplicateKeyException failure) { throw BusinessException.invalid("cd_grp_id"); }
        mapper.audit(new Audit(row.groupId(), null, "ADD", "cd_grp_id", null, row.groupId()));
    }
    @Transactional
    public void saveCodes(String group, List<Code> rows) {
        permissions.can("UI-27.update");
        Group metadata = requireGroup(group);
        Map<String, Code> existing = new LinkedHashMap<>();
        mapper.allCodes(group).forEach(row -> existing.put(row.code(), row));
        if (rows == null) throw BusinessException.invalid("body");
        for (int i = 0; i < rows.size(); i++) validateCode(rows.get(i), i, existing);
        if ("SYSTEM".equals(metadata.kind())) {
            for (Code row : rows) {
                Code old = existing.get(row.code());
                if (old != null && !Objects.equals(old.use(), row.use()))
                    throw locked("시스템 코드는 사용 중지할 수 없습니다.");
                if (old == null || !Objects.equals(old.attr1(), row.attr1()) || !Objects.equals(old.attr2(), row.attr2())
                        || !Objects.equals(old.attr3(), row.attr3()) || !Objects.equals(old.date(), row.date()))
                    throw locked("시스템 코드는 이름과 순서만 바꿀 수 있습니다.");
            }
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) if (!seen.add(rows.get(i).code()))
            throw BusinessException.conflict("DUPLICATE_CODE", "이미 있는 코드입니다.", "[" + i + "].dtl_cd");
        if (!seen.containsAll(existing.keySet())) throw BusinessException.invalid("dtl_cd");
        for (Code row : rows) {
            Code old = existing.get(row.code());
            if (old == null) {
                permissions.can("UI-27.create");
                try { mapper.insertCode(group, row); }
                catch (DuplicateKeyException failure) { throw BusinessException.conflict("DUPLICATE_CODE", "이미 있는 코드입니다.", "dtl_cd"); }
                mapper.audit(new Audit(group, row.code(), "ADD", "dtl_cd", null, row.code()));
            } else {
                Map<String, Object> changes = differences(codeFields(old), codeFields(row));
                if (changes.isEmpty()) continue;
                mapper.updateCode(group, row.code(), changes);
                for (var change : changes.entrySet()) {
                    String type = "UPDATE";
                    if (change.getKey().equals("use_yn")) { permissions.can("UI-27.delete"); type = row.use().equals("N") ? "DISABLE" : "ENABLE"; }
                    mapper.audit(new Audit(group, row.code(), type, change.getKey(), scalar(codeFields(old).get(change.getKey())), scalar(change.getValue())));
                }
            }
        }
    }
    private void validateCode(Code row, int i, Map<String, Code> existing) {
        String prefix = "[" + i + "].";
        if (row == null) throw BusinessException.invalid("[" + i + "]");
        required(row.code(), 30, prefix + "dtl_cd", "코드를 입력하세요.");
        required(row.name(), 100, prefix + "cd_nm", "이름을 입력하세요.");
        if (!existing.containsKey(row.code()) && !row.code().matches("[A-Z0-9_]{1,30}")) throw BusinessException.invalid(prefix + "dtl_cd");
        if (row.order() == null) throw BusinessException.invalid(prefix + "sort_seq");
        if (!Set.of("Y", "N").contains(Objects.toString(row.use(), ""))) throw BusinessException.invalid(prefix + "use_yn");
        if (!existing.containsKey(row.code()) && !"Y".equals(row.use())) throw BusinessException.invalid(prefix + "use_yn");
        optional(row.attr1(), 100, prefix + "attr1_val"); optional(row.attr2(), 100, prefix + "attr2_val"); optional(row.attr3(), 100, prefix + "attr3_val");
        if (row.date() != null && (row.date().getYear() < 1 || row.date().getYear() > 9999)) throw BusinessException.invalid(prefix + "aply_bgn_dt");
    }
    @Transactional
    public void createMap(MapCreate row) {
        permissions.can("UI-27.create");
        required(row.label(), 100, "excel_lbl_nm", "엑셀 글자를 입력하세요.");
        required(row.groupId(), 30, "trns_cd_grp_id", "대응할 코드를 고르세요.");
        required(row.code(), 30, "trns_cd", "대응할 코드를 고르세요.");
        if (!row.code().matches("[A-Z0-9_]{1,30}")) throw BusinessException.invalid("trns_cd");
        role(row.role()); optional(row.memo(), 200, "rmrk_desc");
        if (!mapper.activeTarget(row.groupId(), row.code())) throw BusinessException.notFound();
        if (mapper.duplicateMap(row)) throw duplicateMap();
        long id;
        try { id = mapper.insertMap(row); } catch (DuplicateKeyException failure) { throw duplicateMap(); }
        mapper.audit(new Audit(row.groupId(), row.code(), "ADD", "map_id", null, mapAudit(id, "map_id", Long.toString(id))));
    }
    @Transactional
    public void patchMap(String mapId, Map<String, Object> requested) {
        permissions.can("UI-27.update");
        long id;
        try { id = Long.parseLong(mapId); if (id < 1) throw new NumberFormatException(); }
        catch (NumberFormatException failure) { throw BusinessException.invalid("mapId"); }
        if (requested.isEmpty()) throw BusinessException.invalid("body");
        for (String field : requested.keySet()) {
            if (!Set.of("map_role_cd", "rmrk_desc", "use_yn").contains(field) || !(requested.get(field) instanceof String)) throw BusinessException.invalid(field);
        }
        if (requested.containsKey("map_role_cd")) role((String) requested.get("map_role_cd"));
        if (requested.containsKey("rmrk_desc")) optional((String) requested.get("rmrk_desc"), 200, "rmrk_desc");
        if (requested.containsKey("use_yn") && !Set.of("Y", "N").contains(requested.get("use_yn"))) throw BusinessException.invalid("use_yn");
        ExcelMap old = mapper.excelMap(id);
        if (old == null) throw BusinessException.notFound();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("map_role_cd", old.role()); before.put("rmrk_desc", old.memo()); before.put("use_yn", old.use());
        Map<String, Object> changes = differences(before, requested);
        if (changes.isEmpty()) return;
        if (changes.containsKey("use_yn")) permissions.can("UI-27.delete");
        mapper.updateMap(id, changes);
        changes.forEach((item, value) -> mapper.audit(new Audit(old.groupId(), old.code(), "UPDATE", item,
                mapAudit(id, item, before.get(item)), mapAudit(id, item, value))));
    }
    @Transactional
    public void saveParam(String key, String month, ParamSave value) {
        permissions.can("UI-27.update");
        CalendarValues.month(month, "month");
        ParamSource source = source(key);
        String closed = mapper.lastClosed();
        if (closed != null && month.compareTo(closed) <= 0) throw BusinessException.conflict("CLOSED_MONTH", "마감된 월에는 적용할 수 없습니다.", "month");
        boolean numeric = source.number() != null;
        String field = numeric ? "num_val" : "txt_val";
        if ((!numeric && source.text() == null) || value == null || (numeric ? value.number() == null || value.text() != null : value.text() == null || value.number() != null)) throw invalidValue(field);
        if (numeric) {
            BigDecimal n = value.number().stripTrailingZeros();
            if (n.signum() < 0 || n.scale() > 4 || n.precision() - n.scale() > 14
                    || (("%".equals(source.unit()) || "%p".equals(source.unit())) && n.compareTo(BigDecimal.valueOf(100)) > 0)) throw invalidValue(field);
        } else if (value.text().isBlank() || value.text().length() > 200) throw invalidValue(field);
        Param before = mapper.paramBefore(key, month);
        Param after = new Param(key, month, source.name(), value.number(), value.text(), source.unit(), source.usage());
        if (before != null && before.month().equals(month) && sameNumber(before.number(), after.number()) && Objects.equals(before.text(), after.text())) return;
        mapper.saveParam(source, month, value);
        mapper.audit(new Audit("PARAM", key, "UPDATE", "적용 시작월", paramAudit(before), paramAudit(after)));
        if (before != null && !sameNumber(before.number(), after.number()) && !Objects.equals(before.text(), after.text()))
            mapper.audit(new Audit("PARAM", key, "UPDATE", "적용 시작월", paramAudit(before), paramAudit(after)));
    }
    private boolean sameNumber(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }
    private String paramAudit(Param value) {
        if (value == null) return null;
        Map<String, Object> payload = new LinkedHashMap<>(); payload.put("aply_bgn_ym", value.month());
        payload.put("num_val", value.number()); payload.put("txt_val", value.text()); return serialize(payload);
    }
    private String mapAudit(long id, String item, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>(); payload.put("map_id", Long.toString(id)); payload.put("item_nm", item); payload.put("value", value); return serialize(payload);
    }
    private String serialize(Object value) {
        try { String result = json.writeValueAsString(value); if (result.length() > 500) throw BusinessException.invalid("body"); return result; }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Audit serialization failed"); }
    }
    private Map<String, Object> codeFields(Code row) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("cd_nm", row.name()); result.put("sort_seq", row.order());
        result.put("use_yn", row.use()); result.put("attr1_val", row.attr1()); result.put("attr2_val", row.attr2()); result.put("attr3_val", row.attr3()); result.put("aply_bgn_dt", row.date()); return result;
    }
    private Map<String, Object> differences(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>(); after.forEach((key, value) -> { if (!Objects.equals(before.get(key), value)) changes.put(key, value); }); return changes;
    }
    private String scalar(Object value) { return value == null ? null : value.toString(); }
    private Group requireGroup(String group) { key(group, "group", 30); Group row = mapper.group(group); if (row == null) throw BusinessException.notFound(); return row; }
    private ParamSource source(String key) { key(key, "key", 50); ParamSource row = mapper.paramSource(key); if (row == null) throw BusinessException.notFound(); return row; }
    private void key(String value, String field, int max) { if (value == null || value.isBlank() || value.length() > max) throw BusinessException.invalid(field); }
    private void required(String value, int max, String field, String message) { if (value == null || value.isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, field); optional(value, max, field); }
    private void optional(String value, int max, String field) { if (value != null && value.length() > max) throw BusinessException.invalid(field); }
    private void role(String role) { if (!Set.of("MAIN", "SUB").contains(Objects.toString(role, ""))) throw BusinessException.invalid("map_role_cd"); }
    private BusinessException locked(String message) { return BusinessException.conflict("SYSTEM_CODE_LOCKED", message, "use_yn"); }
    private BusinessException duplicateMap() { return BusinessException.conflict("DUPLICATE_CODE", "이미 있는 대응입니다.", "excel_lbl_nm"); }
    private BusinessException invalidValue(String field) { return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "올바른 값을 입력하세요.", field); }
}
