import java.net.URI;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal reproduction of the AccessTransformers failure recorded in
 * prankcraft/forge-mod/BUILD-STATUS.md:
 *
 *   net.minecraftforge.accesstransformer.TransformerProcessor.processJar
 *     -> Files.copy(sourceEntry, outputFs.getPath(...))
 *     -> ReadOnlyFileSystemException at ZipFileSystem.checkWritable
 *
 * If this program succeeds on a machine, the Forge build's blocker is environmental and
 * unrelated to that machine's ForgeGradle setup. If it fails the same way, the zip filesystem
 * provider on that machine genuinely cannot write, and no amount of Gradle configuration will
 * get past it.
 */
public final class ZipFsWriteProbe {

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath();
        Files.createDirectories(dir);
        Path source = dir.resolve("probe-source.zip");
        Path target = dir.resolve("probe-target.zip");

        Files.deleteIfExists(source);
        Files.deleteIfExists(target);

        // 1. A small source archive with one entry, written the ordinary way.
        try (FileSystem fs = FileSystems.newFileSystem(
                URI.create("jar:" + source.toUri()), Map.of("create", "true"))) {
            Files.writeString(fs.getPath("payload.txt"), "hello from the source archive");
        }
        System.out.println("source archive written: " + Files.size(source) + " bytes");

        // 2. The exact shape TransformerProcessor uses: open the output jar as a zip
        //    filesystem and copy an entry in through it.
        try (FileSystem in = FileSystems.newFileSystem(URI.create("jar:" + source.toUri()), Map.of());
             FileSystem out = FileSystems.newFileSystem(URI.create("jar:" + target.toUri()),
                     Map.of("create", "true"))) {

            Path from = in.getPath("payload.txt");
            Path to = out.getPath("payload.txt");
            try {
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("RESULT: SUCCESS - the zip filesystem is writable here");
                System.out.println("target archive: " + Files.size(target) + " bytes");
                System.exit(0);
            } catch (ReadOnlyFileSystemException e) {
                System.out.println("RESULT: READ-ONLY - reproduced the AccessTransformers failure");
                e.printStackTrace(System.out);
                System.exit(2);
            }
        }
    }
}
