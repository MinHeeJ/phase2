package com.sicost;

import com.sicost.Access.DataScope;
import com.sicost.persistence.CostMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CostService {
    private final CostMapper db;
    private final Access.PermissionService permissions;
    private static final Set<String> CODE_FIELDS = Set.of("dtl_cd", "cd_nm", "sort_seq", "use_yn", "attr1_val", "attr2_val", "attr3_val", "aply_bgn_dt");
    private static final Set<String> MAP_FIELDS = Set.of("map_role_cd", "rmrk_desc", "use_yn");
    public CostService(CostMapper db, Access.PermissionService permissions) { this.db = db; this.permissions = permissions; }
    public List<Map<String, Object>> menus(DataScope scope) {
        List<Map<String, Object>> rows = db.menus();
        Map<Object, Map<String, Object>> nodes = new LinkedHashMap<>();
        Map<Object, Object> parents = new HashMap<>();
        for (var row : rows) {
            var node = new LinkedHashMap<>(row);
            parents.put(node.get("menuId"), node.remove("parentId"));
            node.put("children", new ArrayList<Map<String, Object>>()); nodes.put(node.get("menuId"), node);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (var node : nodes.values()) {
            var parent = nodes.get(parents.get(node.get("menuId")));
            if (parent == null) roots.add(node);
            else children(parent).add(node);
        }
        return roots;
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> children(Map<String, Object> node) { return (List<Map<String, Object>>) node.get("children"); }
    public List<Map<String, Object>> months(String from, String to, DataScope scope) {
        Input.month(from, "from"); Input.month(to, "to");
        if (from.compareTo(to) > 0) throw ApiException.invalid("to", "기간을 확인하세요.");
        return db.months(from, to);
    }
    public List<Map<String, Object>> groups(DataScope scope) { permissions.can("UI-27.read"); return db.groups(); }
    public Map<String, Object> group(String group) {
        Input.key(group, 30, "group");
        var row = db.group(group); if (row == null || row.isEmpty()) throw ApiException.missing(); return row;
    }
    public List<Map<String, Object>> codes(String group, DataScope scope) { group(group); return db.codes(group); }
    public List<Map<String, Object>> options(String group, DataScope scope) { group(group); return db.options(group); }
    @Transactional
    public void createGroup(Map<String, Object> row) {
        permissions.can("UI-27.create");
        Input.fields(row, Set.of("cd_grp_id", "cd_grp_nm", "attr1_nm", "attr2_nm", "attr3_nm"));
        String id = Input.text(row, "cd_grp_id", 30, "그룹 코드를 입력하세요.");
        if (!id.matches("[A-Z_]{1,30}")) throw ApiException.invalid("cd_grp_id", "영문 대문자와 _만 입력하세요.");
        Input.text(row, "cd_grp_nm", 100, "그룹명을 입력하세요.");
        for (String field : List.of("attr1_nm", "attr2_nm", "attr3_nm")) Input.text(row, field, 50, null);
        try { db.insertGroup(command(row)); } catch (DuplicateKeyException e) { throw ApiException.invalid("cd_grp_id", "이미 있는 그룹입니다."); }
        audit(id, null, "ADD", "cd_grp_nm", null, row.get("cd_grp_nm"));
    }
    @Transactional
    public void saveCodes(String group, List<Map<String, Object>> rows) {
        permissions.can("UI-27.update");
        boolean system = "SYSTEM".equals(group(group).get("cd_grp_se_cd"));
        if (rows == null) throw ApiException.invalid("body", "코드 목록을 입력하세요.");
        Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
        for (var row : db.codes(group)) existing.put((String) row.get("dtl_cd"), row);
        Set<String> seen = new HashSet<>();
        for (var row : rows) {
            Input.fields(row, CODE_FIELDS);
            String code = Input.text(row, "dtl_cd", 30, "코드를 입력하세요.");
            if (!seen.add(code)) throw new ApiException(409, "DUPLICATE_CODE", "dtl_cd", "이미 있는 코드입니다.");
            Input.text(row, "cd_nm", 100, "이름을 입력하세요.");
            for (String field : List.of("attr1_val", "attr2_val", "attr3_val")) Input.text(row, field, 100, null);
            Input.choice(row, "use_yn", Set.of("Y", "N")); Input.date(row);
            Object sort = row.get("sort_seq");
            if (!(sort instanceof Number) || !sort.toString().matches("-?[0-9]+") || ((Number) sort).longValue() > Integer.MAX_VALUE || ((Number) sort).longValue() < Integer.MIN_VALUE) throw ApiException.invalid("sort_seq", "정수를 입력하세요.");
            var old = existing.get(code);
            if (old == null) {
                if (system) locked(false);
                if (!code.matches("[A-Z0-9_]{1,30}")) throw ApiException.invalid("dtl_cd", "영문 대문자·숫자·_만 입력하세요.");
                if (!"Y".equals(row.get("use_yn"))) throw ApiException.invalid("use_yn", "새 코드는 사용 상태여야 합니다.");
            } else if (system) {
                if (!Objects.equals(old.get("use_yn"), row.get("use_yn"))) locked(true);
                for (String key : List.of("attr1_val", "attr2_val", "attr3_val", "aply_bgn_dt")) if (!Objects.equals(old.get(key), row.get(key))) locked(false);
            }
        }
        if (!seen.containsAll(existing.keySet())) throw ApiException.invalid("dtl_cd", "기존 코드를 빠짐없이 보내세요.");
        for (var row : rows) {
            String code = (String) row.get("dtl_cd"); var old = existing.get(code);
            var command = command(row); command.put("cd_grp_id", group);
            if (old == null) {
                permissions.can("UI-27.create"); db.insertCode(command); audit(group, code, "ADD", "dtl_cd", null, code);
            } else {
                Map<String, Object> changed = new LinkedHashMap<>();
                for (String field : CODE_FIELDS) if (!field.equals("dtl_cd") && !Objects.equals(old.get(field), row.get(field))) changed.put(field, row.get(field));
                if (!changed.isEmpty()) {
                    command.put("changes", changed); db.updateCode(command);
                    for (var entry : changed.entrySet()) {
                        String type = "UPDATE";
                        if (entry.getKey().equals("use_yn")) { permissions.can("UI-27.delete"); type = "Y".equals(entry.getValue()) ? "ENABLE" : "DISABLE"; }
                        audit(group, code, type, entry.getKey(), old.get(entry.getKey()), entry.getValue());
                    }
                }
            }
        }
    }
    private void locked(boolean disable) {
        throw new ApiException(409, "SYSTEM_CODE_LOCKED", disable ? "use_yn" : "body", disable ? "시스템 코드는 사용 중지할 수 없습니다." : "시스템 코드는 이름과 순서만 바꿀 수 있습니다.");
    }
    public List<Map<String, Object>> maps(DataScope scope) { return db.maps(); }
    @Transactional
    public void createMap(Map<String, Object> row) {
        permissions.can("UI-27.create");
        Input.fields(row, Set.of("excel_lbl_nm", "trns_cd_grp_id", "trns_cd", "map_role_cd", "rmrk_desc"));
        Input.text(row, "excel_lbl_nm", 100, "엑셀 글자를 입력하세요.");
        String group = Input.text(row, "trns_cd_grp_id", 30, "대응할 코드를 고르세요.");
        String code = Input.text(row, "trns_cd", 30, "대응할 코드를 고르세요.");
        Input.choice(row, "map_role_cd", Set.of("MAIN", "SUB")); Input.text(row, "rmrk_desc", 200, null);
        group(group);
        if (db.options(group).stream().noneMatch(c -> code.equals(c.get("code")))) throw ApiException.invalid("trns_cd", "대응할 코드를 고르세요.");
        var command = command(row);
        try { db.insertMap(command); } catch (DuplicateKeyException e) { throw new ApiException(409, "DUPLICATE_CODE", "excel_lbl_nm", "이미 있는 대응입니다."); }
        audit(group, code, "ADD", "excel_lbl_nm", null, row.get("excel_lbl_nm"));
    }
    @Transactional
    public void patchMap(String mapId, Map<String, Object> row) {
        permissions.can("UI-27.update");
        if (!mapId.matches("[0-9]{1,18}")) throw ApiException.invalid("mapId", "대상을 확인하세요.");
        Input.fields(row, MAP_FIELDS);
        if (row.isEmpty()) throw ApiException.invalid("body", "변경할 항목을 입력하세요.");
        if (row.containsKey("map_role_cd")) Input.choice(row, "map_role_cd", Set.of("MAIN", "SUB"));
        if (row.containsKey("use_yn")) Input.choice(row, "use_yn", Set.of("Y", "N"));
        Input.text(row, "rmrk_desc", 200, null);
        var old = db.excelMap(mapId); if (old == null || old.isEmpty()) throw ApiException.missing();
        Map<String, Object> changed = new LinkedHashMap<>();
        for (String field : row.keySet()) if (!Objects.equals(old.get(field), row.get(field))) changed.put(field, row.get(field));
        if (changed.isEmpty()) return;
        db.updateMap(Map.of("map_id", mapId, "changes", changed));
        for (String field : changed.keySet()) {
            String type = field.equals("use_yn") ? ("Y".equals(row.get(field)) ? "ENABLE" : "DISABLE") : "UPDATE";
            if (field.equals("use_yn")) permissions.can("UI-27.delete");
            audit((String) old.get("trns_cd_grp_id"), (String) old.get("trns_cd"), type, field, old.get(field), row.get(field));
        }
    }
    public List<Map<String, Object>> history(String group, DataScope scope) {
        if (group != null && !group.isEmpty()) Input.key(group, 30, "group");
        return db.history(group);
    }
    public List<Map<String, Object>> currentParams(DataScope scope) { return db.currentParams(YearMonth.now(ZoneId.of("Asia/Seoul")).toString()); }
    public List<Map<String, Object>> versions(String key, DataScope scope) {
        Input.key(key, 50, "key"); var rows = db.versions(key); if (rows.isEmpty()) throw ApiException.missing(); return rows;
    }
    public Map<String, Object> effective(String key, String month) {
        Input.key(key, 50, "key"); Input.month(month, "month"); var row = db.effective(key, month); if (row == null || row.isEmpty()) throw ApiException.missing(); return row;
    }
    @Transactional
    public void saveParam(String key, String month, Map<String, Object> row) {
        permissions.can("UI-27.update"); Input.month(month, "month"); Input.fields(row, Set.of("num_val", "txt_val"));
        var versions = versions(key, DataScope.ALL); var source = versions.get(0);
        String closed = db.lastClosedMonth();
        if (closed != null && month.compareTo(closed) <= 0) throw new ApiException(409, "CLOSED_MONTH", "month", "마감된 월에는 적용할 수 없습니다.");
        var command = command(source); command.put("aply_bgn_ym", month);
        if (source.get("num_val") != null) {
            if (!(row.get("num_val") instanceof Number) || row.containsKey("txt_val")) throw ApiException.invalid("num_val", "올바른 값을 입력하세요.");
            BigDecimal value = new BigDecimal(row.get("num_val").toString());
            boolean percent = Set.of("%", "%p").contains(Objects.toString(source.get("unit_nm"), ""));
            if (value.signum() < 0 || value.scale() > 4 || value.precision() - value.scale() > 14 || (percent && value.compareTo(new BigDecimal("100")) > 0)) throw ApiException.invalid("num_val", "올바른 값을 입력하세요.");
            command.put("num_val", value); command.put("txt_val", null);
        } else {
            if (row.containsKey("num_val")) throw ApiException.invalid("txt_val", "올바른 값을 입력하세요.");
            command.put("txt_val", Input.text(row, "txt_val", 200, "올바른 값을 입력하세요.")); command.put("num_val", null);
        }
        var before = versions.stream().filter(v -> month.equals(v.get("aply_bgn_ym"))).findFirst().orElse(source);
        db.saveParam(command);
        audit("PARAM", key, "UPDATE", "적용 시작월", before.get("aply_bgn_ym"), month);
        String valueField = source.get("num_val") != null ? "num_val" : "txt_val";
        audit("PARAM", key, "UPDATE", valueField, before.get(valueField), command.get(valueField));
    }
    private void audit(String group, String code, String type, String field, Object before, Object after) {
        Map<String, Object> event = command(Map.of());
        event.put("cd_grp_id", group); event.put("dtl_cd", code); event.put("cd_chg_ty_cd", type); event.put("item_nm", field);
        event.put("bfr_val", before == null ? null : before.toString()); event.put("aft_val", after == null ? null : after.toString());
        db.appendHistory(event);
    }
    private Map<String, Object> command(Map<String, Object> values) {
        Map<String, Object> command = new LinkedHashMap<>(values);
        command.put("actor", Access.CurrentUser.current().userId());
        return command;
    }
}
