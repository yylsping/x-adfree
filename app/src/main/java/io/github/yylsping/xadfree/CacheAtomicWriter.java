package io.github.yylsping.xadfree;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Write-then-rename persistence so a crash mid-write never corrupts the cache. */
final class CacheAtomicWriter {

    /** How the temp file replaces the destination; injectable for JVM tests. */
    interface ReplaceOperation {
        boolean replace(File temp, File destination) throws IOException;
    }

    static final ReplaceOperation RENAME_REPLACE = (temp, destination) -> {
        if (destination.isFile() && !destination.delete()) {
            return false;
        }
        return temp.renameTo(destination);
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
            return replaceOperation.replace(temp, destination);
        } catch (Throwable ignored) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }
    }
}
