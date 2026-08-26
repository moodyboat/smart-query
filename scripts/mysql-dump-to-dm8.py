#!/usr/bin/env python3
"""Convert a mysqldump containing smart-query schemas into DM8 import files.

The target DM8 instance must use CASE_SENSITIVE=0 and COMPATIBLE_MODE=4.
This converter intentionally drops MySQL-only indexes/foreign keys; application
data and primary/unique keys are retained. Secondary indexes can be recreated
after the data import when needed.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path


SCHEMA_MAP = {
    "smart_query": "SMART_QUERY",
    "smart_query_sample": "SMART_QUERY_SAMPLE",
}


def decode_mysql_string_escapes(sql: str) -> str:
    """Translate mysqldump backslash escapes to DM/standard SQL literals."""
    decoded: list[str] = []
    in_string = False
    index = 0
    replacements = {
        "0": "\0",
        "b": "\b",
        "n": "\n",
        "r": "\r",
        "t": "\t",
        "Z": "\x1a",
        '"': '"',
        "\\": "\\",
    }
    while index < len(sql):
        char = sql[index]
        if char == "'":
            if in_string and index + 1 < len(sql) and sql[index + 1] == "'":
                decoded.extend(("'", "'"))
                index += 2
                continue
            in_string = not in_string
            decoded.append(char)
            index += 1
            continue
        if in_string and char == "\\" and index + 1 < len(sql):
            escaped = sql[index + 1]
            if escaped == "'":
                decoded.extend(("'", "'"))
            elif escaped in replacements:
                decoded.append(replacements[escaped])
            else:
                decoded.extend(("\\", escaped))
            index += 2
            continue
        decoded.append(char)
        index += 1
    return "".join(decoded)


def clean_schema_sql(source: str, mysql_schema: str, dm_schema: str) -> str:
    use_marker = f"USE `{mysql_schema}`;"
    start = source.find(use_marker)
    if start < 0:
        raise ValueError(f"schema not found in dump: {mysql_schema}")
    start += len(use_marker)

    following = [
        source.find(f"-- Current Database: `{name}`", start)
        for name in SCHEMA_MAP
        if name != mysql_schema
    ]
    following = [position for position in following if position >= 0]
    end = min(following) if following else len(source)
    body = source[start:end]

    # Remove mysqldump session directives and locking statements.
    body = re.sub(r"^/\*![0-9]{5}.*?\*/;?\s*$", "", body, flags=re.MULTILINE)
    body = re.sub(r"^LOCK TABLES.*?$", "", body, flags=re.MULTILINE)
    body = re.sub(r"^UNLOCK TABLES;\s*$", "", body, flags=re.MULTILINE)

    # Remove MySQL table options and column modifiers unsupported by DM8.
    body = re.sub(r"\)\s*ENGINE=InnoDB[^;]*;", ");", body, flags=re.IGNORECASE)
    body = re.sub(r"\s+ON UPDATE CURRENT_TIMESTAMP", "", body, flags=re.IGNORECASE)
    body = re.sub(r"\s+CHARACTER SET\s+\w+", "", body, flags=re.IGNORECASE)
    body = re.sub(r"\s+COLLATE\s+\w+", "", body, flags=re.IGNORECASE)
    body = re.sub(r"\b(tinyint|smallint|mediumint|int|bigint)\(\d+\)", r"\1", body, flags=re.IGNORECASE)
    body = re.sub(
        r"\benum\(.*\)(?=\s+(?:NOT\s+NULL|DEFAULT|COMMENT))",
        "VARCHAR(50)",
        body,
        flags=re.IGNORECASE,
    )

    # Process CREATE TABLE lines without touching JSON/text values in INSERTs.
    output_lines: list[str] = []
    in_create = False
    for line in body.splitlines():
        stripped = line.strip()
        if stripped.upper().startswith("CREATE TABLE"):
            in_create = True
        if in_create:
            line = re.sub(r"\blongtext\b", "CLOB", line, flags=re.IGNORECASE)
            line = re.sub(r"\bjson\b", "CLOB", line, flags=re.IGNORECASE)
            # Drop secondary indexes and foreign keys for a deterministic bulk import.
            if re.match(r"^\s*(KEY|FULLTEXT(?:\s+KEY)?)\s+", line, flags=re.IGNORECASE):
                continue
            if re.match(r"^\s*(CONSTRAINT\s+\S+\s+)?FOREIGN KEY\s*", line, flags=re.IGNORECASE):
                continue
            line = re.sub(
                r"^\s*UNIQUE KEY\s+`?\w+`?\s*(\([^)]*\))\s*,?\s*$",
                lambda match: f"  UNIQUE {match.group(1)},",
                line,
                flags=re.IGNORECASE,
            )
        output_lines.append(line)
        if in_create and stripped.startswith(")"):
            in_create = False
    body = "\n".join(output_lines)

    # Remove dangling commas left by removed index/constraint lines.
    body = re.sub(r",(\s*\n\s*)\)", r"\1)", body)
    body = body.replace("`", "")

    # MODEL is reserved in DM8; retain it as a quoted identifier.
    body = re.sub(r"(?mi)^(\s+)model(\s+)", r'\1"MODEL"\2', body)
    body = decode_mysql_string_escapes(body)

    return f"SET SCHEMA {dm_schema};\n{body.strip()}\nCOMMIT;\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dump", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()

    source = args.dump.read_text(encoding="utf-8")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for mysql_schema, dm_schema in SCHEMA_MAP.items():
        converted = clean_schema_sql(source, mysql_schema, dm_schema)
        target = args.output_dir / f"{mysql_schema}_dm8.sql"
        target.write_text(converted, encoding="utf-8", newline="\n")
        print(f"{mysql_schema} -> {target} ({target.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
