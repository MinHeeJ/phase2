package com.sicost.analysis;

import java.time.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationRetention {
    private final ConversationMapper mapper;
    private final Clock clock;
    public ConversationRetention(ConversationMapper mapper,Clock clock){this.mapper=mapper;this.clock=clock;}
    @Configuration @EnableScheduling static class Scheduling {}
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron="0 0 3 * * *",zone="Asia/Seoul")
    @Transactional
    public void sweep(){var now=LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));mapper.clearContext(now);mapper.cleanupMessages(now);mapper.cleanupConversations(now);}
}
