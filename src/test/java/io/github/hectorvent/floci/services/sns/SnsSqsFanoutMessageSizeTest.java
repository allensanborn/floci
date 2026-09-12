package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.SqsServiceFactory;
import io.github.hectorvent.floci.services.sqs.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SNS accepts a publish up to 262144 bytes and SQS accepts a message up to the same
 * 262144 bytes, so a message SNS accepted can still be too large for the queue once
 * the notification envelope is wrapped around it. These tests pin which side of that
 * boundary each case lands on, because the delivery failure is logged rather than
 * surfaced to the publisher.
 */
class SnsSqsFanoutMessageSizeTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String BASE_URL = "http://localhost:4566";
    private static final int MAX_PUBLISH_SIZE = 262_144;

    private SnsService snsService;
    private SqsService sqsService;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        sqsService = SqsServiceFactory.createInMemory(BASE_URL, regionResolver);
        snsService = new SnsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                regionResolver, sqsService, null);
    }

    @Test
    void publishAtTheSizeLimit_withRawDelivery_reachesTheQueue() {
        String queueUrl = subscribeQueue("raw-size-queue", Map.of("RawMessageDelivery", "true"));
        String message = "x".repeat(MAX_PUBLISH_SIZE);

        assertNotNull(snsService.publish(topicArn(), null, null, message, null, null, REGION));

        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size(),
                "A raw delivery at exactly the limit is the same size on both sides");
        assertEquals(MAX_PUBLISH_SIZE, messages.get(0).getBody().length());
    }

    @Test
    void publishAtTheSizeLimit_withoutRawDelivery_isDroppedByTheQueueSizeLimit() {
        String queueUrl = subscribeQueue("enveloped-size-queue", Map.of());
        String message = "x".repeat(MAX_PUBLISH_SIZE);

        assertNotNull(snsService.publish(topicArn(), null, null, message, null, null, REGION),
                "Publish still succeeds: the delivery failure is not reported to the publisher");

        assertTrue(sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION).isEmpty(),
                "The notification envelope pushes the body past the queue's MaximumMessageSize, "
                        + "and SQS rejects it the way real AWS does");
    }

    @Test
    void publishBelowTheEnvelopeOverhead_withoutRawDelivery_reachesTheQueue() {
        String queueUrl = subscribeQueue("enveloped-small-queue", Map.of());
        String message = "x".repeat(200_000);

        assertNotNull(snsService.publish(topicArn(), null, null, message, null, null, REGION));

        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size(),
                "Only the top of the range is affected; ordinary enveloped delivery is unchanged");
        assertTrue(messages.get(0).getBody().contains(message));
    }

    private String topicArn() {
        return "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":size-topic";
    }

    private String subscribeQueue(String queueName, Map<String, String> subscriptionAttributes) {
        sqsService.createQueue(queueName, null, REGION);
        snsService.createTopic("size-topic", null, null, REGION);
        snsService.subscribe(topicArn(), "sqs",
                "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + queueName,
                REGION, subscriptionAttributes);
        return BASE_URL + "/" + ACCOUNT + "/" + queueName;
    }
}
