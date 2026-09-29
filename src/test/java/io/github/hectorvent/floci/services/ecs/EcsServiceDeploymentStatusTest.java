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
 *       deployment;</li>
 *   <li>a deployment ends. One superseded before it converged can never be settled, because
 *       settling finishes the deployment the service is currently on, so it is stopped when its
 *       replacement is recorded rather than left in flight for ever.</li>
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
     * is the one the service is on, so only it is settled from live tasks; the two it skipped
     * over are landed in STOPPED as each replacement is recorded.
     *
     * <p>The first of those had in fact converged, on the tick before the updates, and is stopped
     * anyway: convergence is decided at read time and nothing read it. This pins that consequence
     * rather than hiding it. It needs a client that reconciles a service to a steady state and
     * then changes it again without ever describing it in between, which no real client does,
     * because every response carrying a service settles that service's current deployment.
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
        assertEquals(List.of("STOPPED", "STOPPED"),
                service.listServiceDeploymentsDetailed("drapid-svc", "drapid-cluster", null, REGION)
                        .stream().skip(1).map(ServiceDeployment::getStatus).toList(),
                "both deployments the service moved off are terminal, newest-first ordering");

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

    /**
     * A deployment superseded before it converged has to end somewhere. {@code settleStatus}
     * finishes only the deployment the service is currently on, and a superseded one never is
     * again, so nothing else can ever move it: it would sit IN_PROGRESS with no finish time for
     * the life of the emulator, claiming a rollout that can no longer happen is still happening.
     * A client listing a service's deployments would read an unbounded pile of them in flight at
     * once, and one filtering on IN_PROGRESS would get every rollout the service ever abandoned.
     *
     * <p>STOPPED is the status AWS uses for a deployment that ended without completing, and is
     * one of the nine in {@code ServiceDeploymentStatus}.
     *
     * <p>The second service is not decoration: it is never updated, so its own deployment is
     * still in flight, and it fails this test if the stop is not scoped to one service ARN.
     */
    @Test
    void supersedingAnUnconvergedDeploymentStopsIt() {
        EcsService service = newMockModeService();
        service.createCluster("dsup-cluster", REGION);
        registerTaskDef(service, "dsup-fam", "app:1");
        String first = service.createService("dsup-cluster", "dsup-svc", "dsup-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        registerTaskDef(service, "dsup-other-fam", "other:1");
        String bystander = service.createService("dsup-cluster", "dsup-other", "dsup-other-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();

        // No tick: neither service has launched anything, so both deployments are in flight.
        assertEquals("IN_PROGRESS",
                deploymentOf(service, "dsup-svc", "dsup-cluster", first).getStatus(),
                "precondition: the first deployment never converged");

        TaskDefinition rev2 = registerTaskDef(service, "dsup-fam", "app:2");
        String second = service.updateService("dsup-cluster", "dsup-svc",
                "dsup-fam:" + rev2.getRevision(), null, null, REGION).getDeploymentId();

        ServiceDeployment superseded = deploymentOf(service, "dsup-svc", "dsup-cluster", first);
        assertEquals("STOPPED", superseded.getStatus(),
                "a deployment another one took over from is finished, not still rolling out");
        assertNotNull(superseded.getFinishedAt(),
                "and reports when it ended, like every deployment that is no longer running");
        assertNotNull(superseded.getStoppedAt(), "and when it was stopped");
        assertTrue(superseded.getStatusReason() != null
                        && superseded.getStatusReason().contains(deploymentArnOf(service,
                                "dsup-svc", "dsup-cluster", second)),
                "the reason names the deployment that took over, which is what a reader needs "
                        + "and the status alone does not say; was: " + superseded.getStatusReason());

        assertEquals("IN_PROGRESS",
                deploymentOf(service, "dsup-svc", "dsup-cluster", second).getStatus(),
                "while the deployment that took over is the one now in flight");
        assertEquals("IN_PROGRESS",
                deploymentOf(service, "dsup-other", "dsup-cluster", bystander).getStatus(),
                "another service's deployment is none of this update's business");
    }

    /**
     * STOPPED is terminal in the same sense SUCCESSFUL is. Once the replacement converges, the
     * service is at its requested count and a status derived from that would call the superseded
     * deployment finished too, which would be reporting that a rollout nobody is on succeeded.
     * The stopped record keeps both its status and the instant it ended.
     */
    @Test
    void aSupersededDeploymentDoesNotSucceedWhenItsReplacementDoes() {
        EcsService service = newMockModeService();
        service.createCluster("dsup2-cluster", REGION);
        registerTaskDef(service, "dsup2-fam", "app:1");
        String first = service.createService("dsup2-cluster", "dsup2-svc", "dsup2-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();

        TaskDefinition rev2 = registerTaskDef(service, "dsup2-fam", "app:2");
        String second = service.updateService("dsup2-cluster", "dsup2-svc",
                "dsup2-fam:" + rev2.getRevision(), null, null, REGION).getDeploymentId();
        Instant stoppedAt = deploymentOf(service, "dsup2-svc", "dsup2-cluster", first).getFinishedAt();
        assertNotNull(stoppedAt, "precondition: the superseded deployment ended");

        service.reconcileServices();
        assertEquals("SUCCESSFUL",
                deploymentOf(service, "dsup2-svc", "dsup2-cluster", second).getStatus(),
                "precondition: the replacement converged");

        ServiceDeployment superseded = deploymentOf(service, "dsup2-svc", "dsup2-cluster", first);
        assertEquals("STOPPED", superseded.getStatus(),
                "the deployment that was taken over from did not succeed, its replacement did");
        assertEquals(stoppedAt, superseded.getFinishedAt(),
                "and still reports the instant it ended");
    }

    /**
     * The other direction: a deployment that completed before it was superseded is history and
     * must not be rewritten. Stopping every prior deployment unconditionally would turn a
     * service's whole completed rollout history into a list of stopped ones.
     *
     * <p>The read before the update is load-bearing rather than an assertion for its own sake:
     * the SUCCESSFUL transition is made at read time, so this is what puts the first deployment
     * into the terminal state the update then has to leave alone. That is the ordering a real
     * client is in, because every response carrying a service settles its current deployment.
     */
    @Test
    void aDeploymentThatFinishedBeforeItWasSupersededStaysSuccessful() {
        EcsService service = newMockModeService();
        service.createCluster("dsup3-cluster", REGION);
        registerTaskDef(service, "dsup3-fam", "app:1");
        String first = service.createService("dsup3-cluster", "dsup3-svc", "dsup3-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        service.reconcileServices();

        ServiceDeployment finished = deploymentOf(service, "dsup3-svc", "dsup3-cluster", first);
        assertEquals("SUCCESSFUL", finished.getStatus(), "precondition: it converged and was read");
        Instant finishedAt = finished.getFinishedAt();
        assertNotNull(finishedAt);

        TaskDefinition rev2 = registerTaskDef(service, "dsup3-fam", "app:2");
        service.updateService("dsup3-cluster", "dsup3-svc", "dsup3-fam:" + rev2.getRevision(),
                null, null, REGION);

        ServiceDeployment after = deploymentOf(service, "dsup3-svc", "dsup3-cluster", first);
        assertEquals("SUCCESSFUL", after.getStatus(),
                "a deployment that completed was not stopped by the one that followed it");
        assertEquals(finishedAt, after.getFinishedAt(),
                "and keeps the instant it finished at, not the instant it was superseded");
        assertNull(after.getStoppedAt(), "a deployment that succeeded was never stopped");
        assertNull(after.getStatusReason(), "and has no reason it failed to finish");
    }

    /**
     * The second route into Greptile's finding, and the one supersession does not cover.
     * {@code settleStatus} finishes a deployment only while its service is ACTIVE, and
     * {@code deleteService} makes the service INACTIVE. Delete a service before its first
     * rollout converges and the deployment could never be moved by anything: no later update
     * supersedes it, because there are no later updates on a deleted service.
     *
     * <p>{@code ListServiceDeployments} filtered on IN_PROGRESS returned it for the life of the
     * emulator, which is the same symptom a superseded deployment had, reached through a
     * different door. Asserted on the filtered listing rather than only on the record, because
     * the filter is what a client actually uses to ask what is rolling out.
     */
    @Test
    void deletingAServiceStopsTheDeploymentItLeavesUnfinished() {
        EcsService service = newMockModeService();
        service.createCluster("ddel-cluster", REGION);
        registerTaskDef(service, "ddel-fam", "app:1");
        String only = service.createService("ddel-cluster", "ddel-svc", "ddel-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();

        // No tick, so nothing was ever launched and the deployment is still rolling out.
        assertEquals("IN_PROGRESS",
                deploymentOf(service, "ddel-svc", "ddel-cluster", only).getStatus(),
                "precondition: the deployment never converged");
        assertEquals(1, service.listServiceDeployments("ddel-svc", "ddel-cluster",
                List.of("IN_PROGRESS"), REGION).size(), "precondition: and reads as in flight");

        service.deleteService("ddel-cluster", "ddel-svc", true, REGION);

        ServiceDeployment stopped = deploymentOf(service, "ddel-svc", "ddel-cluster", only);
        assertEquals("STOPPED", stopped.getStatus(),
                "a deployment whose service is gone has ended, and nothing else can ever say so");
        assertNotNull(stopped.getFinishedAt(), "it reports when it ended");
        assertNotNull(stopped.getStoppedAt(), "and when it was stopped");
        assertEquals("The service was deleted.", stopped.getStatusReason(),
                "naming the reason it could not finish");

        assertTrue(service.listServiceDeployments("ddel-svc", "ddel-cluster",
                        List.of("IN_PROGRESS"), REGION).isEmpty(),
                "and a client asking what is rolling out is no longer told this is");
    }

    /**
     * Deleting a service does not rewrite the rollouts that finished before it. The same
     * already-terminal guard supersession relies on, reached through the delete path, because a
     * shared helper is only correct at both call sites if both are asserted.
     */
    @Test
    void deletingAServiceLeavesItsFinishedDeploymentsSuccessful() {
        EcsService service = newMockModeService();
        service.createCluster("ddel2-cluster", REGION);
        registerTaskDef(service, "ddel2-fam", "app:1");
        String only = service.createService("ddel2-cluster", "ddel2-svc", "ddel2-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION).getDeploymentId();
        service.reconcileServices();

        ServiceDeployment finished = deploymentOf(service, "ddel2-svc", "ddel2-cluster", only);
        assertEquals("SUCCESSFUL", finished.getStatus(), "precondition: it converged and was read");
        Instant finishedAt = finished.getFinishedAt();
        assertNotNull(finishedAt);

        service.deleteService("ddel2-cluster", "ddel2-svc", true, REGION);

        ServiceDeployment after = deploymentOf(service, "ddel2-svc", "ddel2-cluster", only);
        assertEquals("SUCCESSFUL", after.getStatus(),
                "a rollout that completed is history, and deleting the service does not undo it");
        assertEquals(finishedAt, after.getFinishedAt(), "keeping the instant it finished at");
        assertNull(after.getStoppedAt(), "and carrying none of the stopped fields");
        assertNull(after.getStatusReason());
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

    /** The ARN of the deployment carrying {@code deploymentId}, selected the same way. */
    private static String deploymentArnOf(EcsService service, String name, String cluster,
                                          String deploymentId) {
        return deploymentOf(service, name, cluster, deploymentId).getServiceDeploymentArn();
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
