package com.sicost.shared;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.DataScope;
import com.sicost.common.validation.CalendarValues;
import com.sicost.shared.SharedReadModels.*;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SharedReadService {
    private final SharedReadMapper mapper;

    public SharedReadService(SharedReadMapper mapper) { this.mapper = mapper; }

    public List<CodeOption> codeOptions(String group, DataScope scope) {
        if (group == null || group.isEmpty() || group.length() > 30) {
            throw ApiException.badRequest("그룹 코드는 1~30자로 입력하세요.", "group");
        }
        if (!mapper.groupExists(group)) throw ApiException.notFound("코드 그룹이 없습니다.");
        return mapper.codeOptions(group);
    }

    public List<MenuNode> menus(DataScope scope) {
        List<MenuRow> rows = mapper.menus();
        return rows.stream().filter(row -> row.parentId() == null).map(root ->
            new MenuNode(root.menuId(), root.name(), root.screenId(), root.icon(), root.mobile(),
                rows.stream().filter(child -> Objects.equals(child.parentId(), root.menuId()))
                    .map(child -> new MenuNode(child.menuId(), child.name(), child.screenId(), child.icon(), child.mobile(), List.of()))
                    .toList())).toList();
    }

    public List<MonthClose> monthClose(String from, String to, DataScope scope) {
        var start = CalendarValues.month(from, "from");
        var end = CalendarValues.month(to, "to");
        if (start.isAfter(end)) throw ApiException.badRequest("시작월은 종료월보다 늦을 수 없습니다.", "from");
        return mapper.monthClose(from, to);
    }

    public OperatingParam effectiveParam(String key, String month, DataScope scope) {
        CalendarValues.month(month, "month");
        OperatingParam row = mapper.effectiveParam(key, month);
        if (row == null) throw ApiException.notFound("해당 월에 적용되는 기준값이 없습니다.");
        return row;
    }
}
