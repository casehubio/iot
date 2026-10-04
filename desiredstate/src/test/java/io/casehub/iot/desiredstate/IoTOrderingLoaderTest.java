package io.casehub.iot.desiredstate;

import io.casehub.iot.api.DeviceClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IoTOrderingLoaderTest {

    @TempDir Path tempDir;

    @Test
    void loadGlobal_scansDirectoryForOrderingEntries() throws IOException {
        Files.writeString(tempDir.resolve("safety.yaml"),
            "ordering:\n  - before: LOCK\n    after: LIGHT\n");
        var loader = new IoTOrderingLoader(tempDir.toString());
        Set<IoTOrderingEntry> entries = loader.loadGlobal();
        assertThat(entries).containsExactly(new IoTOrderingEntry(DeviceClass.LOCK, DeviceClass.LIGHT));
    }

    @Test
    void loadGlobal_unionsMultipleFiles() throws IOException {
        Files.writeString(tempDir.resolve("a.yaml"),
            "ordering:\n  - before: LOCK\n    after: LIGHT\n");
        Files.writeString(tempDir.resolve("b.yaml"),
            "ordering:\n  - before: SWITCH\n    after: COVER\n");
        var loader = new IoTOrderingLoader(tempDir.toString());
        Set<IoTOrderingEntry> entries = loader.loadGlobal();
        assertThat(entries).hasSize(2);
    }

    @Test
    void loadGlobal_emptyDirectory_returnsEmpty() {
        var loader = new IoTOrderingLoader(tempDir.toString());
        assertThat(loader.loadGlobal()).isEmpty();
    }

    @Test
    void loadGlobal_nullPath_returnsEmpty() {
        var loader = new IoTOrderingLoader((String) null);
        assertThat(loader.loadGlobal()).isEmpty();
    }
}
