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
 * status has to mean something: IN_PROGRESS while the service is short of its requested task
 * count, SUCCESSFUL once it is running at it. A deployment that is SUCCESSFUL the instant it is
 * recorded makes {@code wait_for_steady_state} return before any task exists, which is a wait
 * that can never fail and therefore reports nothing.
 *
 * <p>Ticks are driven explicitly here rather than waited on, so there is no timing in the test.
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
     * {@code theJoinedDeploymentIsDescribableAndReportsTheServicesProgress} and the pre-existing
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
        service.createService("dupd-cluster", "dupd-svc", "dupd-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        service.reconcileServices();
        service.reconcileServices();
        assertEquals("SUCCESSFUL", currentDeployment(service, "dupd-svc", "dupd-cluster").getStatus(),
                "precondition: the first deployment converged");

        TaskDefinition rev2 = registerTaskDef(service, "dupd-fam", "app:2");
        service.updateService("dupd-cluster", "dupd-svc", "dupd-fam:" + rev2.getRevision(),
                null, null, REGION);

        // No tick yet. The only RUNNING task belongs to the superseded deployment, so the new
        // deployment has nothing of its own up.
        ServiceDeployment rolling = currentDeployment(service, "dupd-svc", "dupd-cluster");
        assertEquals("IN_PROGRESS", rolling.getStatus(),
                "a deployment with none of its own tasks running is not finished");
        assertNull(rolling.getFinishedAt(), "and carries no finishedAt");

        // One tick launches the new revision's task; now the deployment really has converged,
        // even though the old task is still draining beside it.
        service.reconcileServices();
        ServiceDeployment settled = currentDeployment(service, "dupd-svc", "dupd-cluster");
        assertEquals("SUCCESSFUL", settled.getStatus(),
                "its own task is running, so it is finished even while the old one drains");
        assertNotNull(settled.getFinishedAt());
    }

    /**
     * A deployment that lost its task is no longer finished. Tasks die, and a status derived from
     * what is running now has to be able to go backwards; a deployment latched SUCCESSFUL would
     * tell a client the service is healthy while nothing of it is up.
     */
    @Test
    void aDeploymentStopsBeingFinishedWhenItsTaskDies() {
        EcsService service = newMockModeService();
        service.createCluster("ddie-cluster", REGION);
        registerTaskDef(service, "ddie-fam", "app:1");
        service.createService("ddie-cluster", "ddie-svc", "ddie-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        service.reconcileServices();
        assertEquals("SUCCESSFUL", currentDeployment(service, "ddie-svc", "ddie-cluster").getStatus());

        String taskArn = runningTasks(service).getFirst().getTaskArn();
        service.stopTask("ddie-cluster", taskArn, "test kill", REGION);

        ServiceDeployment reopened = currentDeployment(service, "ddie-svc", "ddie-cluster");
        assertEquals("IN_PROGRESS", reopened.getStatus(),
                "nothing of this deployment is running any more");
        assertNull(reopened.getFinishedAt(), "and its finishedAt is withdrawn with it");
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
        service.createService("dscale-cluster", "dscale-svc", "dscale-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);
        service.reconcileServices();

        service.updateService("dscale-cluster", "dscale-svc", null, 0, null, REGION);
        assertEquals(1, runningTasks(service).size(), "the task has not been drained yet");
        assertEquals("SUCCESSFUL", currentDeployment(service, "dscale-svc", "dscale-cluster").getStatus());

        service.reconcileServices();
        assertEquals(0, runningTasks(service).size(), "and now it is drained");
        assertEquals("SUCCESSFUL", currentDeployment(service, "dscale-svc", "dscale-cluster").getStatus());
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
        service.updateService("drapid-cluster", "drapid-svc", "drapid-fam:" + rev3.getRevision(),
                null, null, REGION);

        List<ServiceDeployment> all = service.listServiceDeploymentsDetailed("drapid-svc",
                "drapid-cluster", null, REGION);
        assertEquals(3, all.size(), "one per create/update");
        assertEquals("IN_PROGRESS", all.getFirst().getStatus(),
                "the newest has none of its own tasks running");

        service.reconcileServices();
        assertEquals("SUCCESSFUL",
                currentDeployment(service, "drapid-svc", "drapid-cluster").getStatus(),
                "and converges once its own task is up");
    }

    /** The one place the provider's join is asserted: the revision ARN carries the task set id. */
    private static void assertRevisionCarriesTaskSetId(EcsService service, String name,
                                                       String cluster, String deploymentId) {
        String taskSetId = deploymentId.substring(deploymentId.indexOf('/') + 1);
        String current = service.listServiceDeploymentsDetailed(name, cluster, null, REGION)
                .getFirst().getTargetServiceRevisionArn();
        assertTrue(current != null && current.contains(taskSetId),
                "revision ARN must carry task set id " + taskSetId + ", was: " + current);
    }

    /** The deployment the service is currently on: the listing is newest-first. */
    private static ServiceDeployment currentDeployment(EcsService service, String name, String cluster) {
        return service.listServiceDeploymentsDetailed(name, cluster, null, REGION).getFirst();
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
