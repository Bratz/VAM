package com.bank.vam.ai.copilot.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CopilotMessageRepository extends JpaRepository<CopilotMessage, UUID> {

    /** All messages in a conversation, in send order. */
    List<CopilotMessage> findByConversationIdOrderBySequenceNumberAsc(UUID conversationId);

    /**
     * The last N messages (used by the future LLM context window). Callers
     * still need to reverse the list to get chronological order.
     */
    List<CopilotMessage> findTop20ByConversationIdOrderBySequenceNumberDesc(UUID conversationId);
}
