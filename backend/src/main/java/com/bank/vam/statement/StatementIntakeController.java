package com.bank.vam.statement;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.statement.BankStatementFile.Channel;
import com.bank.vam.statement.StatementIntakeService.IntakeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * System-to-system statement intake (spec R2, R16): middleware that pushes instead of dropping files into the inbox,
 * and testing / demos. Same service as the inbox, so the same checks and effects. Not used by any screen (D7): screens
 * only read — the intake log ({@code GET /files}) and an account's statements and lines ({@code GET /lines}).
 * ponytail: open like every endpoint today (SecurityConfig permits all, the inbound ISO endpoints included); when
 * sign-in is turned on, restrict this path to service credentials (OAuth2 client credentials with an intake scope).
 */
@RestController
@RequestMapping("/api/v1/statements")
@RequiredArgsConstructor
public class StatementIntakeController {

    private static final int MAX_FILES = 200;
    private static final int ACCOUNT_STATEMENTS = 30;
    private static final int ACCOUNT_LINES = 500;

    private final StatementIntakeService intake;
    private final BankStatementFileRepository files;
    private final BankStatementRepository statements;
    private final BankStatementLineRepository lines;

    /** One file taken in and the statements read from it, in file order. */
    public record FileLog(BankStatementFile file, List<BankStatement> statements) {
    }

    /** An account's latest applied statements (newest first) and their lines; each line names its statement. */
    public record AccountStatements(List<BankStatement> statements, List<BankStatementLine> lines) {
    }

    /**
     * Intake log (R2a), newest first, read-only. ponytail: the last {@code limit} files (max 200), no paging and no
     * corporate scope (a file can carry several corporates' accounts; there is no role model yet) — page and scope it
     * when the log outgrows a screen or sign-in arrives.
     */
    @GetMapping("/files")
    public ApiResponse<List<FileLog>> files(@RequestParam(defaultValue = "50") int limit) {
        List<BankStatementFile> latest = files.findAll(PageRequest.of(0, Math.min(Math.max(limit, 1), MAX_FILES),
                Sort.by("receivedAt").descending())).getContent();
        Map<UUID, List<BankStatement>> byFile = latest.isEmpty() ? Map.of()
                : statements.findByFileIdInOrderByCreatedAtAsc(latest.stream().map(BankStatementFile::getId).toList())
                        .stream().collect(Collectors.groupingBy(BankStatement::getFileId));
        return ApiResponse.success(latest.stream().map(f -> new FileLog(f, byFile.getOrDefault(f.getId(), List.of()))).toList());
    }

    /**
     * Statement lines on the account (R11): its latest applied statements and their lines (refused and duplicate
     * statements are in the intake log). ponytail: capped at the last 30 statements / 500 lines, no paging; add paging
     * when an account carries more lines than that per month.
     */
    @GetMapping("/lines")
    public ApiResponse<AccountStatements> lines(@RequestParam UUID physicalAccountId) {
        List<BankStatement> latest = statements.findByPhysicalAccountIdAndStatusOrderByStatementDateDescCreatedAtDesc(
                physicalAccountId, BankStatement.Status.APPLIED, PageRequest.of(0, ACCOUNT_STATEMENTS));
        List<BankStatementLine> theirLines = latest.isEmpty() ? List.of()
                : lines.findByBankStatementIdInOrderByValueDateDescCreatedAtAsc(latest.stream().map(BankStatement::getId).toList(),
                        PageRequest.of(0, ACCOUNT_LINES));
        return ApiResponse.success(new AccountStatements(latest, theirLines));
    }

    /** The file as the body; its name from {@code ?fileName=} or the {@code X-File-Name} header. */
    @PostMapping(value = "/intake", consumes = {MediaType.APPLICATION_OCTET_STREAM_VALUE, MediaType.TEXT_PLAIN_VALUE,
            MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ApiResponse<IntakeResult> intake(@RequestBody byte[] content, @RequestParam(required = false) String fileName,
                                            @RequestHeader(value = "X-File-Name", required = false) String header) {
        return taken(intake.intake(content, fileName != null ? fileName : header != null ? header : "statement", Channel.API));
    }

    @PostMapping(value = "/intake", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<IntakeResult> intake(@RequestParam MultipartFile file) throws IOException {
        return taken(intake.intake(file.getBytes(), file.getOriginalFilename() != null ? file.getOriginalFilename() : "statement", Channel.API));
    }

    private static ApiResponse<IntakeResult> taken(IntakeResult r) {
        return ApiResponse.success(r, r.status() == BankStatementFile.Status.FAILED ? "File not read: " + r.error()
                : r.applied() + " applied, " + r.duplicates() + " duplicate, " + r.refused() + " refused");
    }
}
