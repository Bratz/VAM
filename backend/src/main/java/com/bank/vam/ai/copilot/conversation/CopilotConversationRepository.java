package com.bank.vam.ai.copilot.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CopilotConversationRepository extends JpaRepository<CopilotConversation, UUID> {

    /** Drawer history list for a user, newest-first. */
    List<CopilotConversation> findByUserIdOrderByLastMessageAtDesc(String userId);

    /** Same, scoped to a corporate (used once auth is wired). */
    List<CopilotConversation> findByCorporateIdAndUserIdOrderByLastMessageAtDesc(
            UUID corporateId, String userId);
}
