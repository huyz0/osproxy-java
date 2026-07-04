package io.osproxy.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.spi.InjectedField;
import io.osproxy.spi.InjectedValue;
import io.osproxy.spi.Placement;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The distributed placement store: polls a placement document from any HTTP
 * source and applies it to the {@link io.osproxy.tenancy.PlacementTable} —
 * the fleet-coherent "which partition lives where" the Rust project keeps
 * behind its MigrationStore seam. Same posture as the directive poller:
 * fail-closed decoding (an unknown mode or key refuses the whole document)
 * and keep-last-good through outages.
 *
 * <p>Apply-if-changed: an entry is written to the table only when it differs
 * from what the table holds, so re-polling an unchanged document never bumps
 * epochs — an epoch bump means a partition actually moved, and stale writes
 * are refused because of it. Orchestrated migrations (drain → cutover →
 * complete) go through {@link io.osproxy.tenancy.MigrationControl}; this
 * store is the convergence path for simple placement flips.
 *
 * <p>Wire format:
 * <pre>{@code
 * {"partitions": {
 *    "acme":   {"mode": "dedicated-index", "cluster": "c2", "index": "acme-idx"},
 *    "globex": {"mode": "shared-index",    "cluster": "c1", "index": "shared"},
 *    "initech":{"mode": "dedicated-cluster", "cluster": "initech-c"}}}
 * }</pre>
 */
public final class PollingPlacementStore implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final URI source;
    private final io.osproxy.tenancy.PlacementTable table;
    private final Thread poller;
    private volatile boolean running = true;

    public PollingPlacementStore(
            String sourceUrl, io.osproxy.tenancy.PlacementTable table, long pollMillis) {
        this.source = URI.create(sourceUrl);
        this.table = table;
        this.poller = Thread.ofVirtual()
                .name("osproxy-placement-poller")
                .start(() -> pollLoop(pollMillis));
    }

    /** The document failed validation; the message names the offender. */
    public static final class InvalidPlacements extends Exception {
        InvalidPlacements(String message) {
            super(message);
        }
    }

    /** Decodes a placement document, refusing anything unknown. */
    static Map<PartitionId, Placement> decode(byte[] body) throws InvalidPlacements {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (java.io.IOException e) {
            throw new InvalidPlacements("body is not valid json");
        }
        if (!(root instanceof ObjectNode obj) || obj.size() != 1 || !obj.has("partitions")) {
            throw new InvalidPlacements("document must be exactly {\"partitions\":{...}}");
        }
        if (!(obj.get("partitions") instanceof ObjectNode partitions)) {
            throw new InvalidPlacements("partitions must be an object");
        }
        Map<PartitionId, Placement> out = new LinkedHashMap<>();
        var names = partitions.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            out.put(new PartitionId(name), decodeEntry(name, partitions.get(name)));
        }
        return out;
    }

    private static Placement decodeEntry(String partition, JsonNode entry)
            throws InvalidPlacements {
        if (!(entry instanceof ObjectNode obj)) {
            throw new InvalidPlacements(partition + ": entry must be an object");
        }
        var keys = obj.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.equals("mode") && !key.equals("cluster") && !key.equals("index")) {
                throw new InvalidPlacements(partition + ": unknown key " + key);
            }
        }
        String mode = obj.path("mode").asText("");
        String cluster = obj.path("cluster").asText("");
        if (cluster.isEmpty()) {
            throw new InvalidPlacements(partition + ": cluster is required");
        }
        return switch (mode) {
            case "dedicated-cluster" -> new Placement.DedicatedCluster(new ClusterId(cluster));
            case "dedicated-index" -> new Placement.DedicatedIndex(
                    new ClusterId(cluster), requireIndex(partition, obj));
            case "shared-index" -> new Placement.SharedIndex(
                    new ClusterId(cluster), requireIndex(partition, obj),
                    List.of(new InjectedField(
                            ReferenceTenancy.TENANT_FIELD,
                            InjectedValue.PartitionIdValue.INSTANCE)));
            default -> throw new InvalidPlacements(partition + ": unknown mode " + mode);
        };
    }

    private static IndexName requireIndex(String partition, ObjectNode obj)
            throws InvalidPlacements {
        String index = obj.path("index").asText("");
        if (index.isEmpty()) {
            throw new InvalidPlacements(partition + ": index is required for this mode");
        }
        return new IndexName(index);
    }

    private void pollLoop(long pollMillis) {
        while (running) {
            pollOnce();
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * One fetch+decode+apply attempt (package-visible for tests).
     * Synchronized: the change detection in {@link #apply} is
     * read-compare-write, so two overlapping polls of the same document
     * could both see "changed" and double-bump an epoch.
     */
    synchronized void pollOnce() {
        try {
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(source)
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                apply(decode(response.body()));
            }
        } catch (Exception e) {
            // Fetch or decode failed: keep the last good placements.
        }
    }

    /** Writes only real changes, so unchanged entries keep their epoch. */
    private void apply(Map<PartitionId, Placement> desired) {
        Map<PartitionId, Placement> current = table.snapshot();
        for (Map.Entry<PartitionId, Placement> entry : desired.entrySet()) {
            if (!entry.getValue().equals(current.get(entry.getKey()))) {
                table.put(entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public void close() {
        running = false;
        poller.interrupt();
    }
}
