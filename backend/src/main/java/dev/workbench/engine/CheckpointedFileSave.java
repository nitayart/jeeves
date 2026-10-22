package dev.workbench.engine;

import dev.workbench.registry.Json;
import dev.workbench.registry.SidecarIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keep the SDK's native save path/lock while protecting the prior complete database.
 * Each save retains an immutable fsynced checkpoint and a durable in-progress receipt.
 * An exception/refusal restores the old bytes and timestamp; an interrupted process leaves
 * the receipt for explicit recovery after its owner and lock have been checked.
 */
public final class CheckpointedFileSave {
    private CheckpointedFileSave() {}
    @FunctionalInterface public interface Writer { boolean write(Path destination) throws Exception; }

    public static Path marker(Path destination) { return Path.of(destination.toAbsolutePath() + ".save-in-progress.json"); }

    private static String suffixOf(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : ".bak";
    }

    public static boolean write(Path destination, Writer writer) throws Exception {
        Path absolute = destination.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        if (Files.exists(marker(absolute)))
            throw new IOException("An interrupted save needs recovery before another write: " + marker(absolute));
        Path backups = absolute.getParent().resolve(".save-checkpoints");
        Files.createDirectories(backups);
        Map<String,Object> receipt = new LinkedHashMap<>();
        receipt.put("destination", absolute.toString());
        receipt.put("startedAt", java.time.Instant.now().toString());
        if (Files.exists(absolute)) {
            Path backup = backups.resolve(absolute.getFileName() + "." + java.util.UUID.randomUUID() + suffixOf(absolute));
            Files.copy(absolute, backup, StandardCopyOption.COPY_ATTRIBUTES);
            SidecarIO.forceFile(backup);
            SidecarIO.forceDirectory(backups);
            String digest = sha256(absolute);
            if (!digest.equals(sha256(backup))) throw new IOException("Database checkpoint verification failed: " + backup);
            receipt.put("backup", backup.toString()); receipt.put("sha256", digest);
            receipt.put("bytes", Files.size(backup));
        }
        if (!SidecarIO.writeAtomic(marker(absolute).toFile(), Json.write(receipt), "database save receipt"))
            throw new IOException("Could not persist save recovery receipt; database untouched: " + absolute);
        try {
            if (!writer.write(absolute)) { restore(absolute, receipt); return false; }
            if (!Files.isRegularFile(absolute) || Files.size(absolute) == 0)
                throw new IOException("Database writer reported success without a nonempty file: " + absolute);
            SidecarIO.forceFile(absolute);
            SidecarIO.forceDirectory(absolute.getParent());
            Files.delete(marker(absolute));
            SidecarIO.forceDirectory(absolute.getParent());
            return true;
        } catch (Exception failure) {
            try { restore(absolute, receipt); }
            catch (Exception recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
    }

    /** Explicit recovery only: the caller must first prove the old owner is gone.
     * Never remove JEB's native lock as a side effect of recovery. */
    public static void recover(Path destination) throws IOException {
        Path absolute = destination.toAbsolutePath();
        if (Files.exists(Path.of(absolute + ".lock")))
            throw new IOException("Check and release the previous owner's native lock before recovery: " + absolute);
        if (!Files.exists(marker(absolute))) return;
        Map<String,Object> receipt = Json.parseObject(Files.readString(marker(absolute)));
        if (!absolute.toString().equals(receipt.get("destination"))) throw new IOException("Save receipt destination mismatch");
        restore(absolute, receipt);
    }

    private static void restore(Path destination, Map<String,Object> receipt) throws IOException {
        Path backup = receipt.get("backup") == null ? null : Path.of(String.valueOf(receipt.get("backup")));
        if (backup != null) {
            Path expectedParent = destination.getParent().resolve(".save-checkpoints").normalize();
            if (!backup.toAbsolutePath().normalize().getParent().equals(expectedParent)
                    || !sha256(backup).equals(receipt.get("sha256")))
                throw new IOException("Save checkpoint missing or invalid; original evidence retained: " + backup);
        }
        if (Files.exists(destination)) {
            Path evidence = destination.getParent().resolve(".save-checkpoints")
                    .resolve(destination.getFileName() + ".failed-" + java.util.UUID.randomUUID());
            Files.move(destination, evidence, StandardCopyOption.ATOMIC_MOVE);
            SidecarIO.forceDirectory(evidence.getParent());
        }
        if (backup != null) {
            Path replacement = Files.createTempFile(destination.getParent(), ".restore-", suffixOf(destination));
            try {
                Files.copy(backup, replacement, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                SidecarIO.forceFile(replacement);
                Files.move(replacement, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(replacement); }
        }
        SidecarIO.forceDirectory(destination.getParent());
        Files.deleteIfExists(marker(destination));
        SidecarIO.forceDirectory(destination.getParent());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"recover".equals(args[0]))
            throw new IllegalArgumentException("Usage: CheckpointedFileSave recover /absolute/path/Target.jdb2");
        Path destination = Path.of(args[1]);
        if (!destination.isAbsolute()) throw new IllegalArgumentException("An absolute database path is required");
        recover(destination);
        System.out.println("Recovery completed: " + destination);
    }

    public static String sha256(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            for (int n; (n = input.read(buffer)) > 0;) digest.update(buffer, 0, n);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
