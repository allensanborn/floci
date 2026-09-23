package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A FAILING specification for floci-io/floci#4194, written to be deleted.
 *
 * <p>This class is a fixture on a fixture branch, not a contribution. It states the part of
 * the dead-letter-queue contract that can be pinned down without a container, so an
 * implementer has something executable to work against rather than prose. Delete it once the
 * real tests exist; do not merge it as-is.
 *
 * <p>It is {@code @Disabled} so that checking the branch out does not break a build. Remove
 * the annotation to see it fail, which it will until the behaviour exists.
 *
 * <p><b>What is already done for you.</b> The data is all stored and round-trips correctly
 * today; only the behaviour is missing.
 * <ul>
 *   <li>{@code LambdaFunction.deadLetterTargetArn} is parsed from {@code DeadLetterConfig}
 *       on both CreateFunction and UpdateFunctionConfiguration
 *       ({@code LambdaService.java:444}) and returned by the read APIs.</li>
 *   <li>{@code FunctionEventInvokeConfig.maximumRetryAttempts} and
 *       {@code maximumEventAgeInSeconds} are stored by PutFunctionEventInvokeConfig.</li>
 * </ul>
 * So no model or parsing work is needed. The gap is entirely in what happens after a failed
 * asynchronous invocation.
 *
 * <p><b>What this file deliberately does NOT assert.</b> The DLQ <em>message shape</em>. AWS
 * sends the original event payload as the body, with {@code RequestID}, {@code ErrorCode} and
 * {@code ErrorMessage} message attributes, and I have not verified Floci's attribute plumbing
 * against a real account. Asserting a guess here would send an implementer chasing my
 * assumption instead of AWS's behaviour. Check it and tighten this, rather than trusting it:
 * https://docs.aws.amazon.com/lambda/latest/dg/invocation-async.html#invocation-dlq
 *
 * <p><b>The retry half is not specified here at all</b>, because it does not live in this
 * class. See the issue comment for where it does, and why DLQ without retries fires at the
 * wrong time.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Disabled("Specification for #4194: remove this annotation to see the gap, delete the class once implemented")
class LambdaDeadLetterQueueSpecTest {

    private static final String FUNCTION_ARN =
            "arn:aws:lambda:us-east-1:000000000000:function:dlq-probe-fn";
    private static final String DLQ_ARN = "arn:aws:sqs:us-east-1:000000000000:dlq-probe";
    private static final String DLQ_URL = "http://localhost:4566/000000000000/dlq-probe";
    private static final String TOPIC_DLQ_ARN = "arn:aws:sns:us-east-1:000000000000:dlq-topic";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock LambdaService lambdaService;
    @Mock EventBridgeService eventBridgeService;
    @Mock SqsService sqsService;
    @Mock SnsService snsService;
    @Mock EmulatorConfig config;

    private AsyncInvokeDestinationRouter router;
    private LambdaFunction fn;

    @BeforeEach
    void setUp() {
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        when(eventBridgeService.putEvents(any(), any(), any()))
                .thenReturn(new EventBridgeService.PutEventsResult(0, List.of()));
        // No event invoke config at all: a DeadLetterConfig is a field on the FUNCTION, and is
        // independent of PutFunctionEventInvokeConfig. A function can have one and no other.
        when(lambdaService.findEventInvokeConfig(any())).thenReturn(Optional.empty());

        router = new AsyncInvokeDestinationRouter(instanceOf(lambdaService), instanceOf(eventBridgeService),
                instanceOf(sqsService), instanceOf(snsService), MAPPER, config);

        fn = new LambdaFunction();
        fn.setFunctionName("dlq-probe-fn");
        fn.setFunctionArn(FUNCTION_ARN);
        fn.setAccountId("000000000000");
    }

    @Test
    void aFailedAsyncInvocation_sendsTheEventToTheFunctionsDeadLetterQueue() {
        fn.setDeadLetterTargetArn(DLQ_ARN);

        router.route(fn, request(), failure("Unhandled", "{\"errorMessage\":\"always fails\"}"), 0);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(eq(DLQ_URL), body.capture(), anyInt(), eq("us-east-1"));
        // AWS puts the ORIGINAL event on the DLQ, not the destination-style result record.
        assertThat(body.getValue(), containsString("\"amount\":100000"));
    }

    @Test
    void aSucceedingAsyncInvocation_sendsNothingToTheDeadLetterQueue() {
        fn.setDeadLetterTargetArn(DLQ_ARN);

        router.route(fn, request(), success("{\"ok\":true}"), 0);

        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void aDeadLetterTargetMayAlsoBeAnSnsTopic() {
        fn.setDeadLetterTargetArn(TOPIC_DLQ_ARN);

        router.route(fn, request(), failure("Unhandled", "{\"errorMessage\":\"always fails\"}"), 0);

        verify(snsService).publish(eq(TOPIC_DLQ_ARN), eq(null), anyString(), anyString(), eq("us-east-1"));
    }

    private static byte[] request() {
        return "{\"probe\":1,\"amount\":100000}".getBytes();
    }

    private static InvokeResult success(String payload) {
        return new InvokeResult(200, null, payload.getBytes(), null, "req-1");
    }

    private static InvokeResult failure(String functionError, String payload) {
        return new InvokeResult(200, functionError, payload.getBytes(), null, "req-1");
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instanceOf(T bean) {
        Instance<T> instance = mock(Instance.class);
        when(instance.get()).thenReturn(bean);
        return instance;
    }
}
