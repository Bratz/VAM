package com.bank.vam.fileingest.repository;

import com.bank.vam.fileingest.entity.IngestJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IngestJobRepository extends JpaRepository<IngestJob, UUID> {
}
