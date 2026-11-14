# Price and historical-delay report

Run `avia.MongoOfferDashboardCli` with `MONGO_URI` in the environment.
No arguments required. Default output: `output/offer-dashboard.html`.
An optional first argument overrides the output path. Open the resulting file
in a browser. All CSS, JavaScript and selected data are embedded; no server, CDN,
API token or internet is required after generation. The HTML contains dataset
snapshots: share it only with people who should have access to those data.

The CLI only reads `avia.enriched_flight_offers`. It creates no collections or
indexes, never calls Aviasales, and leaves the AeroDataBox report unchanged.
It reuses existing offer/history models. Metrics are displayed, not recomputed.

Features: overall snapshot counts, filters by directed airport route, airline,
local departure date, currency, history status and minimum historical sample;
sorting by local departure, currency/price, median or historical sample size;
50-row pagination; full historical metrics in expandable details; a price vs.
historical median scatter plot for one currency at a time. All outliers remain.
Currency filtering and chart currency are independent and explicitly labelled.
All price snapshots remain separate, including repeated observations of a departure.

N is the historical physical-flight sample, including cancellations, not the
number of price offers or the valid-delay denominator. Missing history and null
metrics are not displayed as zero. `INVALID_OFFER` records appear in a separate
quality section. KPI cards and that section cover the entire source; filters
affect only the offer table and scatter plot. No predictions or live prices.

Tests: run `avia.OfferDashboardTest` (standalone, no Mongo), then
`node src/test/js/offer-dashboard-smoke.cjs target/offer-dashboard-test.html`.
The generated test HTML uses synthetic fixtures, not the real Atlas dataset.
