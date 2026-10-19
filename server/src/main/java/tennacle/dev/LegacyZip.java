package tennacle.dev;

import io.github.term4.bloom.mapmaking.LegacyAnvil;
import net.minestom.server.instance.Instance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A zipped pre-1.13 world, wherever its {@code region/} folder sits inside the zip, as a polar world. */
final class LegacyZip {

    private LegacyZip() {}

    static byte[] toPolar(Path zip, Consumer<Instance> edit) throws IOException {
        Path temp = Files.createTempDirectory("legacy-world");
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            String root = null;
            for (ZipEntry entry : archive.stream().toList()) {
                int at = entry.getName().indexOf("region/");
                if (at >= 0) {
                    root = entry.getName().substring(0, at);
                    break;
                }
            }
            if (root == null) throw new IOException(zip + " holds no region/ folder");
            for (ZipEntry entry : archive.stream().toList()) {
                if (entry.isDirectory() || !entry.getName().startsWith(root)) continue;
                Path target = temp.resolve(entry.getName().substring(root.length())).normalize();
                if (!target.startsWith(temp)) throw new IOException("entry outside the world: " + entry.getName());
                Files.createDirectories(target.getParent());
                try (InputStream in = archive.getInputStream(entry)) {
                    Files.copy(in, target);
                }
            }
            if (!LegacyAnvil.isLegacy(temp)) throw new IOException(zip + " is not a pre-1.13 world");
            return LegacyAnvil.toPolar(temp, edit);
        } finally {
            try (Stream<Path> paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
