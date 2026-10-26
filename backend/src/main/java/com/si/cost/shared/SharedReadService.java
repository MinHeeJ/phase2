package com.si.cost.shared;

import com.si.cost.common.api.BusinessException;
import com.si.cost.common.api.CalendarValues;
import com.si.cost.common.user.DataScope;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.si.cost.shared.SharedReadModels.*;

@Service
@Transactional(readOnly = true)
public class SharedReadService {
    private final SharedReadMapper mapper;

    public SharedReadService(SharedReadMapper mapper) { this.mapper = mapper; }

    public List<MenuNode> menus(DataScope scope) {
        var rows = mapper.menus(scope);
        return rows.stream().filter(row -> row.parentId() == null)
                .map(root -> new MenuNode(root.menuId(), root.name(), root.screenId(), root.icon(), root.mobile(),
                        rows.stream().filter(child -> Long.valueOf(root.menuId()).equals(child.parentId()))
                                .map(child -> new MenuNode(child.menuId(), child.name(), child.screenId(), child.icon(),
                                        child.mobile(), List.of())).toList())).toList();
    }

    public List<CodeOption> codeOptions(String group, DataScope scope) {
        if (group == null || group.isBlank() || group.length() > 30) throw BusinessException.invalid("group");
        if (!mapper.groupExists(group)) throw BusinessException.notFound();
        return mapper.codeOptions(group, scope);
    }

    public List<MonthClose> monthClose(String from, String to, DataScope scope) {
        YearMonth start = CalendarValues.month(from, "from");
        YearMonth end = CalendarValues.month(to, "to");
        if (start.isAfter(end)) throw BusinessException.invalid("to");
        Map<String, MonthClose> stored = mapper.monthClose(from, to, scope).stream()
                .collect(Collectors.toMap(MonthClose::month, Function.identity()));
        var result = new ArrayList<MonthClose>();
        for (YearMonth month = start; !month.isAfter(end); month = month.plusMonths(1)) {
            String key = month.toString();
            result.add(stored.getOrDefault(key, new MonthClose(key, "OPEN")));
        }
        return result;
    }

    public EffectiveParam effectiveParam(String key, String month, DataScope scope) {
        CalendarValues.month(month, "month");
        if (key == null || key.isBlank()) throw BusinessException.invalid("key");
        var value = mapper.effectiveParam(key, month, scope);
        if (value == null) throw BusinessException.notFound();
        return value;
    }
}
