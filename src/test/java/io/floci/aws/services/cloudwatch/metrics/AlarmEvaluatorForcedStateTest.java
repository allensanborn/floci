package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** A state a client set through SetAlarmState holds until the alarm's next evaluation period. */
class AlarmEvaluatorForcedStateTest {

    private static final String REGION = "us-east-1";

    private final CloudWatchMetricsService service = new CloudWatchMetricsService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));
    @SuppressWarnings("unchecked")
    private final AlarmEvaluator evaluator = new AlarmEvaluator(service, mock(Instance.class));

    private void putAlarm(int period) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("held");
        alarm.setNamespace("ns");
        alarm.setMetricName("m");
        alarm.setStatistic("Average");
        alarm.setEvaluationPeriods(1);
        alarm.setThreshold(1.0);
        alarm.setComparisonOperator("GreaterThanThreshold");
        alarm.setPeriod(period);
        service.putMetricAlarm(alarm, REGION);
    }

    @Test
    void evaluatorLeavesAForcedStateAloneUntilItsPeriodHasPassed() {
        putAlarm(60);
        service.forceAlarmState("held", "ALARM", "forced", null, REGION);

        evaluator.evaluateAll(); // no data would make it INSUFFICIENT_DATA
        assertEquals("ALARM", service.describeAlarms(List.of("held"), null, REGION).getFirst().getStateValue());

        // the period has passed: the evaluator takes the state back
        service.describeAlarms(List.of("held"), null, REGION).getFirst().setStateForcedTimestamp(1);
        evaluator.evaluateAll();
        assertEquals("INSUFFICIENT_DATA",
                service.describeAlarms(List.of("held"), null, REGION).getFirst().getStateValue());
    }

    private MetricAlarm stored() {
        return service.describeAlarms(List.of("held"), null, REGION).getFirst();
    }

    @Test
    void theHoldIsCappedAtSixtySecondsWhateverThePeriod() {
        putAlarm(86400);
        service.forceAlarmState("held", "ALARM", "forced", null, REGION);
        long now = Instant.now().getEpochSecond();

        stored().setStateForcedTimestamp(now - 30);
        evaluator.evaluateAll();
        assertEquals("ALARM", stored().getStateValue());

        stored().setStateForcedTimestamp(now - 61);
        evaluator.evaluateAll();
        assertEquals("INSUFFICIENT_DATA", stored().getStateValue());
    }

    @Test
    void anEvaluationThatReadTheAlarmBeforeAForceDoesNotOverwriteIt() {
        putAlarm(60);
        service.forceAlarmState("held", "ALARM", "forced", null, REGION);
        service.setAlarmState("held", "OK", "evaluated", null, REGION);
        assertEquals("ALARM", stored().getStateValue());
    }

    @Test
    void putMetricAlarmHandsTheAlarmBackToEvaluation() {
        putAlarm(60);
        service.forceAlarmState("held", "ALARM", "forced", null, REGION);
        assertTrue(stored().getStateForcedTimestamp() > 0);

        putAlarm(60);
        assertEquals(0, stored().getStateForcedTimestamp());
        evaluator.evaluateAll();
        assertEquals("INSUFFICIENT_DATA", stored().getStateValue());
    }
}
