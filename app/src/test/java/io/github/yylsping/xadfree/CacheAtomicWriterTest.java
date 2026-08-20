package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class CacheAtomicWriterTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void successfulWritePersistsBytes() throws Exception {
        File dir = temporaryFolder.newFolder();
        File destination = new File(dir, "cache.json");

        boolean written = CacheAtomicWriter.write(destination,
                "hello".getBytes(StandardCharsets.UTF_8), CacheAtomicWriter.RENAME_REPLACE);

        assertTrue(written);
        assertEquals("hello",
                new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(dir, "cache.json.tmp").isFile());
    }

    @Test
    public void replaceFailureKeepsOldContent() throws Exception {
        File dir = temporaryFolder.newFolder();
        File destination = new File(dir, "cache.json");
        try (FileOutputStream out = new FileOutputStream(destination)) {
            out.write("old".getBytes(StandardCharsets.UTF_8));
        }

        boolean written = CacheAtomicWriter.write(destination,
                "new".getBytes(StandardCharsets.UTF_8), (temp, target) -> false);

        assertFalse(written);
        assertEquals("old",
                new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
    }
}
