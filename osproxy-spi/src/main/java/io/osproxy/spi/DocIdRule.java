package io.osproxy.spi;

/**
 * How a logical document id maps to the physical id stored upstream. The
 * template references {@code {partition}} and {@code {id}}; a shared-index
 * placement's rule MUST reference the partition or the router fails closed
 * (two tenants' documents could otherwise collide on one physical id).
 *
 * @param template e.g. {@code "{partition}:{id}"}
 * @param setRouting also send OpenSearch {@code routing=<partition>} so a
 *     partition's documents land on deterministic shards
 */
public record DocIdRule(String template, boolean setRouting) {
    public DocIdRule {
        if (template == null || !template.contains("{id}")) {
            throw new IllegalArgumentException("id template must reference {id}");
        }
    }

    /** Whether the physical id embeds the partition (required for SharedIndex). */
    public boolean referencesPartition() {
        return template.contains("{partition}");
    }
}
