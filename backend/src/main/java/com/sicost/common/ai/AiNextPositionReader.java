package com.sicost.common.ai;

import java.util.List;
import java.util.Map;

/** Non-persisting UI-30 engine boundary. Implementations must never call recommendation search/save. */
public interface AiNextPositionReader {
    record Criteria(List<Long> devIds,String baseYm,String fromYm,String toYm,int limit) {}
    List<Map<String,Object>> read(Criteria criteria);
}
