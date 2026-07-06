# osproxy-rewrite

Pure request/response transforms: injecting and stripping tenancy fields
(`Fields`), constructing and inverting physical document ids (`DocIds`),
demuxing/remuxing `_bulk` and `_mget`/`_msearch` bodies (`Bulk`, `Multi`),
and wrapping a query with the mandatory partition filter (`Queries`). No
I/O anywhere in this module; Jackson streaming (`JsonParser`/
`JsonGenerator`) is the default on the hot paths (bulk NDJSON demux,
inject/strip), with tree-based (`Json`) parsing only where a transform
needs random access.

This is the module property tests live in: inject/strip and
construct/invert-id are provably symmetric by construction, verified with
jqwik rather than a handful of example-based cases.

## Depends on

- `osproxy-core`

## Key types

- `Fields`: `injectFields`/`stripFields`, streaming and tree variants.
- `DocIds`: `constructId`/`mapLogicalToPhysical` and their inverses.
- `Bulk`: NDJSON `_bulk` parsing, both buffered (`parseBulk`) and
  streaming (`parseBulkStream`) forms.
- `Multi`: `_mget`/`_msearch` demux/remux.
- `Queries`: mandatory partition-filter wrapping, buffered and streaming.
- `Json`: the shared `ObjectMapper`, plus small helpers
  (`parseObject`/`writeBytes`).
- `RewriteException`: the one checked exception every transform throws on
  malformed input, mapped to `ErrorCode.MALFORMED_REQUEST` upstream.

See [Architecture](../site/src/content/docs/03-architecture.md) (the "two
body transforms" section) and
[Performance](../site/src/content/docs/11-performance.md) for the
streaming-transform benchmarks.
