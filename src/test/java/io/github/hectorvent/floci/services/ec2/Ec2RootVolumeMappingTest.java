package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.BlockDeviceMapping;
import io.github.hectorvent.floci.services.ec2.model.EbsBlockDevice;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The root volume ignored the launch's mapping for the root device apart from its type, so Terraform's
 * aws_instance root_block_device (volume_size, encrypted, kms_key_id) never read back and forced a
 * replacement.
 */
class Ec2RootVolumeMappingTest {

    private static final String REGION = "us-east-1";
    private static final String AMI = "ami-1234567890abcdef0";
    private static final String KEY = "arn:aws:kms:us-east-1:000000000000:key/0f1e2d3c-4b5a-6978-8091-a2b3c4d5e6f7";

    @Test
    void theRootVolumeFollowsTheLaunchMapping() {
        Ec2Service service = newService();
        LaunchTemplateData.Ebs ebs = ebs(20);
        ebs.setEncrypted(true);
        ebs.setKmsKeyId(KEY);
        ebs.setVolumeType("gp3");
        ebs.setThroughput(250);
        ebs.setDeleteOnTermination(false);

        Volume root = rootVolume(service, launch(service, AMI, "/dev/xvda", ebs));
        assertEquals(20, root.getSize());
        assertTrue(root.isEncrypted());
        assertEquals(KEY, root.getKmsKeyId());
        assertEquals("gp3", root.getVolumeType());
        assertEquals(250, root.getThroughput());
        assertEquals(false, root.getAttachments().getFirst().isDeleteOnTermination());
    }

    @Test
    void throughputIsKeptOnlyForGp3() {
        Ec2Service service = newService();
        LaunchTemplateData.Ebs ebs = ebs(20);
        ebs.setVolumeType("gp2");
        ebs.setThroughput(250);
        assertNull(rootVolume(service, launch(service, AMI, "/dev/xvda", ebs)).getThroughput());
    }

    @Test
    void awsRefusesAKeyWithoutEncryptionAndASizeBelowTheImagesRoot() {
        Ec2Service service = newService();
        LaunchTemplateData.Ebs keyOnly = ebs(20);
        keyOnly.setKmsKeyId(KEY);
        assertEquals("InvalidParameterDependency", assertThrows(AwsException.class,
                () -> launch(service, AMI, "/dev/xvda", keyOnly)).getErrorCode());
        assertEquals("InvalidBlockDeviceMapping", assertThrows(AwsException.class,
                () -> launch(service, AMI, "/dev/xvda", ebs(4))).getErrorCode());

        BlockDeviceMapping imageRoot = new BlockDeviceMapping();
        imageRoot.setDeviceName("/dev/xvda");
        EbsBlockDevice imageEbs = new EbsBlockDevice();
        imageEbs.setVolumeSize(30);
        imageRoot.setEbs(imageEbs);
        String bigRoot = service.registerImage(REGION, "big-root", null, "x86_64", "/dev/xvda", List.of(imageRoot))
                .getImageId();
        assertEquals("InvalidBlockDeviceMapping", assertThrows(AwsException.class,
                () -> launch(service, bigRoot, "/dev/xvda", ebs(20))).getErrorCode(),
                "20 GiB is below the image's 30 GiB root");
        assertEquals(30, rootVolume(service, launch(service, bigRoot, "/dev/sdb", ebs(20))).getSize(),
                "with no mapping for the root device the image's root size applies");
    }

    private static LaunchTemplateData.Ebs ebs(int size) {
        LaunchTemplateData.Ebs ebs = new LaunchTemplateData.Ebs();
        ebs.setVolumeSize(size);
        return ebs;
    }

    private static Instance launch(Ec2Service service, String imageId, String device, LaunchTemplateData.Ebs ebs) {
        LaunchTemplateData.BlockDeviceMapping mapping = new LaunchTemplateData.BlockDeviceMapping();
        mapping.setDeviceName(device);
        mapping.setEbs(ebs);
        return service.runInstances(REGION, imageId, "t3.micro", 1, 1, null, List.of(), null, null, List.of(),
                null, null, null, null, 0, null, null, null, null, false, null, null, List.of(mapping))
                .getInstances().getFirst();
    }

    private static Volume rootVolume(Ec2Service service, Instance instance) {
        return service.describeVolumes(REGION, List.of(instance.getRootVolumeId()), Map.of()).getFirst();
    }

    private static Ec2Service newService() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory("000000000000"));
    }
}
