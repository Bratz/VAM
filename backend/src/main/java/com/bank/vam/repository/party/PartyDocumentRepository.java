package com.bank.vam.repository.party;

import com.bank.vam.entity.party.PartyDocument;
import com.bank.vam.entity.party.PartyDocument.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface PartyDocumentRepository extends JpaRepository<PartyDocument, UUID> {

    List<PartyDocument> findByPartyId(UUID partyId);

    List<PartyDocument> findByPartyIdAndCategory(UUID partyId, DocumentCategory category);

    List<PartyDocument> findByPartyIdAndDocumentType(UUID partyId, DocumentType documentType);

    List<PartyDocument> findByPartyIdAndVerificationStatus(UUID partyId, VerificationStatus status);

    long countByPartyId(UUID partyId);

    long countByPartyIdAndVerificationStatus(UUID partyId, VerificationStatus status);

    @Query("SELECT pd FROM PartyDocument pd WHERE pd.party.id = :partyId " +
           "AND pd.expiryDate IS NOT NULL AND pd.expiryDate <= :date")
    List<PartyDocument> findExpiredDocuments(@Param("partyId") UUID partyId, @Param("date") LocalDate date);

    @Query("SELECT pd FROM PartyDocument pd WHERE pd.party.id = :partyId " +
           "AND pd.expiryDate IS NOT NULL AND pd.expiryDate <= :date " +
           "AND pd.expiryDate > :now")
    List<PartyDocument> findExpiringSoon(@Param("partyId") UUID partyId, 
                                          @Param("now") LocalDate now, 
                                          @Param("date") LocalDate date);

    @Query("SELECT pd FROM PartyDocument pd WHERE pd.party.id = :partyId " +
           "AND pd.verificationStatus = 'PENDING'")
    List<PartyDocument> findPendingVerification(@Param("partyId") UUID partyId);

    boolean existsByPartyIdAndDocumentType(UUID partyId, DocumentType documentType);
}
