package com.sicost.analysis;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.analysis.AnalysisModels.*;

@Mapper
public interface ConversationMapper {
    long create(ConversationWrite write);
    ConversationRow conversation(long id);
    List<ConversationRow> conversations(ConversationQuery query);
    long count(ConversationQuery query);
    int hide(MessageQuery query);
    List<MessageRow> messages(MessageQuery query);
    long messageCount(long id);
    MessageRow message(long id);
    List<MessageRow> context(long id);
    long append(MessageWrite write);
    int touch(ConversationWrite write);
    int updateTime(MessageWrite write);
    int feedback(FeedbackWrite write);
    int clearContext(LocalDateTime now);
    int cleanupMessages(LocalDateTime now);
    int cleanupConversations(LocalDateTime now);
}
