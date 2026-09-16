package com.bank.vam.repository.fileingest;

import com.bank.vam.entity.fileingest.FormatSignature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FormatSignatureRepository extends JpaRepository<FormatSignature, UUID> {
}
