package io.casehub.iot.testing;

import io.casehub.iot.api.DeviceEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceFixtureEquivalenceTest {

    @Test
    void yamlStandardHomeMatchesJavaFixtures() {
        List<DeviceEntity> fromYaml = DeviceFixtureLoader.load("fixtures/standard-home.yaml");
        List<DeviceEntity> fromJava = Fixtures.standardHome();
        assertThat(fromYaml).usingRecursiveComparison()
            .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .isEqualTo(fromJava);
    }

    @Test
    void yamlAndJavaProduceSameDeviceCount() {
        List<DeviceEntity> fromYaml = DeviceFixtureLoader.load("fixtures/standard-home.yaml");
        assertThat(fromYaml).hasSize(11);
    }
}
