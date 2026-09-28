package com.bank.vam.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * vam.scheduling.enabled=false must actually stop the @Scheduled jobs. The thing that
 * silently defeats it is re-adding @EnableScheduling somewhere ungated (it used to sit on
 * AsyncConfig and on the application class), so this asserts on the post-processor that
 * annotation registers — the one bean whose absence means no job can fire.
 */
class SchedulingConfigTest {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class);

    @Test
    void schedulingIsOnByDefault() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void schedulingCanBeTurnedOff() {
        runner.withPropertyValues("vam.scheduling.enabled=false")
            .run(ctx -> assertThat(ctx).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }
}
