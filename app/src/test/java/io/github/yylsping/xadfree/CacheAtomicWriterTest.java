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
                "hello".getBytes(StandardCharsets.UTF_8), CacheAtomicWriter.NIO_MOVE_REPLACE);

        assertTrue(written);
        assertEquals("hello",
                new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(dir, "cache.json.tmp").isFile());
    }

    @Test
    public void defaultReplaceNeverDeletesTheDestinationFirst() throws Exception {
        File dir = temporaryFolder.newFolder();
        File destination = new File(dir, "cache.json");
        try (FileOutputStream out = new FileOutputStream(destination)) {
            out.write("old".getBytes(StandardCharsets.UTF_8));
        }
        final boolean[] destinationExistedAtReplace = {false};

        // P2-1: the destination must still be complete when replace starts —
        // no delete-then-rename window.
        boolean written = CacheAtomicWriter.write(destination,
                "new".getBytes(StandardCharsets.UTF_8), (temp, target) -> {
                    destinationExistedAtReplace[0] = target.isFile();
                    Files.move(temp.toPath(), target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return true;
                });

        assertTrue(written);
        assertTrue("destination vanished before replace", destinationExistedAtReplace[0]);
        assertEquals("new",
                new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
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

    @Test
    public void crashDuringReplaceLeavesEitherOldOrNew() throws Exception {
        File dir = temporaryFolder.newFolder();
        File destination = new File(dir, "cache.json");
        try (FileOutputStream out = new FileOutputStream(destination)) {
            out.write("old".getBytes(StandardCharsets.UTF_8));
        }

        // Simulate a kill exactly between temp write and replace.
        CacheAtomicWriter.write(destination, "new".getBytes(StandardCharsets.UTF_8),
                (temp, target) -> {
                    throw new IllegalStateException("killed");
                });

        String content =
                new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8);
        assertTrue("content=" + content, "old".equals(content) || "new".equals(content));
        assertEquals("old", content);
        assertFalse(new File(dir, "cache.json.tmp").isFile());
    }

    @Test
    public void repeatedWritesAlwaysReplaceInPlace() throws Exception {
        File dir = temporaryFolder.newFolder();
        File destination = new File(dir, "cache.json");

        for (int round = 0; round < 3; round++) {
            boolean written = CacheAtomicWriter.write(destination,
                    ("v" + round).getBytes(StandardCharsets.UTF_8),
                    CacheAtomicWriter.NIO_MOVE_REPLACE);
            assertTrue(written);
            assertEquals("v" + round, new String(
                    Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
        }
    }
}
