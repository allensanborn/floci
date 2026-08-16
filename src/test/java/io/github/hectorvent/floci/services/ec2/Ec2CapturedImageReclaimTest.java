package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CreateImage captures an instance's file system as a committed Docker image. Those layers are
 * real disk, and a build loop that re-creates the same AMI would otherwise leave one behind per
 * iteration, so deregistering an AMI releases its capture.
 *
 * <p>The exception is the case that matters for correctness: AWS keeps instances launched from a
 * deregistered AMI running, and lets them stop and start again. While anything can still boot
 * from a capture, the capture has to stay.
 *
 * <p>These drive the real CreateImage path rather than setting the captured reference by hand.
 * The image store hands back detached copies, so a mutation applied to a returned Image never
 * reaches the store -- a hand-built fixture silently tests nothing.
 */
@QuarkusTest
@TestProfile(Ec2CapturedImageReclaimTest.ContainerBackedEc2.class)
class Ec2CapturedImageReclaimTest {

    /**
     * The shared test configuration runs EC2 in mock mode, which skips container work entirely --
     * including the capture and its reclamation, so neither path would execute. This turns it
     * back on; the container manager itself is mocked, so no Docker is involved.
     */
    public static class ContainerBackedEc2 implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false");
        }
    }

    @Inject
    Ec2Service service;

    @InjectMock
    Ec2ContainerManager containerManager;

    private static final String REGION = "us-east-1";
    private static final String BASE_AMI = "ami-amazonlinux2023";

    @BeforeEach
    void terminationMarksInstancesTerminated() {
        // Outside mock mode Ec2Service delegates termination to the container manager, which is
        // mocked here, so without this an instance stays "running" in the store forever and any
        // assertion about terminated instances would be vacuous.
        doAnswer(invocation -> {
            invocation.<Instance>getArgument(0).setState(InstanceState.terminated());
            return null;
        }).when(containerManager).terminate(any(Instance.class));
    }

    private Instance launch(String imageId) {
        return service.runInstances(REGION, imageId, "t3.micro", 1, 1,
                null, List.of(), null, null, List.of(), null, null)
                .getInstances().getFirst();
    }

    /** Runs an instance and captures it, with the commit stubbed to the given reference. */
    private Image captureAmi(String name, String tag) {
        when(containerManager.commitInstance(any(Instance.class), anyString())).thenReturn(tag);
        Instance source = launch(BASE_AMI);
        Image image = service.createImage(REGION, source.getInstanceId(), name, "captured", true);
        service.terminateInstances(REGION, List.of(source.getInstanceId()));
        return image;
    }

    @Test
    void deregisteringAnUnusedAmiReleasesItsCapture() {
        Image image = captureAmi("reclaim-unused", "floci-ami/ami-unused:latest");

        service.deregisterImage(REGION, image.getImageId(), false);

        verify(containerManager).removeCommittedImage("floci-ami/ami-unused:latest");
    }

    @Test
    void deregisteringAnAmiWithALiveInstanceKeepsItsCapture() {
        // Removing the layer this instance boots from would break the stop/start that AWS
        // explicitly still permits after deregistration.
        Image image = captureAmi("reclaim-inuse", "floci-ami/ami-inuse:latest");
        launch(image.getImageId());

        service.deregisterImage(REGION, image.getImageId(), false);

        verify(containerManager, never()).removeCommittedImage("floci-ami/ami-inuse:latest");
    }

    @Test
    void aTerminatedInstanceDoesNotPinACapture() {
        // Terminated instances can never boot again, so they must not keep the layer alive --
        // otherwise nothing is ever reclaimed in a long-running emulator.
        Image image = captureAmi("reclaim-terminated", "floci-ami/ami-terminated:latest");
        Instance launched = launch(image.getImageId());
        service.terminateInstances(REGION, List.of(launched.getInstanceId()));

        service.deregisterImage(REGION, image.getImageId(), false);

        verify(containerManager).removeCommittedImage("floci-ami/ami-terminated:latest");
    }

    @Test
    void deregisteringAnAmiThatWasNeverCapturedRemovesNothing() {
        // RegisterImage and catalog AMIs have no captured layer to release.
        Image image = service.registerImage(REGION, "reclaim-uncaptured", "plain",
                "x86_64", "/dev/xvda", List.of());

        service.deregisterImage(REGION, image.getImageId(), false);

        verify(containerManager, never()).removeCommittedImage(anyString());
    }
}
