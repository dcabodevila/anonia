# Offline international given-name dictionary

`ResourceGazetteer` combines the compatibility list `nombres-es.txt` with
`nombres-intl.txt.gz`. The generated resource contains **116,031 canonical-unique
names**; the default Java gazetteer contains **116,032** including legacy `Mª`.
Runtime loading is entirely offline. No detector changes or surname dictionary
are included in this work.

## Regenerate

From the repository root, with Python 3.10+ and `curl` on PATH:

```sh
python scripts/gazetteer/build_given_names.py
mvn -o -Dtest=ResourceGazetteerTest,ForeignNameCorpusEndToEndTest test
mvn -o test
```

The script downloads INE and Wikidata using `curl` with certificate verification
and the generic User-Agent `doc-anonymizer-gazetteer-build/1.0 (offline dictionary build)`.
It keeps downloads in memory and writes only the gzip resource and the small
`scripts/gazetteer/build-report.json` provenance report. Failed downloads or a
union of 100,000 names or fewer abort before replacing existing outputs.
Regeneration can take tens of minutes; endpoint errors are retried. The report
records source hashes, retrieval time, class counts and actual excluded words.

### Optional SSA input

SSA's CDN returned HTTP 403 to automated clients in this environment. **SSA was
not included in this build.** Download the official archive in a browser:

<https://www.ssa.gov/oact/babynames/names.zip>

Then regenerate with the downloaded public-data archive (do not commit it):

```sh
python scripts/gazetteer/build_given_names.py --ssa-zip path/to/names.zip
```

The script sums counts across all years and both sexes, retaining names with at
least **10 total occurrences**. `--ssa-min-total` can raise that threshold; values
below 5 are rejected. Without `--ssa-zip`, the skip is explicit in stdout and the
provenance report. The optional merge implementation has not been exercised with
a real SSA archive in this run.

## Sources and rights

Retrieved **2026-10-02**. These are derived name spellings, not population
statistics. Normalization and exclusions are our processing, not source claims.
No source endorses this application.

| Source | Official data and coverage | License / terms |
|---|---|---|
| INE, Spain | [Residents' names by mean age, XLSX](https://www.ine.es/daco/daco42/nombyapel/nombres_por_edad_media.xlsx), both male/female sheets; names with national frequency ≥20. Annual population census reference date **2025-01-01**, published **2026-04-23** per the [INE operation page](https://www.ine.es/dyngs/INEbase/operacion.htm?c=Estadistica_C&cid=1254736177009&menu=ultiDatos&idp=1254735572981). | [INE reuse notice](https://www.ine.es/ss/Satellite?c=Page&cid=1254735849170&pagename=INE%2FINELayout&L=0): permits commercial/noncommercial reuse of INE-origin information, with source attribution, update date where supplied, no distortion of meaning or implied endorsement; reuse at the reuser's risk. The footer links CC BY 4.0 but its tooltip says BY-SA; this document relies on the explicit reuse notice rather than resolving that inconsistent footer. |
| Wikidata | [SPARQL endpoint](https://query.wikidata.org/sparql); items whose `P31` is Q202444 (given name) or any transitive `P279` subclass, including male Q12308941, female Q11879590 and unisex classes. Labels are restricted to the Latin-script language allowlist in the script and individually checked for Latin letters. | Structured data: **[CC0](https://www.wikidata.org/wiki/Wikidata:Licensing)**. Only structured labels and class relations are used, not wiki prose. |
| SSA, US (optional; skipped) | [Official national baby-name archive](https://www.ssa.gov/oact/babynames/names.zip), Social Security card applications from 1880 onward. | **CC0 / public domain**, as recorded in the official [Data.gov catalog](https://catalog.data.gov/dataset/baby-names-from-social-security-card-applications-national-data). The catalog links the same SSA ZIP, not a separate working mirror. |

INE attribution for processed data:
**Elaboración propia con datos extraídos del sitio web del INE: www.ine.es**.

## Build counts

Counts are spellings, not people. Wikidata raw labels include repeated spellings
across languages and classes. Counts below are after canonical deduplication.

| Source | Raw entries/labels | Normalized unique | Excluded | Accepted | Added to union |
|---|---:|---:|---:|---:|---:|
| INE | 63,440 | 20,837 | 14 | 20,823 | 20,823 |
| Wikidata | 3,174,491 | 108,897 | 34 | 108,863 | 95,208 |
| SSA | skipped | — | — | — | — |

Final gzip: **116,031 lines, 368,159 bytes**. Sorted output, a deterministic
representative for each canonical key, and a zero gzip timestamp make identical
inputs byte-stable. Live Wikidata is not a frozen snapshot: regeneration can
change counts, and edits during paginated retrieval can shift page boundaries.
The report identifies this particular build.

## Normalization and exclusions

- Strip surrounding whitespace and normalize to NFC.
- Accept Latin-letter names with internal hyphens or straight/curly apostrophes;
  reject spaces, digits, other punctuation and single-letter entries. Combining
  accents that remain after NFC are preserved in spellings.
- Deduplicate using NFD, dropping nonspacing marks and punctuation, then
  lowercasing, matching Java's comparison for these entries.
- Apply `scripts/gazetteer/exclusions.txt` using the same canonical key.

The **163 exclusion keys** cover Spanish/Catalan/Galician function words and
particles, legal/administrative/address headings, and clear document nouns and
calendar words. Actual removed entries include `Real`, `Sala`, `General`, `Caja`,
`Ley`, `Firma`, `de`, `del`, `la` and `el`; see the report for all source matches.
Ambiguous given names **Rosa, Paz, Grace, Victoria, Mercedes and Pilar** remain.
Calendar words with established person-name use (`Julio`, `Domingo`, `Abril`,
`Mayo`) also remain. No names were manually added to the international resource.

All existing negative controls passed without additional control-driven
exclusions or weakened assertions. T4 and the redaction-only former T6 corpus
group are enabled; T5 remains disabled.

## Runtime cost and verification

The default gazetteer is a lazily initialized immutable `HashSet` shared across
pipelines; custom resource-path construction still loads only that resource and
supports both plain UTF-8 and gzip. `PipelineFactory`'s user-name union is unchanged.

The focused test measured an **uncached gzip load in a warmed JVM: 30.5 ms**,
**25,165,824-byte heap delta during loading**, and approximately **10,485,640 bytes
retained after explicit GC**. These are local, GC-sensitive estimates, not a
performance guarantee. Repeated default construction reuses the shared set.

TDD evidence:

- Initial focused RED: 56 tests, 20 behavioral failures, no errors, one skipped
  group (T5); all seven T4 names and Paz/Grace leaked.
- First GREEN: 56 tests, no failures/errors, one skipped group.
- Canonical-uniqueness regression RED: 58 tests, one intended failure
  (`Abdalaziz` duplicated after Java removes internal punctuation).
- After aligning the generator's canonical key and regenerating: 58 tests,
  no failures/errors, one skipped group.
- Final focused run, including additional ambiguity/exclusion controls: 73 tests,
  no failures/errors, one skipped group.
- Final `mvn -o test`: 317 tests, no failures/errors, one skipped group (T5).
  `SecurityCorpusEndToEndTest` (31), `DetectionClassificationTest` (11),
  `PipelineFactoryTest` (7) and all seven foreign-corpus negative controls passed.

The exact focused command is shown above. No confidence lowering or detector
changes were made.
