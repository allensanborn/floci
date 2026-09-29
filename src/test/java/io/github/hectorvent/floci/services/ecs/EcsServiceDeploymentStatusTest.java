package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.ServiceDeployment;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a service deployment reports, driven off the reconciler tick by tick (floci-rddp).
 *
 * <p>The deployment a service is currently on is the resource a steady-state wait polls, so its
 * status has to mean something, and three things have to be true of it at once:
 *
 * <ul>
 *   <li>it is IN_PROGRESS until the deployment's tasks are up. One that is SUCCESSFUL the
 *       instant it is recorded makes {@code wait_for_steady_state} a wait that can never fail;</li>
 *   <li>the tasks it counts are its <em>own</em>, not the service's. Counting the ones still
 *       draining from the deployment it replaced reports a task-definition change finished
 *       before the new revision has started;</li>
 *   <li>SUCCESSFUL is terminal. Tasks dying afterwards do not un-finish a finished
 *       deployment.</li>
 * </ul>
 *
 * <p>Reconciler ticks are driven explicitly rather than waited on, and deployments are selected
 * by their task set id rather than by position in a listing, so nothing here depends on timing
 * or on map iteration order.
 */
class EcsServiceDeploymentStatusTest {

    private static final String REGION = "us-east-1";

    @Test
    void theCurrentDeploymentIsInProgressUntilTheServicesTasksAreRunning() {
        EcsService service = newMockModeService();
        service.createCluster("dstat-cluster", REGION);
        registerTaskDef(service, "dstat-fam", "app:1");
        service.createService("dstat-cluster", "dstat-svc", "dstat-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);

        // Created, nothing launched yet: runningCount 0 of a requested 1.
        ServiceDeployment pending = onlyDeployment(service, "dstat-svc", "dstat-cluster");
        assertEquals("IN_PROGRESS", pending.getStatus());
        assertNull(pending.getFinishedAt(), "an unfinished deployment has no finishedAt");

        // One tick is enough. The count is taken from the tasks at read time, not carried over
        // from svc.runningCount, which the reconciler sets from a snapshot taken *before* it
        // launches and so lags a tick behind the thing it describes.
        service.reconcileServices();

        ServiceDeployment done = onlyDeployment(service, "dstat-svc", "dstat-cluster");
        assertEquals("SUCCESSFUL", done.getStatus());
        assertNotNull(done.getFinishedAt(), "a finished deployment reports when it finished");
    }

    /**
     * A guard, not a repro: this one passes on the build the bug was found on, because there
     * every deployment was born SUCCESSFUL and so a zero-desired service trivially was too. It
     * exists to pin a direction the fix must not break -- a service already at its requested
     * count has converged, so its deployment is finished immediately and a steady-state wait
     * returns at once rather than stalling for something that is never going to happen.
     *
     * <p>What it catches, measured rather than asserted: make {@code settleStatus} refuse to call
     * a zero-desired service converged (add {@code && svc.getDesiredCount() > 0}, the shape a
     * defensive "nothing was asked for, so nothing succeeded" slip takes) and this goes red with
     * {@code expected: <SUCCESSFUL> but was: <IN_PROGRESS>} while the two desiredCount-1 tests in
     * this class stay green. The same mutation is what deriving the status on a reconciler tick,
     * instead of at read time, would amount to for a service that never needs a tick.
     *
     * <p>Two other tests catch that mutation too -- this class's integration twin
     * {@code theJoinedDeploymentIsDescribable} and the pre-existing
     * {@code EcsFargateEdgeCaseIntegrationTest.aServiceDeploymentPointsAtTheRevisionItDeployed},
     * both of which also use desiredCount 0. So this is not independent coverage. It is kept
     * because it is the only one of the three that fails with the condition named in the message;
     * the other two report "1 expectation failed" from a wire assertion.
     */
    @Test
    void aServiceAlreadyAtItsRequestedCountHasNothingToWaitFor() {
        EcsService service = newMockModeService();
        service.createCluster("dzero-cluster", REGION);
        registerTaskDef(service, "dzero-fam", "app:1");
        service.createService("dzero-cluster", "dzero-svc", "dzero-fam", 0,
                LaunchType.FARGATE, List.of(), null, REGION);

        ServiceDeployment deployment = onlyDeployment(service, "dzero-svc", "dzero-cluster");
        assertEquals("SUCCESSFUL", deployment.getStatus());
        assertNotNull(deployment.getFinishedAt());
    }

    @Test
    void everyDeploymentTargetsARevisionNamedByItsOwnTaskSetId() {
        EcsService service = newMockModeService();
        service.createCluster("drev-cluster", REGION);
        registerTaskDef(service, "drev-fam", "app:1");
        EcsServiceModel created = service.createService("drev-cluster", "drev-svc", "drev-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        // Read out now: createService and updateService hand back the same live model object.
        String firstDeploymentId = created.getDeploymentId();
        assertRevisionCarriesTaskSetId(service, "drev-svc", "drev-cluster", firstDeploymentId);

        service.reconcileServices();
        String secondDeploymentId = service.updateService("drev-cluster", "drev-svc", null, null,
                null, null, true, REGION).getDeploymentId();
        assertNotEquals(firstDeploymentId, secondDeploymentId,
                "forceNewDeployment mints a new deployment id");
        assertRevisionCarriesTaskSetId(service, "drev-svc", "drev-cluster", secondDeploymentId);

        // The superseded deployment keeps pointing at its own revision, so the two never collide.
        List<ServiceDeployment> all = service.listServiceDeploymentsDetailed("drev-svc",
                "drev-cluster", null, REGION);
        assertEquals(2, all.size());
        assertEquals(2, all.stream().map(ServiceDeployment::getTargetServiceRevisionArn)
                .distinct().count(), "each deployment targets its own revision");
    }

    /**
     * The update path, which is the most common real use of {@code wait_for_steady_state}: you
     * change the image and wait to find out whether the new one comes up.
     *
     * <p>A service's {@code runningCount} counts every task it owns, including the ones still
     * running on the deployment this one replaced. Settling a deployment on that number means a
     * task-definition change is reported finished the instant UpdateService returns, on the
     * strength of the OLD revision's task -- so a new image that cannot start reports stable and
     * the wait returns immediately. The deployment being settled has to be judged on its own
     * tasks.
     */
    @Test
    void aTaskDefinitionChangeIsNotFinishedWhileOnlyTheOldTasksAreRunning() {
        EcsService service = newMockModeService();
        service.createCluster("dupd-cluster", REGION);
        registerTaskDef(service, "dupd-fam", "app:1");
        EcsServiceModel created = service.createService("dupd-cluster", "dupd-svc", "dupd-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        String firstDeploymentId = created.getDeploymentId();
        service.reconcileServices();
        assertEquals("SUCCESSFUL", deploymentOf(service, "dupd-svc", "dupd-cluster",
                        firstDeploymentId).getStatus(),
                "precondition: the first deployment converged");

        TaskDefinition rev2 = registerTaskDef(service, "dupd-fam", "app:2");
        String rolled = service.updateService("dupd-cluster", "dupd-svc",
                "dupd-fam:" + rev2.getRevision(), null, null, REGION).getDeploymentId();

        // No tick yet. The only RUNNING task belongs to the superseded deployment, so the new
        // deployment has nothing of its own up.
        ServiceDeployment rolling = deploymentOf(service, "dupd-svc", "dupd-cluster", rolled);
        assertEquals("IN_PROGRESS", rolling.getStatus(),
                "a deployment with none of its own tasks running is not finished");
        assertNull(rolling.getFinishedAt(), "and carries no finishedAt");

        // One tick launches the new revision's task; now the deployment really has converged,
        // even though the old task is still draining beside it.
        service.reconcileServices();
        ServiceDeployment settled = deploymentOf(service, "dupd-svc", "dupd-cluster", rolled);
        assertEquals("SUCCESSFUL", settled.getStatus(),
                "its own task is running, so it is finished even while the old one drains");
        assertNotNull(settled.getFinishedAt());
    }

    /**
     * A finished deployment stays finished when its tasks later die. SUCCESSFUL is terminal in
     * AWS: a deployment that completed does not un-complete because the service became unhealthy
     * afterwards, and {@code finishedAt} records when it finished, not the last time it was
     * asked about.
     *
     * <p>This is the half a deployment-scoped task count does NOT fix -- when the deployment's
     * own tasks die, the scoped count drops too, so without the latch a terminal record would
     * flip back to IN_PROGRESS under its readers and blank a timestamp it had already published.
     */
    @Test
    void aFinishedDeploymentStaysFinishedWhenItsTasksDie() {
        EcsService service = newMockModeService();
        service.createCluster("ddie-cluster", REGION);
        registerTaskDef(service, "ddie-fam", "app:1");
        String deploymentId = service.createService("ddie-cluster", "ddie-svc", "ddie-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        service.reconcileServices();
        ServiceDeployment finished = deploymentOf(service, "ddie-svc", "ddie-cluster", deploymentId);
        assertEquals("SUCCESSFUL", finished.getStatus());
        Instant finishedAt = finished.getFinishedAt();
        assertNotNull(finishedAt);

        // Read again WHILE STILL CONVERGED. This is the read that pins the latch itself: without
        // the already-SUCCESSFUL guard, the converged branch is taken on every read and stamps a
        // fresh finishedAt each time, so a caller polling a finished deployment watches the
        // instant it finished at drift forwards. Re-reading only after the tasks die cannot see
        // that, because the converged branch is not taken then -- which is why this assertion
        // has to happen here and not below.
        assertEquals(finishedAt,
                deploymentOf(service, "ddie-svc", "ddie-cluster", deploymentId).getFinishedAt(),
                "finishedAt is stamped once, not re-stamped on every read while converged");

        String taskArn = runningTasks(service).getFirst().getTaskArn();
        service.stopTask("ddie-cluster", taskArn, "test kill", REGION);
        assertEquals(0, runningTasks(service).size(), "precondition: nothing of it is running");

        ServiceDeployment after = deploymentOf(service, "ddie-svc", "ddie-cluster", deploymentId);
        assertEquals("SUCCESSFUL", after.getStatus(), "a completed deployment does not un-complete");
        assertEquals(finishedAt, after.getFinishedAt(), "and keeps the instant it finished at");
    }

    /**
     * Scaling to zero. The deployment's own task is still up for a tick after desiredCount drops,
     * and {@code >=} calls that converged -- which is the direction that matters, since a client
     * waiting on a scale-to-zero should not be told it is still rolling out. Recorded as measured
     * behaviour rather than asserted as ideal: AWS's own waiter compares counts with {@code ==},
     * so it would hold this one pending until the task actually stops.
     */
    @Test
    void scalingToZeroIsConvergedBeforeTheTaskHasActuallyStopped() {
        EcsService service = newMockModeService();
        service.createCluster("dscale-cluster", REGION);
        registerTaskDef(service, "dscale-fam", "app:1");
        String id = service.createService("dscale-cluster", "dscale-svc", "dscale-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        service.reconcileServices();

        service.updateService("dscale-cluster", "dscale-svc", null, 0, null, REGION);
        assertEquals(1, runningTasks(service).size(), "the task has not been drained yet");
        assertEquals("SUCCESSFUL", deploymentOf(service, "dscale-svc", "dscale-cluster", id).getStatus());

        service.reconcileServices();
        assertEquals(0, runningTasks(service).size(), "and now it is drained");
        assertEquals("SUCCESSFUL", deploymentOf(service, "dscale-svc", "dscale-cluster", id).getStatus());
    }

    /**
     * Two task-definition changes in a row, with no tick between them. Only the newest deployment
     * is the one the service is on, so only it is settled from live tasks; the one skipped over
     * keeps what it was last read as, which is the documented behaviour for a superseded
     * deployment ({@code floci-n5kb} tracks closing those out properly).
     */
    @Test
    void twoUpdatesInSuccessionSettleOnlyTheNewestDeployment() {
        EcsService service = newMockModeService();
        service.createCluster("drapid-cluster", REGION);
        registerTaskDef(service, "drapid-fam", "app:1");
        service.createService("drapid-cluster", "drapid-svc", "drapid-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        service.reconcileServices();

        TaskDefinition rev2 = registerTaskDef(service, "drapid-fam", "app:2");
        service.updateService("drapid-cluster", "drapid-svc", "drapid-fam:" + rev2.getRevision(),
                null, null, REGION);
        TaskDefinition rev3 = registerTaskDef(service, "drapid-fam", "app:3");
        String newest = service.updateService("drapid-cluster", "drapid-svc",
                "drapid-fam:" + rev3.getRevision(), null, null, REGION).getDeploymentId();

        assertEquals(3, service.listServiceDeploymentsDetailed("drapid-svc", "drapid-cluster",
                null, REGION).size(), "one per create/update");
        assertEquals("IN_PROGRESS",
                deploymentOf(service, "drapid-svc", "drapid-cluster", newest).getStatus(),
                "the newest has none of its own tasks running");

        service.reconcileServices();
        assertEquals("SUCCESSFUL",
                deploymentOf(service, "drapid-svc", "drapid-cluster", newest).getStatus(),
                "and converges once its own task is up");
    }

    /**
     * The two views of one deployment must agree about the same moment. Right after a
     * task-definition change, {@code DescribeServices} deployments[0].rolloutState and the
     * {@code ServiceDeployment} record are both describing a rollout that has not started, and a
     * client reading either has to be told the same thing -- otherwise it has no way to know
     * which to believe. Both are derived from the deployment's own task count for that reason.
     *
     * <p>They are allowed to diverge later, and that is not the same thing: the record is
     * history and latches SUCCESSFUL, while rolloutState is live and follows the service. A
     * deployment that completed and then lost its tasks reports a finished record beside a
     * rollout that is no longer complete, which is two different questions with two correct
     * answers rather than one question with two.
     */
    @Test
    void theRolloutStateAndTheDeploymentRecordAgreeWhileARolloutIsInFlight() {
        EcsService service = newMockModeService();
        service.createCluster("dboth-cluster", REGION);
        registerTaskDef(service, "dboth-fam", "app:1");
        EcsServiceModel created = service.createService("dboth-cluster", "dboth-svc", "dboth-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        // TWO ticks, and the second one is what makes this test able to fail. The first launches
        // the task but sets svc.runningCount from a snapshot taken before the launch, so it is
        // still 0; the second counts it. Changing the task definition after only one tick leaves
        // the service-wide count at 0, which makes the buggy derivation give the right answer by
        // accident and the assertions below hold either way. A genuine steady state -- the state
        // anyone is actually in when they change an image -- is the state that exposes it.
        service.reconcileServices();
        service.reconcileServices();
        assertEquals(1, created.getRunningCount(),
                "precondition: a genuine steady state, not one tick short of it");

        TaskDefinition rev2 = registerTaskDef(service, "dboth-fam", "app:2");
        String rolled = service.updateService("dboth-cluster", "dboth-svc",
                "dboth-fam:" + rev2.getRevision(), null, null, REGION).getDeploymentId();

        EcsServiceModel svc = service.serviceByArn(
                service.describeServices("dboth-cluster", List.of("dboth-svc"), REGION)
                        .getFirst().getServiceArn());
        assertEquals("IN_PROGRESS", service.deploymentsFor(svc).getFirst().getRolloutState(),
                "the live rollout has not started: only the old revision's task is up");
        assertEquals("IN_PROGRESS",
                deploymentOf(service, "dboth-svc", "dboth-cluster", rolled).getStatus(),
                "and the record says the same about the same moment");
        assertTrue(service.eventsFor(svc).isEmpty(),
                "no steady-state event while the rollout is in flight");

        service.reconcileServices();
        assertEquals("COMPLETED", service.deploymentsFor(svc).getFirst().getRolloutState());
        assertEquals("SUCCESSFUL",
                deploymentOf(service, "dboth-svc", "dboth-cluster", rolled).getStatus());
    }

    /**
     * A DAEMON service still reaches a finished deployment after a task-definition change.
     *
     * <p>Scoping the count to the current deployment is right for REPLICA and wrong for DAEMON,
     * because {@code reconcileDaemonService} has no notion of staleness: it keeps whichever task
     * already covers a container instance, so the old revision's task holds its slot and no
     * replacement is ever launched. A deployment-scoped count would therefore sit at zero
     * permanently and a steady-state wait would hang, which is worse than the wrong-but-prompt
     * answer main gives.
     *
     * <p>Measured on the commit before this one: {@code rolloutState} stayed IN_PROGRESS and the
     * record stayed IN_PROGRESS with no finish time across four reconciler ticks, with the old
     * task still the only one running. This test fails there for that reason, which is the hang
     * itself and not an incidental difference.
     *
     * <p>The assertions below deliberately pin the limitation as well as the fix: the task still
     * running is the OLD revision's. That coupling is load-bearing, not documentation. The day
     * DAEMON rolling is implemented, counting stale tasks would reproduce on DAEMON the exact
     * REPLICA bug this branch exists to remove, so the DAEMON branch of the count has to come
     * out at the same moment. This assertion is what forces that: it fails as soon as the
     * reconciler starts replacing the task, instead of letting the mitigation quietly outlive
     * the reason for it.
     */
    @Test
    void aDaemonServiceStillFinishesItsDeploymentAfterATaskDefinitionChange() {
        EcsService service = newMockModeService();
        service.createCluster("ddmn-cluster", REGION);
        service.registerContainerInstance("ddmn-cluster", null, List.of(), REGION);
        TaskDefinition rev1 = registerTaskDef(service, "ddmn-fam", "app:1");
        service.createService("ddmn-cluster", "ddmn-svc", "ddmn-fam", 1, LaunchType.EC2,
                List.of(), null, null, "DAEMON", null, null, REGION);
        service.reconcileServices();
        service.reconcileServices();

        TaskDefinition rev2 = registerTaskDef(service, "ddmn-fam", "app:2");
        String rolled = service.updateService("ddmn-cluster", "ddmn-svc",
                "ddmn-fam:" + rev2.getRevision(), null, null, REGION).getDeploymentId();
        service.reconcileServices();
        service.reconcileServices();

        ServiceDeployment record = deploymentOf(service, "ddmn-svc", "ddmn-cluster", rolled);
        assertEquals("SUCCESSFUL", record.getStatus(),
                "a DAEMON deployment must still finish, or a steady-state wait hangs for ever");
        assertNotNull(record.getFinishedAt());

        // The limitation this count is built around: the daemon reconciler never replaced the
        // task, so what is running is still the previous revision.
        List<EcsTask> live = runningTasks(service);
        assertEquals(1, live.size(), "one task per container instance");
        assertEquals(rev1.getTaskDefinitionArn(), live.getFirst().getTaskDefinitionArn(),
                "DAEMON does not roll on a task-definition change; the count is honest about that");
        assertNotEquals(rev2.getTaskDefinitionArn(), live.getFirst().getTaskDefinitionArn());
    }

    /** The one place the provider's join is asserted: the revision ARN carries the task set id. */
    private static void assertRevisionCarriesTaskSetId(EcsService service, String name,
                                                       String cluster, String deploymentId) {
        String taskSetId = deploymentId.substring(deploymentId.indexOf('/') + 1);
        List<String> targets = service.listServiceDeploymentsDetailed(name, cluster, null, REGION)
                .stream().map(ServiceDeployment::getTargetServiceRevisionArn).toList();
        assertTrue(targets.stream().anyMatch(arn -> arn != null && arn.contains(taskSetId)),
                "some deployment's revision ARN must carry task set id " + taskSetId
                        + ", targets were: " + targets);
    }

    /**
     * The deployment carrying {@code deploymentId}, selected by the task set id inside its target
     * revision ARN rather than by position in the listing. The listing sorts on createdAt, and two
     * records minted in the same instant would then fall back to map iteration order -- position
     * would make this class's "no timing" claim false.
     */
    private static ServiceDeployment deploymentOf(EcsService service, String name, String cluster,
                                                  String deploymentId) {
        String taskSetId = deploymentId.substring(deploymentId.indexOf('/') + 1);
        return service.listServiceDeploymentsDetailed(name, cluster, null, REGION).stream()
                .filter(d -> d.getTargetServiceRevisionArn() != null
                        && d.getTargetServiceRevisionArn().endsWith("/" + taskSetId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no deployment for " + deploymentId));
    }

    private static ServiceDeployment onlyDeployment(EcsService service, String name, String cluster) {
        List<ServiceDeployment> deployments =
                service.listServiceDeploymentsDetailed(name, cluster, null, REGION);
        assertEquals(1, deployments.size(), "one create, one deployment");
        return deployments.getFirst();
    }

    private static List<EcsTask> runningTasks(EcsService service) {
        return service.describeTasks(null, service.listTasks(null, null, null, null, REGION), REGION)
                .stream().filter(t -> "RUNNING".equals(t.getLastStatus())).toList();
    }

    private static TaskDefinition registerTaskDef(EcsService service, String family, String image) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage(image);
        return service.registerTaskDefinition(family, List.of(cd), null, null, null,
                null, null, List.of(), REGION);
    }

    private static EcsService newMockModeService() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class),
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new InMemoryStorageFactory(),
                null);
        service.initializeStorage();
        return service;
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                        String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
