"""Build the offline INE surname dictionary (Python 3.10+, stdlib and curl).

INE currently publishes BIFF8 XLS, not XLSX, for this complete table. Downloads
stay in memory. Resources and provenance are atomically replaced only after
layout/coverage checks. See docs/gazetteer-sources.md for rights and limitations.
"""
from datetime import datetime, timezone
import gzip
import hashlib
import json
import sys

from build_given_names import ROOT, atomic_write, canonical, download, normalized_set
from ine_xls import sheets

INE_URL = "https://www.ine.es/daco/daco42/nombyapel/apellidos_frecuencia.xls"


def ine_surnames(data):
    names = []
    metadata = []
    for title, rows in sheets(data):
        if rows.get(4, {}).get(1) != "Apellido" or rows.get(3, {}).get(2) != "Apellido 1º" \
                or rows.get(3, {}).get(3) != "Apellido 2º":
            raise ValueError("INE surname spreadsheet layout changed")
        accepted = 0
        second_available = 0
        for row, cells in sorted(rows.items()):
            if row < 5:
                continue
            surname, first, second = cells.get(1), cells.get(2), cells.get(3)
            if not isinstance(surname, str) or not isinstance(first, (int, float)) or first < 20:
                raise ValueError(f"Unexpected surname/frequency row: {title}, row {row + 1}")
            # The published rows are selected by first-surname frequency >=20.
            # Column D supplies second-surname counts for the same spellings;
            # hidden helper columns are never used (they contain mask sentinels).
            names.append(surname)
            accepted += 1
            second_available += isinstance(second, (int, float)) and second >= 20
        metadata.append({"sheet": title, "rows": accepted,
                         "second_surname_frequency_at_least_20": second_available,
                         "headers": [rows.get(i, {}).get(0, "") for i in range(3)]})
    if len(metadata) != 2 or len(names) < 50000:
        raise ValueError("Expected both INE frequency sheets and at least 50,000 rows")
    return names, metadata


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    data = download(INE_URL)
    raw, sheet_metadata = ine_surnames(data)
    normalized = normalized_set(raw)
    exclusion_bytes = (ROOT / "scripts/gazetteer/exclusions.txt").read_bytes()
    exclusions = {canonical(line.strip()) for line in exclusion_bytes.decode("utf-8").splitlines()
                  if line.strip() and not line.lstrip().startswith("#")}
    rejected = sorted(set(normalized) & exclusions)
    accepted = {key: value for key, value in normalized.items() if key not in exclusions}
    if len(accepted) < 10000:
        raise ValueError("Surname coverage guard failed; existing resources not replaced")
    for key in ("garcia", "perez", "lopez", "ruiz", "kowalski"):
        if key not in accepted:
            raise ValueError(f"Missing reference surname {key}; existing resources not replaced")
    payload = ("\n".join(accepted[key] for key in sorted(accepted)) + "\n").encode("utf-8")
    compressed = gzip.compress(payload, compresslevel=9, mtime=0)
    report = {"retrieved_utc": datetime.now(timezone.utc).isoformat(), "url": INE_URL,
              "source_sha256": hashlib.sha256(data).hexdigest(), "source_bytes": len(data),
              "sheets": sheet_metadata, "raw_entries": len(raw),
              "normalized_unique": len(normalized), "excluded": len(rejected),
              "excluded_words": rejected, "dictionary_size": len(accepted),
              "gzip_bytes": len(compressed), "gzip_sha256": hashlib.sha256(compressed).hexdigest(),
              "exclusion_count": len(exclusions)}
    atomic_write(ROOT / "src/main/resources/gazetteer/apellidos.txt.gz", compressed)
    atomic_write(ROOT / "src/main/resources/gazetteer/exclusions.txt", exclusion_bytes)
    atomic_write(ROOT / "scripts/gazetteer/surname-build-report.json",
                 (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
