# osproxy-config

Typed configuration: `ProxyConfig` loads and validates every setting from
Helidon `Config` (a YAML/properties file merged with `OSPROXY_*`
environment overrides), fully validated before any socket opens, a bad
value is a `ConfigException` naming the field, not a runtime surprise
later. No business logic lives here, just load, validate, and hand back an
immutable record.

## Depends on

- `osproxy-core`

## Key types

- `ProxyConfig`: the one typed config record the reference server builds
  everything from; also has a `Builder` for tests that don't want to load
  from a file.

See [Configuration](../site/src/content/docs/07-configuration.md) for
every setting, its key, default, and meaning.
