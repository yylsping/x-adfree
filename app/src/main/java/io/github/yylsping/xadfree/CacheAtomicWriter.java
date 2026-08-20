package io.github.yylsping.xadfree;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Write-temp-then-atomic-replace persistence (P2-1).
 *
 * <p>The destination is replaced by an atomic move when the filesystem
 * supports it, and by a non-pre-delete replace otherwise. The previous
 * delete-destination-then-rename sequence left a window where a kill dropped
 * the cache file entirely; that window is gone — at every instant the
 * destination is either the old complete file or the new complete file.
 * The temp file is fsynced before the move; a parent-directory fsync follows
 * when the platform allows it.
 */
final class CacheAtomicWriter {

    /** How the temp file replaces the destination; injectable for JVM tests. */
    interface ReplaceOperation {
        boolean replace(File temp, File destination) throws IOException;
    }

    /**
     * Atomic when possible; never deletes the destination first. Falls back
     * from ATOMIC_MOVE to a plain REPLACE_EXISTING move.
     */
    static final ReplaceOperation NIO_MOVE_REPLACE = (temp, destination) -> {
        Path from = temp.toPath();
        Path to = destination.toPath();
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
    };

    private CacheAtomicWriter() {
    }

    static boolean write(File destination, byte[] bytes, ReplaceOperation replaceOperation) {
        File temp = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (!replaceOperation.replace(temp, destination)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                return false;
            }
            syncDirectory(destination.getParentFile());
            return true;
        } catch (Throwable ignored) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }
    }

    private static void syncDirectory(File directory) {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                directory.toPath(), java.nio.file.StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Throwable ignored) {
            // Directory durability is best-effort; content safety comes from
            // the fsynced temp file and the atomic replace.
        }
    }
}
