# AeroDataBox extension: 2026-10-25 through 2026-11-13

Completed on 2026-11-14, branch task3. Production source code unchanged.

Pipeline: AeroDataBoxBulkCollectorCli -> AeroDataBoxBulkCollector -> AeroDataBoxClient -> MongoAeroDataBoxRawStorage -> avia.raw_aerodatabox.
Normalization: MongoFlightNormalizerCli -> MongoRawFlightNormalizer -> FlightPreprocessor -> FlightRecord -> MongoFlightStorage / MongoBulkUpserts -> avia.flights.

Source files reside in src/main/java/avia, with service/, client/, storage/ subpackages respectively.
Scope: FlightDatasetPlan, SVO/LED/AER/OVB/SVX, 00-12 and 12-24 local windows, inclusive dates. Actual existing raw data confirmed this same scope.
API: https://aerodatabox.p.rapidapi.com/flights/airports/iata/{airport}/{fromLocal}/{toLocal}?direction=Both&withLeg=true&withCancelled=true&withCodeshared=false&withCargo=false&withPrivate=false&withLocation=false
Credentials: AERODATABOX_API_KEY (present, value not logged), MONGO_URI. Host is fixed in AeroDataBoxClient. Headers: X-RapidAPI-Key / X-RapidAPI-Host.
Raw responses also backed up in data/raw/YYYY-MM-DD/AIRPORT_HH-HH.json.

Idempotency: unique batch_key = airport|date|fromHour|toHour; collector skips existing batches and uses $setOnInsert upsert. Normalizer selects complete observations by FlightObservationOrder.COMPARATOR. Physical flightId = airline|number|origin|destination|scheduledDepartureUtc. Writes replace/upsert by flightId; no collection deletion.

Before: raw 540, flights 85514, min UTC 2026-08-30T23:25:00Z, max UTC 2026-10-24T20:55:00Z.
After: raw 740 (+200), flights 112703 (+27189), min UTC unchanged, max UTC 2026-11-13T20:50:00Z.
Collection: 200 requests, 200 API attempts, 200 saved, zero failed/deferred. All 20 dates complete, 10 batches per day; read-only rerun confirms all 200 exist.
Normalization: 130690 observations, 112703 unique flights, 12131 duplicate observations removed, 5856 observations without stable ID skipped, 70 old batches without collection timestamp. All 85514 existing IDs matched; 27189 inserted; zero errors/unconfirmed writes.
New local-date period has 26985 flights; another 204 additions depart Oct 24 and arrive in Oct 25 windows. Both-direction scope explains the boundary overlap.
No local departures on/after Nov 14 in normalized dataset; max UTC also before Nov 14. No Nov 14 batch requested (Nov 14 00:00 is only the endpoint of Nov 13's last window).

Operational index added: avia.flights.flightId_lookup (nonunique flightId ascending). Existing partial unique index retained. Explain originally showed COLLSCAN for equality lookup; new index gives IXSCAN. Bulk durations fell from 140-177 seconds to approximately 1.2 seconds per 5000 records. No document schema or IDs changed.

Quality for local departures Oct 25-Nov 13: total 26985; cancelled 700; null departureDelayMin 3379; negative 5312. Among all non-null delays (23606): median 6, mean 43.0217741252224, nearest-rank p90 124, p95 230, max 2845 minutes. No outlier removal or clipping.
Coverage below defines usable as not cancelled and departureDelayMin non-null; date is scheduledDepartureLocal date. No missing days. Nov 10 has the smallest new-day usable count (1049), but all ten raw windows are present.

DP202 LED-VKO: 17 flights. DP511 LED-NAL: 5. DP529 LED-KGD: 20. DP1569 LED-PEE: absent. The additional DP202 LED-SVO on Oct 27 is explicitly not counted as LED-VKO. Full dates/local scheduled times/delays below.

Repeat commands (PowerShell from repository root; existing installed JDK and local dependencies):
```powershell
$env:MONGO_URI='mongodb://127.0.0.1:27017'
$env:AERODATABOX_API_KEY=[Environment]::GetEnvironmentVariable('AERODATABOX_API_KEY','User')
# If not saved in User environment, set in this shell: $env:AERODATABOX_API_KEY='...'
$java='C:/Users/Polina/.jdks/temurin-24.0.2/bin/java.exe'
$deps=(Get-ChildItem C:/Users/Polina/.m2/repository/org/mongodb,C:/Users/Polina/.m2/repository/org/json -Recurse -Filter '*.jar').FullName -join ';'
$files=(Get-ChildItem src/main/java -Recurse -Filter '*.java').FullName
& C:/Users/Polina/.jdks/temurin-24.0.2/bin/javac.exe -encoding UTF-8 -cp $deps -d target/profiles-check $files
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
& $java -cp "target/profiles-check;$deps" avia.AeroDataBoxBulkCollectorCli --from 2026-10-25 --to 2026-11-13 --airports SVO,LED,AER,OVB,SVX
if ($LASTEXITCODE -ne 0) { throw 'Check collection log before normalization' }
& $java -cp "target/profiles-check;$deps" avia.MongoFlightNormalizerCli
```
Add --dry-run to collector command for read-only coverage verification without API calls.

Safety: no history deleted; Nov 14 not collected; delay model not trained/modified; price collections/models unchanged; flight_statistics not recalculated.
Files: this report; raw backups under data/raw/2026-10-25 through 2026-11-13; temporary Java read-only audit helpers, index helper, compiled classes and logs under target/. No production source edits.

Full after-audit output (time extremes printed by Java Date are MSK; UTC values stated above):
```text
raw=740 flights=112703
date extreme=Document{{scheduledDepartureUtc=Mon Aug 31 02:25:00 MSK 2026}}
date extreme=Document{{scheduledDepartureUtc=Fri Nov 13 23:50:00 MSK 2026}}
raw scope=[Document{{_id=SVO, count=148, min=2026-09-01, max=2026-11-13}}, Document{{_id=OVB, count=148, min=2026-09-01, max=2026-11-13}}, Document{{_id=AER, count=148, min=2026-09-01, max=2026-11-13}}, Document{{_id=SVX, count=148, min=2026-09-01, max=2026-11-13}}, Document{{_id=LED, count=148, min=2026-09-01, max=2026-11-13}}]
DP check=202 SVO 2026-10-27T17:40 delay=103
DP check=202 VKO 2026-10-25T18:15 delay=497
DP check=202 VKO 2026-10-28T21:35 delay=108
DP check=202 VKO 2026-10-29T22:15 delay=null
DP check=202 VKO 2026-10-30T17:40 delay=2
DP check=202 VKO 2026-10-31T15:50 delay=243
DP check=202 VKO 2026-11-01T16:45 delay=39
DP check=202 VKO 2026-11-02T17:40 delay=116
DP check=202 VKO 2026-11-03T17:40 delay=null
DP check=202 VKO 2026-11-04T17:30 delay=240
DP check=202 VKO 2026-11-05T22:15 delay=5
DP check=202 VKO 2026-11-06T16:45 delay=-7
DP check=202 VKO 2026-11-07T07:20 delay=-1
DP check=202 VKO 2026-11-08T16:45 delay=18
DP check=202 VKO 2026-11-10T17:40 delay=33
DP check=202 VKO 2026-11-11T15:45 delay=289
DP check=202 VKO 2026-11-12T22:15 delay=96
DP check=202 VKO 2026-11-13T16:45 delay=null
DP check=511 NAL 2026-10-28T09:50 delay=3
DP check=511 NAL 2026-10-31T09:00 delay=159
DP check=511 NAL 2026-11-04T09:00 delay=127
DP check=511 NAL 2026-11-07T09:00 delay=82
DP check=511 NAL 2026-11-11T09:00 delay=210
DP check=529 KGD 2026-10-25T07:10 delay=480
DP check=529 KGD 2026-10-26T07:20 delay=95
DP check=529 KGD 2026-10-27T07:10 delay=45
DP check=529 KGD 2026-10-28T07:10 delay=-6
DP check=529 KGD 2026-10-29T07:10 delay=24
DP check=529 KGD 2026-10-30T07:10 delay=13
DP check=529 KGD 2026-10-31T07:10 delay=0
DP check=529 KGD 2026-11-01T07:10 delay=131
DP check=529 KGD 2026-11-02T07:20 delay=104
DP check=529 KGD 2026-11-03T07:10 delay=-5
DP check=529 KGD 2026-11-04T07:10 delay=35
DP check=529 KGD 2026-11-05T07:10 delay=6
DP check=529 KGD 2026-11-06T07:10 delay=9
DP check=529 KGD 2026-11-07T07:10 delay=13
DP check=529 KGD 2026-11-08T07:10 delay=-8
DP check=529 KGD 2026-11-09T07:20 delay=163
DP check=529 KGD 2026-11-10T07:10 delay=6
DP check=529 KGD 2026-11-11T07:10 delay=335
DP check=529 KGD 2026-11-12T07:10 delay=8
DP check=529 KGD 2026-11-13T07:10 delay=124
2026-10-20 total=1398 usable=1231
2026-10-21 total=1428 usable=1264
2026-10-22 total=1469 usable=1277
2026-10-23 total=1604 usable=1260
2026-10-24 total=1543 usable=1280
2026-10-25 total=1467 usable=1281
2026-10-26 total=1384 usable=1223
2026-10-27 total=1279 usable=1117
2026-10-28 total=1365 usable=1214
2026-10-29 total=1321 usable=1141
2026-10-30 total=1502 usable=1159
2026-10-31 total=1382 usable=1175
2026-11-01 total=1443 usable=1286
2026-11-02 total=1385 usable=1234
2026-11-03 total=1259 usable=1097
2026-11-04 total=1347 usable=1197
2026-11-05 total=1312 usable=1156
2026-11-06 total=1424 usable=1247
2026-11-07 total=1333 usable=1176
2026-11-08 total=1386 usable=1238
2026-11-09 total=1356 usable=1205
2026-11-10 total=1186 usable=1049
2026-11-11 total=1289 usable=1127
2026-11-12 total=1282 usable=1151
2026-11-13 total=1283 usable=1133
new period total=26985 cancelled=700 null=3379 negative=5312
median=6.0 mean=43.0217741252224 p90=124 p95=230 max=2845
```
