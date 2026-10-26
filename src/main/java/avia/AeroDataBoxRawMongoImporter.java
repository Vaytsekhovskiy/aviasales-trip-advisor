package avia;

import avia.storage.MongoAeroDataBoxRawStorage;
import org.bson.Document;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/** Imports existing raw files only; does not call either flight API. */
public final class AeroDataBoxRawMongoImporter {
    public record Failure(Path file, String errorType) { }
    public record Summary(int filesFound, int inserted, int updated, List<Failure> failures) { }

    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("Usage: avia.AeroDataBoxRawMongoImporter [data/raw]");
            System.exit(1);
        }
        if (System.getenv("MONGO_URI") == null || System.getenv("MONGO_URI").isBlank()) {
            System.err.println("MONGO_URI is required. Set it in the process environment / IntelliJ Run Configuration.");
            System.exit(1);
        }
        try {
            List<Path> files = findFiles(Path.of(args.length == 0 ? "data/raw" : args[0]));
            Summary summary;
            try (var storage = new MongoAeroDataBoxRawStorage()) {
                summary = importFiles(files, storage::save);
            }
            printSummary(summary);
            if (!summary.failures().isEmpty()) System.exit(1);
        } catch (Exception e) {
            // Driver exception messages may contain connection details; do not print them.
            System.err.println("Import could not start or finish (" + e.getClass().getSimpleName()
                    + "). Check the input directory, MONGO_URI, Atlas network access and write/index permissions.");
            System.exit(1);
        }
    }

    public static List<Path> findFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("Raw directory not found");
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    /** A sink returning true for inserts allows offline behavioral checks without Atlas. */
    public static Summary importFiles(List<Path> files, Predicate<Document> save) {
        int inserted = 0, updated = 0;
        List<Failure> failures = new ArrayList<>();
        for (Path file : files) {
            try {
                String json = Files.readString(file);
                Document document = MongoAeroDataBoxRawStorage.toDocument(file, json, Instant.now());
                if (save.test(document)) inserted++; else updated++;
            } catch (Exception e) {
                failures.add(new Failure(file, e.getClass().getSimpleName()));
            }
        }
        return new Summary(files.size(), inserted, updated, List.copyOf(failures));
    }

    private static void printSummary(Summary summary) {
        System.out.println("MongoDB AeroDataBox raw import\n");
        System.out.println("Files found: " + summary.filesFound());
        System.out.println("Inserted: " + summary.inserted());
        System.out.println("Updated: " + summary.updated());
        System.out.println("Failed: " + summary.failures().size());
        System.out.println("\nDatabase: " + MongoAeroDataBoxRawStorage.DATABASE);
        System.out.println("Collection: " + MongoAeroDataBoxRawStorage.COLLECTION);
        for (Failure failure : summary.failures()) {
            System.out.println("Failed file: " + failure.file() + " (" + failure.errorType() + ")");
        }
    }
}
