package com.bank.vam.statement;

import com.bank.vam.statement.BankStatementFile.Channel;
import com.bank.vam.statement.StatementIntakeService.IntakeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The inbound folder the bank's integration drops statement files into (spec R2, D2): every poll, each file is taken in
 * ({@link Channel#INBOX}) one at a time and moved to {@code processed/}, or to {@code failed/} with a
 * {@code <name>.reason.txt} beside it when it could not be read. Off while {@code vam.statements.inbox} is blank; like
 * every scheduled job it also needs {@code vam.scheduling.enabled}. A file dropped again is simply taken in again — its
 * statements come back as duplicates.
 */
@Slf4j
@Component
public class StatementInbox {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final StatementIntakeService intake;
    private final String inbox;

    public StatementInbox(StatementIntakeService intake, @Value("${vam.statements.inbox:}") String inbox) {
        this.intake = intake;
        this.inbox = inbox;
    }

    @Scheduled(fixedDelayString = "${vam.statements.poll-ms:300000}")
    public void poll() {
        if (inbox == null || inbox.isBlank()) return;
        List<Path> ready;
        try (Stream<Path> listed = Files.list(Path.of(inbox))) {
            ready = listed.filter(Files::isRegularFile).filter(p -> complete(p.getFileName().toString())).sorted().toList();
        } catch (IOException e) {
            log.warn("Statement inbox {} not readable: {}", inbox, e.toString());
            return;
        }
        ready.forEach(this::take);
    }

    void take(Path file) {
        String name = file.getFileName().toString();
        String reason;
        try {
            IntakeResult r = intake.intake(Files.readAllBytes(file), name, Channel.INBOX);
            reason = r.status() == BankStatementFile.Status.FAILED ? r.error() : null;
        } catch (Exception e) {
            log.error("Statement file {} not taken in", name, e);
            reason = "not taken in: " + e;
        }
        try {
            Path moved = move(file, reason == null ? "processed" : "failed");
            if (reason != null) Files.writeString(moved.resolveSibling(moved.getFileName() + ".reason.txt"), reason);
        } catch (IOException e) {
            // Left in the inbox it is taken in again next poll; its statements then come back as duplicates.
            log.error("Statement file {} taken in but not moved out of the inbox", name, e);
        }
    }

    /** Hidden files and ones still being written (.tmp / .part) wait for the next poll. */
    private static boolean complete(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.startsWith(".") && !lower.endsWith(".tmp") && !lower.endsWith(".part");
    }

    private static Path move(Path file, String to) throws IOException {
        Path dir = Files.createDirectories(file.resolveSibling(to));
        Path target = dir.resolve(file.getFileName());
        if (Files.exists(target)) target = dir.resolve(file.getFileName() + "." + LocalDateTime.now().format(STAMP));
        return Files.move(file, target);
    }
}
