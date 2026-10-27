package com.sicost.common.persistence;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AssignmentConcurrencyMapper {
    record AssignmentLock(long asgnId, LocalDateTime updatedAt, LocalDateTime transactionAt) {}
    record AssignmentTouch(long asgnId, LocalDateTime expected, Long actor) {}
    AssignmentLock lockAssignment(long id);
    Long lockProject(long id);
    LocalDateTime advance(AssignmentTouch touch);
}
