package com.bank.vam.repository.fileingest;

import com.bank.vam.entity.fileingest.IngestJob;
import com.bank.vam.entity.fileingest.IngestStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IngestJobRepository extends JpaRepository<IngestJob, UUID> {

    List<IngestJob> findByStage(IngestStage stage);
}
