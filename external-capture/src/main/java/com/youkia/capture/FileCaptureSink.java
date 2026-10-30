package com.youkia.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Create a new bundle directory per run. bundle.json is written last as the completion marker. */
public final class FileCaptureSink implements CaptureSink {
    private final Path root;
    private final ObjectMapper mapper;
    private Path lastDirectory;
    public FileCaptureSink(Path root, ObjectMapper mapper) { this.root = root.toAbsolutePath().normalize(); this.mapper = mapper; }
    public Path lastDirectory() { return lastDirectory; }
    @Override public void save(Observation.Bundle bundle, RawMaterial material) throws IOException {
        Files.createDirectories(root);
        Path dir = Files.createTempDirectory(root, "capture-");
        Files.write(dir.resolve("raw.bin"), material.bytes(), StandardOpenOption.CREATE_NEW);
        // Verify stored bytes before publishing the manifest.
        var stored = new RawMaterial(material.sourceUrl(), material.capturedAt(), material.transport(), Files.readAllBytes(dir.resolve("raw.bin")));
        if (!stored.sha256().equals(bundle.evidence().sha256())) throw new IOException("Material hash mismatch");
        StringBuilder csv = new StringBuilder("\uFEFFrank,external_id,name,developer,evidence_locator\r\n");
        for (var entry : bundle.observation().entries()) csv.append(entry.rank()).append(',')
                .append(cell(entry.externalId())).append(',').append(cell(entry.name())).append(',')
                .append(cell(entry.developer())).append(',').append(cell(entry.evidenceLocator())).append("\r\n");
        Files.writeString(dir.resolve("entries.csv"), csv, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(dir.resolve("coverage.md"), "# 来源覆盖\n\n来源：" + bundle.observation().sourceKey()
                + "\n\n状态：" + bundle.coverage().status() + "\n\n实际条数：" + bundle.coverage().actualCount()
                + "\n\n连续覆盖：1–" + bundle.coverage().contiguousTopN() + "\n\n缺失名次：" + bundle.coverage().missingRanks()
                + "\n\n原始材料：raw.bin\n\nSHA-256：" + material.sha256() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(dir.resolve("bundle.json.tmp"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(dir.resolve("bundle.json.tmp"), dir.resolve("bundle.json"), StandardCopyOption.ATOMIC_MOVE);
        lastDirectory = dir;
    }
    private static String cell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && ("=+-@".indexOf(text.stripLeading().isEmpty() ? ' ' : text.stripLeading().charAt(0)) >= 0
                || "\t\r\n".indexOf(text.charAt(0)) >= 0)) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
