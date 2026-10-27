# Bundled maps and layers

Upload MBTiles/PMTiles, GeoJSON, style JSON, sprites, and glyphs here. This directory
is bundled as `maps/` in the APK. Configure new maps in `basemaps.json`; no Kotlin
changes are required. 

For an MBTiles source, add a `sources` entry with `archive: "maps/example.mbtiles"`,
`type` (`raster` or `vector`), zoom limits, attribution, and online `tiles` templates
or a TileJSON `url`. Optional `cacheVersion` invalidates a changed source's cache.
Add a basemap using `source` for raster maps or `style` for an uploaded MapLibre
style (e.g. `maps/example/style.json`). Style source IDs match configured sources.

For local PMTiles use `url: "pmtiles://asset://maps/example.pmtiles"`. For bundled
GeoJSON use `data: "asset://maps/example.geojson"`. `asset://maps/...` references
also work for style glyphs and sprites; include every resource for offline use.

Add toggleable groups to `overlays`: unique `id`, `name`, optional `defaultVisible`,
and standard MapLibre `sources` and `layers`. They appear in Menu → Map layers,
above the basemap and below Trail's data, and survive basemap changes.

`tools/prepare_maps` packages Tewksbury streets, Natural Earth, Bright/hybrid styles,
fonts and sprites. It selects **intersecting tiles**, including partial boundary
tiles, at every configured zoom. Streets use source zooms 0–14, then overzoom.
`offline-manifest.json` records coverage, counts, and SHA-256 hashes. A clean install
in airplane mode has a complete street map. Satellite/OSM raster/OpenTopoMap use
online/runtime caches unless archives are uploaded or authorized sources are added
to `offline-maps.json`. Do not bulk-download public OSM raster tiles.

Remote PMTiles (the optional contours example) stream through Trail’s persistent
HTTP-range cache, so previously viewed tiles work offline. This cache has its own
500 MiB limit (trimmed to 400 MiB). Upload an archive and use
`pmtiles://asset://maps/contours.pmtiles` for guaranteed coverage without first viewing
the area online. Bump `cacheVersion` when replacing a remote archive in place.

Downloads live outside Git in `.map-downloads/`. Outputs are generated under
`app/build/generated/mapAssets/`. Actions caches downloads; changing the offline
configuration refreshes them. Invalid/incomplete downloads fail the build before
replacing the last successful APK. Uploaded archives take precedence over downloads.
