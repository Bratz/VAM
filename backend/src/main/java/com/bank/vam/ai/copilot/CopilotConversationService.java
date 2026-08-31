package com.bank.vam.ai.copilot;

import com.bank.vam.ai.copilot.conversation.CopilotConversation;
import com.bank.vam.ai.copilot.conversation.CopilotConversationRepository;
import com.bank.vam.ai.copilot.conversation.CopilotMessage;
import com.bank.vam.ai.copilot.conversation.CopilotMessageRepository;
import com.bank.vam.ai.copilot.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Transactional persistence layer for Copilot conversations and messages.
 * Split from {@link CopilotService} so {@code @Transactional} fires through
 * Spring's proxy (self-calls within the same bean would bypass it).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotConversationService {

    private final CopilotConversationRepository conversationRepo;
    private final CopilotMessageRepository messageRepo;

    /**
     * Find an existing conversation or create a new one. If
     * {@code conversationId} is supplied but unknown, we fail fast rather
     * than silently creating a fresh conversation — prevents data loss when
     * the frontend caches a stale ID.
     */
    @Transactional
    public CopilotConversation findOrCreateConversation(ChatRequest req) {
        if (req.conversationId() != null) {
            return conversationRepo.findById(req.conversationId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Conversation not found: " + req.conversationId()));
        }
        CopilotConversation conv = CopilotConversation.builder()
                .corporateId(req.corporateId())
                .userId(req.userIdOrDefault())
                .lastMessageAt(LocalDateTime.now())
                .build();
        return conversationRepo.save(conv);
    }

    /**
     * Persist the user's message and bump the conversation's
     * {@code nextSequenceNumber} + {@code lastMessageAt} in the same tx.
     */
    @Transactional
    public CopilotMessage appendUserMessage(CopilotConversation conv, String content) {
        int seq = conv.allocateSequenceNumber();
        conv.setLastMessageAt(LocalDateTime.now());
        conversationRepo.save(conv);
        return messageRepo.save(CopilotMessage.builder()
                .conversationId(conv.getId())
                .role(CopilotMessage.Role.USER)
                .content(content)
                .sequenceNumber(seq)
                .build());
    }

    /**
     * Persist the assistant reply at the end of streaming. {@code intent} and
     * {@code toolCallsJson} stay null until P3 wires them up.
     */
    @Transactional
    public CopilotMessage appendAssistantMessage(CopilotConversation conv,
                                                 String content,
                                                 String intent,
                                                 String toolCallsJson) {
        // Re-load to pick up the latest nextSequenceNumber (the user-message
        // tx already incremented it; we're in a separate tx here).
        CopilotConversation fresh = conversationRepo.findById(conv.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Conversation vanished: " + conv.getId()));
        int seq = fresh.allocateSequenceNumber();
        fresh.setLastMessageAt(LocalDateTime.now());
        conversationRepo.save(fresh);
        return messageRepo.save(CopilotMessage.builder()
                .conversationId(fresh.getId())
                .role(CopilotMessage.Role.ASSISTANT)
                .content(content)
                .intent(intent)
                .toolCalls(toolCallsJson)
                .sequenceNumber(seq)
                .build());
    }

    @Transactional(readOnly = true)
    public List<CopilotMessage> listMessages(UUID conversationId) {
        return messageRepo.findByConversationIdOrderBySequenceNumberAsc(conversationId);
    }

    @Transactional(readOnly = true)
    public List<CopilotConversation> listConversationsForUser(String userId) {
        return conversationRepo.findByUserIdOrderByLastMessageAtDesc(userId);
    }
}
