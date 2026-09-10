package com.bank.vam.defectfix.orchestrate;

import com.bank.vam.defectfix.agent.CodingAgentClient.ToolCallRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TicketTrajectoryTest {

    @Test
    void serializesAndRoundTripsIncludingNestedToolCalls() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TicketTrajectory trajectory = new TicketTrajectory(
                "KAN-9", "BACKEND", "backend-test|com.example.FooTest#bar",
                "test/some-branch", "2026-09-10T18:23:29Z",
                List.of(new TicketTrajectory.AttemptRecord(1, true, "Fixed it",
                        List.of(new ToolCallRecord(1, "read_file", "backend/src/Foo.java", "class Foo {}")),
                        true, "Target defect no longer present.")),
                "success", "https://github.com/Bratz/VAM/pull/8", null, "2026-09-10T18:34:34Z");

        String json = mapper.writeValueAsString(trajectory);
        TicketTrajectory parsed = mapper.readValue(json, TicketTrajectory.class);

        assertThat(parsed).isEqualTo(trajectory);
        assertThat(parsed.attempts().get(0).toolCalls().get(0).tool()).isEqualTo("read_file");
    }
}
