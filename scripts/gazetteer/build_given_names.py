"""Regenerate the offline dictionary. Python 3.10+, stdlib and curl only.

Downloads stay in memory. Only the gzip resource and a small provenance report
are written. No network is used by the Java runtime. See docs/gazetteer-sources.md.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import csv
import gzip
import hashlib
import io
import json
from pathlib import Path
import os
import subprocess
import tempfile
import sys
import time
import unicodedata
from urllib.parse import urlencode
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
INE_URL = "https://www.ine.es/daco/daco42/nombyapel/nombres_por_edad_media.xlsx"
ENDPOINT = "https://query.wikidata.org/sparql"
USER_AGENT = "doc-anonymizer-gazetteer-build/1.0 (offline dictionary build)"
# Languages normally written in Latin script; also enforce script on each label.
LANGUAGES = "en es ca gl eu fr de it pt nl af da sv no nb nn fi is ga gd cy br kw oc co ro pl cs sk sl hr bs sq hu tr az uz tk et lv lt la eo id ms vi sw zu xh st tn sn so ha yo ig mt lb fo rm ast an sc sco bar als lij lmo nap pms vec wa fur lad nah qu gn mg mi sm tl ceb jv su ht io ia vo".split()
NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def atomic_write(path, payload):
    """Close a same-directory temporary file before replacing (also on Windows).

    A failed write/replace leaves the old destination intact and may leave the
    temporary file for diagnosis. No destination is truncated in place.
    """
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".",
                                     suffix=".tmp", delete=False) as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())
        temporary = stream.name
    os.replace(temporary, path)


def download(url, accept=None):
    """Use the OS TLS stack via curl; never disable certificate verification."""
    for attempt in range(4):
        # Retry whole subprocesses, not curl's stdout stream: a partial transfer
        # must not be concatenated with the next response.
        completed = subprocess.run(
            ["curl", "--fail", "--silent", "--show-error", "--location",
             "--compressed", "--connect-timeout", "30", "--max-time", "180",
             "--user-agent", USER_AGENT,
             *(["--header", "Accept: " + accept] if accept else []), url], stdout=subprocess.PIPE)
        if completed.returncode == 0:
            return completed.stdout
        if attempt < 3:
            print(f"curl exit {completed.returncode}; retry {attempt + 1}/3", flush=True)
            time.sleep(5 * (attempt + 1))
    raise RuntimeError(f"Download failed after four attempts (curl exit {completed.returncode})")


def sparql(query):
    return json.loads(download(ENDPOINT + "?" + urlencode({"query": query, "format": "json"})))["results"]["bindings"]


def sparql_labels(query):
    # CSV avoids multi-megabyte per-label JSON metadata and reduces partial
    # transfers through proxies. Only labels are needed for the dictionary.
    data = download(ENDPOINT + "?" + urlencode({"query": query}), accept="text/csv")
    reader = csv.DictReader(io.StringIO(data.decode("utf-8-sig")))
    if reader.fieldnames != ["label"]:
        raise RuntimeError(f"Unexpected Wikidata CSV columns: {reader.fieldnames}")
    return [row["label"] for row in reader]


def canonical(name):
    # Match Java CanonicalForm.forCompare for these letter-only name entries.
    return "".join(c for c in unicodedata.normalize("NFD", name)
                   if c.isalpha() and unicodedata.category(c) != "Mn").lower()


def normalize(raw):
    name = unicodedata.normalize("NFC", raw.strip())
    letters = 0
    for i, char in enumerate(name):
        if char.isalpha():
            if "LATIN" not in unicodedata.name(char, ""):
                return None
            letters += 1
        elif char in "-'’":
            if i == 0 or i == len(name) - 1 or not name[i - 1].isalpha() or not name[i + 1].isalpha():
                return None
        elif unicodedata.category(char) == "Mn" and i > 0:
            # NFC may retain marks for letters without a precomposed equivalent.
            continue
        else:
            return None
    return name if letters >= 2 else None


def normalized_set(raw):
    # Deterministic representative when accent/case variants collapse.
    result = {}
    for raw_name in raw:
        name = normalize(raw_name)
        if name:
            key = canonical(name)
            result[key] = min(name, result.get(key, name))
    return result


def ine_names():
    data = download(INE_URL)
    names = []
    headers = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        strings = ["".join(si.itertext()) for si in ET.fromstring(archive.read("xl/sharedStrings.xml"))]
        for sheet in sorted(p for p in archive.namelist() if p.startswith("xl/worksheets/sheet") and p.endswith(".xml")):
            for _, row in ET.iterparse(io.BytesIO(archive.read(sheet)), events=("end",)):
                if row.tag != "{" + NS["s"] + "}row":
                    continue
                cells = {}
                for cell in row.findall("s:c", NS):
                    value = cell.findtext("s:v", default="", namespaces=NS)
                    if cell.get("t") == "s" and value:
                        value = strings[int(value)]
                    cells["".join(c for c in cell.get("r", "") if c.isalpha())] = value
                if cells.get("C", "").replace(".", "", 1).isdigit() and cells.get("B"):
                    names.append(cells["B"])
                elif cells.get("A"):
                    headers.append(cells["A"])
                row.clear()
    if not names:
        raise RuntimeError("INE spreadsheet layout changed: no name/frequency rows found")
    return names, {"url": INE_URL, "sha256": hashlib.sha256(data).hexdigest(), "headers": headers}


def wikidata_names():
    names = []
    digest = hashlib.sha256()
    language_filter = ",".join(json.dumps(lang) for lang in LANGUAGES)
    page_size = 5000
    # Resolve the small class closure first; do not join a recursive path to
    # every label (that query times out on the public endpoint).
    classes = sparql('''SELECT DISTINCT ?class WHERE {
        ?class wdt:P279* wd:Q202444 .
    } ORDER BY ?class''')
    class_counts = {}
    for row in classes:
        class_id = row["class"]["value"].rsplit("/", 1)[-1]
        count = int(sparql(f'''SELECT (COUNT(?item) AS ?count) WHERE {{
            ?item wdt:P31 wd:{class_id} .
        }}''')[0]["count"]["value"])
        if not count:
            continue
        class_counts[class_id] = count
        for offset in range(0, count, page_size):
            # Page items first: all labels of each item land in the same page.
            query = f'''SELECT ?label WHERE {{
              hint:Query hint:optimizer "None" .
              {{ SELECT ?item WHERE {{
                ?item wdt:P31 wd:{class_id} .
              }} ORDER BY ?item LIMIT {page_size} OFFSET {offset} }}
              ?item rdfs:label ?label .
              FILTER(LANG(?label) IN ({language_filter}))
            }} ORDER BY ?item ?label'''
            rows = sparql_labels(query)
            digest.update(json.dumps(rows, ensure_ascii=False).encode("utf-8"))
            names.extend(rows)
            print(f"Wikidata {class_id} ({count:,} items), offset {offset}: {len(rows):,} labels", flush=True)
    return names, {"endpoint": ENDPOINT, "languages": LANGUAGES,
                   "class_counts": class_counts, "page_size": page_size,
                   "response_sha256": digest.hexdigest()}


def ssa_names(path, minimum):
    totals = Counter()
    data = path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        files = sorted(p for p in archive.namelist() if p.startswith("yob") and p.endswith(".txt"))
        if not files:
            raise ValueError("SSA zip contains no yob*.txt files")
        for filename in files:
            with archive.open(filename) as stream:
                for name, sex, count in csv.reader(io.TextIOWrapper(stream, encoding="utf-8-sig")):
                    totals[name] += int(count)
    return [name for name, total in totals.items() if total >= minimum], {
        "sha256": hashlib.sha256(data).hexdigest(), "years": len(files), "minimum_total": minimum}


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssa-zip", type=Path, help="optional official names.zip downloaded in a browser")
    parser.add_argument("--ssa-min-total", type=int, default=10, help="minimum SSA count across years/sexes (default: 10)")
    args = parser.parse_args()
    if args.ssa_min_total < 5:
        parser.error("--ssa-min-total must be >= 5")
    report = {"retrieved_utc": datetime.now(timezone.utc).isoformat(), "sources": {}}
    combined = {}
    exclusions = {canonical(line.strip()) for line in (ROOT / "scripts/gazetteer/exclusions.txt").read_text(encoding="utf-8").splitlines()
                  if line.strip() and not line.lstrip().startswith("#")}
    sources = [("INE", ine_names), ("Wikidata", wikidata_names)]
    if args.ssa_zip:
        sources.append(("SSA", lambda: ssa_names(args.ssa_zip, args.ssa_min_total)))
    else:
        print("SSA skipped: no --ssa-zip supplied (browser download required)", flush=True)
        report["sources"]["SSA"] = {"skipped": True, "reason": "No local --ssa-zip supplied"}
    for source, loader in sources:
        raw, metadata = loader()
        normalized = normalized_set(raw)
        rejected = sorted(set(normalized) & exclusions)
        accepted = {key: value for key, value in normalized.items() if key not in exclusions}
        before = len(combined)
        for key, value in accepted.items():
            combined[key] = min(value, combined.get(key, value))
        metadata.update(raw_entries=len(raw), normalized_unique=len(normalized),
                        excluded=len(rejected), excluded_words=rejected,
                        accepted_unique=len(accepted), added_to_union=len(combined) - before)
        report["sources"][source] = metadata
        print(f"{source}: raw={len(raw):,}, normalized={len(normalized):,}, excluded={len(rejected):,}, accepted={len(accepted):,}, added={len(combined)-before:,}", flush=True)
    if len(combined) <= 100000:
        raise RuntimeError(f"Coverage guard: only {len(combined):,} names; existing output not replaced")
    payload = ("\n".join(combined[key] for key in sorted(combined)) + "\n").encode("utf-8")
    resource = ROOT / "src/main/resources/gazetteer/nombres-intl.txt.gz"
    # Stable gzip header: identical source content produces identical bytes.
    compressed = gzip.compress(payload, compresslevel=9, mtime=0)
    report.update(dictionary_size=len(combined), gzip_bytes=len(compressed),
                  gzip_sha256=hashlib.sha256(compressed).hexdigest(), exclusion_count=len(exclusions))
    atomic_write(resource, compressed)
    atomic_write(ROOT / "scripts/gazetteer/build-report.json",
                 (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    print(f"Final: {len(combined):,} names; gzip {len(compressed):,} bytes; {resource.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
