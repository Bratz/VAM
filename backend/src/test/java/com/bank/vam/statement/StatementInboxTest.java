package com.bank.vam.statement;

import com.bank.vam.statement.BankStatementFile.Channel;
import com.bank.vam.statement.StatementIntakeService.IntakeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Spec R2: taken-in files to processed/, unreadable ones to failed/ with the reason; partial files wait. */
class StatementInboxTest {

    @TempDir
    Path inbox;

    private final StatementIntakeService intake = mock(StatementIntakeService.class);

    @Test
    void validToProcessed_garbageToFailedWithReason_partialAndHiddenLeft_clashGetsSuffix() throws Exception {
        when(intake.intake(any(), any(), eq(Channel.INBOX))).thenAnswer(i -> {
            boolean garbage = new String((byte[]) i.getArgument(0)).equals("garbage");
            return new IntakeResult(UUID.randomUUID(), i.getArgument(1),
                    garbage ? BankStatementFile.Status.FAILED : BankStatementFile.Status.PROCESSED,
                    garbage ? "not a statement" : null, garbage ? 0 : 1, 0, 0, List.of());
        });
        Files.writeString(inbox.resolve("a.sta"), ":20:X");
        Files.writeString(inbox.resolve("junk.txt"), "garbage");
        Files.writeString(inbox.resolve("b.sta.part"), ":20:half");
        Files.writeString(inbox.resolve(".hidden"), ":20:");
        Files.createDirectories(inbox.resolve("processed"));
        Files.writeString(inbox.resolve("processed/a.sta"), "taken earlier");

        new StatementInbox(intake, inbox.toString()).poll();

        assertEquals("taken earlier", Files.readString(inbox.resolve("processed/a.sta")));
        try (var processed = Files.list(inbox.resolve("processed"))) {
            assertEquals(1, processed.filter(p -> p.getFileName().toString().startsWith("a.sta.")).count());   // timestamp suffix
        }
        assertTrue(Files.exists(inbox.resolve("failed/junk.txt")));
        assertEquals("not a statement", Files.readString(inbox.resolve("failed/junk.txt.reason.txt")));
        assertTrue(Files.exists(inbox.resolve("b.sta.part")) && Files.exists(inbox.resolve(".hidden")));
        assertFalse(Files.exists(inbox.resolve("a.sta")) || Files.exists(inbox.resolve("junk.txt")));
        verify(intake, times(2)).intake(any(), any(), eq(Channel.INBOX));
    }

    @Test
    void blankInbox_off() {
        new StatementInbox(intake, " ").poll();
        verifyNoInteractions(intake);
    }
}
