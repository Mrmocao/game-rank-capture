import com.fasterxml.jackson.databind.*;
import org.jsoup.Jsoup;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Read-only database/file/evidence audit. Never prints credentials or private business rows. */
public class VerifyRankingCapture {
    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 6)
            throw new IllegalArgumentException("env-file material-root batch-id output-json [--require-top100] [--expected-count=N]");
        boolean requireTop100 = false;int expectedCount=8;
        for(int i=4;i<args.length;i++)if(args[i].equals("--require-top100"))requireTop100=true;
        else if(args[i].startsWith("--expected-count="))expectedCount=Integer.parseInt(args[i].substring("--expected-count=".length()));
        else throw new IllegalArgumentException("Unknown option");
        if(expectedCount<1)throw new IllegalArgumentException("Expected count must be positive");
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[0]))) { int p = line.indexOf('='); if (p > 0 && !line.startsWith("#")) env.put(line.substring(0, p).trim(), line.substring(p + 1).trim()); }
        var mapper = new ObjectMapper(); var results = new ArrayList<Map<String, Object>>();
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        try (var connection = DriverManager.getConnection(env.get("DB_URL"), env.get("DB_USERNAME"), env.get("DB_PASSWORD"))) {
            connection.setReadOnly(true);
            try (var query = connection.prepareStatement("SELECT r.id run_id,r.status,r.freshness,r.reused,o.* FROM external_capture_run r JOIN external_ranking_observation o ON o.id=r.observation_id AND o.tenant_id=r.tenant_id WHERE r.batch_id=?")) {
                query.setString(1, args[2]);
                try (var rs = query.executeQuery()) {
                    while (rs.next()) {
                        Path rawPath = root.resolve(rs.getString("raw_locator")).normalize();
                        if (!rawPath.startsWith(root)) throw new IllegalStateException("Material path escaped root");
                        byte[] raw = Files.readAllBytes(rawPath);
                        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
                        if (!hash.equals(rs.getString("raw_sha256"))) throw new IllegalStateException("Stored material hash differs");
                        var bundle = mapper.readTree(rs.getString("bundle_json")); var entries = bundle.path("observation").path("entries");
                        if (entries.size() != rs.getInt("actual_count")) throw new IllegalStateException("Bundle count differs");
                        if (requireTop100 && (entries.size() != 100 || rs.getInt("contiguous_top_n") != 100
                                || !rs.getString("status").equals("SUCCEEDED")
                                || !bundle.path("coverage").path("missingRanks").isEmpty()))
                            throw new IllegalStateException("Expected each chart to have continuous Top100 and successful run");
                        long id = rs.getLong("id"); int count = 0;
                        var doc = rs.getString("source_key").startsWith("gravity-") ? Jsoup.parse(new String(raw, StandardCharsets.UTF_8)) : null;
                        try (var detail = connection.prepareStatement("SELECT rank_position,name,external_id FROM external_ranking_entry WHERE observation_id=? ORDER BY rank_position")) {
                            detail.setLong(1, id);
                            try (var rows = detail.executeQuery()) {
                                while (rows.next()) {
                                    var entry = entries.get(count++);
                                    if (entry == null || entry.path("rank").asInt() != rows.getInt(1) || !entry.path("name").asText().equals(rows.getString(2)))
                                        throw new IllegalStateException("Database entry differs from bundle");
                                    if (requireTop100 && rows.getInt(1) != count) throw new IllegalStateException("Top100 has a gap");
                                    if (doc != null) {
                                        var evidence = doc.select(entry.path("evidenceLocator").asText());
                                        if (evidence.size() != 1 || !evidence.select(".rank-app-name").text().equals(rows.getString(2)))
                                            throw new IllegalStateException("DOM evidence locator does not resolve exact entry");
                                        if (rows.getString(3) != null) throw new IllegalStateException("Unexpected inferred platform identity");
                                    }
                                }
                            }
                        }
                        if (count != rs.getInt("actual_count")) throw new IllegalStateException("Database row count differs");
                        results.add(Map.of("observationId", id, "source", rs.getString("source_key"), "chart", rs.getString("board_name"),
                                "businessDate", rs.getString("business_date"), "entries", count, "status", rs.getString("status"),
                                "freshness", rs.getString("freshness"), "reused", rs.getBoolean("reused"), "sha256", hash, "evidenceVerified", true));
                    }
                }
            }
            if (results.size() != expectedCount) throw new IllegalStateException("Expected "+expectedCount+" verified observations, got "+results.size());
            int missingComments;
            try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('external_capture_run','external_ranking_observation','external_ranking_entry') AND column_comment=''")) { rs.next(); missingComments = rs.getInt(1); }
            if (missingComments != 0) throw new IllegalStateException("Capture column comments missing");
        }
        Map<String, Object> report = Map.of("batchId", args[2], "verified", true, "requireTop100", requireTop100, "observations", results,
                "totalEntries", results.stream().mapToInt(row -> ((Number)row.get("entries")).intValue()).sum());
        Files.writeString(Path.of(args[3]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardOpenOption.CREATE_NEW);
        System.out.println("VERIFIED: "+results.size()+" observations, " + report.get("totalEntries") + " entries; database, raw hashes and DOM locators agree.");
    }
}
