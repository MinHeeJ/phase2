package com.sicost.shared;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.DataScope;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SharedReadService {
    private final SharedReadMapper mapper;

    public SharedReadService(SharedReadMapper mapper) { this.mapper = mapper; }

    public List<MenuNode> menus(DataScope scope) {
        var rows = mapper.menus();
        return rows.stream().filter(row -> row.parentId() == null)
                .map(parent -> node(parent, rows.stream().filter(child -> parent.menuId().equals(child.parentId()))
                        .map(child -> node(child, List.of())).toList())).toList();
    }

    private MenuNode node(MenuRow row, List<MenuNode> children) {
        return new MenuNode(row.menuId(), row.name(), row.screenId(), row.icon(), row.mobile(), children);
    }

    public List<CodeOption> codes(String group, DataScope scope) {
        if (group.isEmpty() || group.length() > 30) throw ApiException.invalid("group", "그룹 식별자는 1~30자입니다.");
        if (!mapper.groupExists(group)) throw ApiException.notFound();
        return mapper.codes(group);
    }

    public List<MonthClose> months(String from, String to, DataScope scope) {
        validateMonth(from, "from");
        validateMonth(to, "to");
        if (from.compareTo(to) > 0) throw ApiException.invalid("from", "시작월은 종료월 이후일 수 없습니다.");
        return mapper.months(from, to);
    }

    public OperatingParam parameter(String key, String month, DataScope scope) {
        validateMonth(month, "month");
        var value = mapper.parameter(key, month);
        if (value == null) throw ApiException.notFound();
        return value;
    }

    private void validateMonth(String value, String field) {
        if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])") || value.startsWith("0000"))
            throw ApiException.invalid(field, "YYYY-MM 형식의 유효한 연월을 입력하세요.");
        try { YearMonth.parse(value); }
        catch (DateTimeParseException exception) { throw ApiException.invalid(field, "유효한 연월을 입력하세요."); }
    }
}
