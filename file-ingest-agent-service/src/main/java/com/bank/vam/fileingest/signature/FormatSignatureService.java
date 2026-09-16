package com.bank.vam.fileingest.signature;

import com.bank.vam.fileingest.agent.FileStructureProfile;
import com.bank.vam.fileingest.entity.FormatSignature;
import com.bank.vam.fileingest.entity.IngestDomain;
import com.bank.vam.fileingest.repository.FormatSignatureRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The transform-reuse cache (design doc decision 4). Build order step 4 only writes to this
 * (recording the transform a first-sighting agent run produced); step 5 adds the lookup that lets
 * a later upload of the same shape skip the agent.
 */
@Component
public class FormatSignatureService {

    private final FormatSignatureRepository repository;

    public FormatSignatureService(FormatSignatureRepository repository) {
        this.repository = repository;
    }

    public Optional<FormatSignature> lookup(String customerId, IngestDomain domain, FileStructureProfile profile) {
        return repository.findBySignatureHash(hash(customerId, domain, profile));
    }

    /** The transform-handlers package a signature's generated code lives in — derived from the
     * hash itself (not stored separately) so a cache hit can reconstruct the class to invoke
     * without any new column. "sig" prefix avoids a package name starting with a digit. */
    public String packageNameFor(String customerId, IngestDomain domain, FileStructureProfile profile) {
        return packageNameFromHash(hash(customerId, domain, profile));
    }

    public String packageNameFromHash(String signatureHash) {
        return "sig" + signatureHash.substring(0, 16);
    }

    public FormatSignature recordTransform(String customerId, IngestDomain domain, FileStructureProfile profile, String transformRef) {
        String hash = hash(customerId, domain, profile);
        FormatSignature signature = repository.findBySignatureHash(hash).orElseGet(FormatSignature::new);
        signature.setSignatureHash(hash);
        signature.setCustomerId(customerId);
        signature.setDomain(domain);
        signature.setTransformRef(transformRef);
        return repository.save(signature);
    }

    /** SHA-256(customerId | domain | normalized-column-headers | delimiter) — see the design doc. */
    String hash(String customerId, IngestDomain domain, FileStructureProfile profile) {
        String normalizedColumns = profile.columns().stream()
                .map(c -> c.strip().toLowerCase())
                .collect(Collectors.joining(","));
        String raw = customerId + "|" + domain + "|" + normalizedColumns + "|" + profile.delimiter();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }
}
