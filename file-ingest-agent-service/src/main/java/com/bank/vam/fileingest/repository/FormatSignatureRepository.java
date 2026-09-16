package com.bank.vam.fileingest.repository;

import com.bank.vam.fileingest.entity.FormatSignature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FormatSignatureRepository extends JpaRepository<FormatSignature, UUID> {

    Optional<FormatSignature> findBySignatureHash(String signatureHash);
}
