import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

class ProfileLockHolder {
    public static void main(String[] args) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(args[0], "rw");
             FileLock lock = file.getChannel().lock()) {
            Files.writeString(Path.of(args[1]), "locked");
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (!Files.exists(Path.of(args[2]))) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Fixture timed out");
                Thread.sleep(25);
            }
        }
    }
}
