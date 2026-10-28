package com.sicost.billingsops;

import com.sicost.common.api.DeveloperPaging;
import com.sicost.common.validation.BillingValues;
import java.time.*;
import java.util.*;
import java.util.function.Function;

final class BillingQuery {
    private BillingQuery() {}
    static int year(Integer year, Clock clock) {
        int selected = year == null ? LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).getYear() : year;
        if (selected < 1900 || selected > 9999) throw BillingValues.invalid("year");
        return selected;
    }
    static <T> List<T> page(List<T> rows, DeveloperPaging paging) {
        long start = (long)paging.page() * paging.size();
        int from = (int)Math.min(rows.size(), start);
        int to = (int)Math.min(rows.size(), start + paging.size());
        return List.copyOf(rows.subList(from, to));
    }
    static <T> List<T> sort(List<T> rows, String sort,
            Map<String, Function<T, ? extends Comparable<?>>> fields, Function<T, Long> id) {
        String[] parts = sort.split(",");
        Function<T, ? extends Comparable<?>> field = fields.get(parts[0]);
        if (field == null) throw BillingValues.invalid("sort");
        Comparator<T> comparator = (a,b) -> compare(field.apply(a), field.apply(b));
        if (parts.length == 2 && parts[1].equals("desc")) comparator = comparator.reversed();
        return rows.stream().sorted(comparator.thenComparing(id)).toList();
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Comparable a, Comparable b) {
        if (a == b) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return a.compareTo(b);
    }
    static Set<String> sorts(String... fields) {
        Set<String> allowed = new HashSet<>();
        for (String field : fields) {
            allowed.add(field + ",asc");
            allowed.add(field + ",desc");
        }
        return allowed;
    }
    static boolean search(String q, String... values) {
        if (q == null || q.isBlank()) return true;
        if (q.length() > 200) throw BillingValues.invalid("q");
        return Arrays.stream(values).filter(Objects::nonNull)
            .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)));
    }
}
