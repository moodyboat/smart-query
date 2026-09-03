"""Isolated protocol runner for conversation-authored rule operators."""

from __future__ import annotations

import argparse
import ast
import json
import math
import statistics
import decimal
import datetime
import re
import traceback
from pathlib import Path


PROTOCOL_VERSION = 1
ALLOWED_MODULES = {"math", "statistics", "decimal", "datetime", "re", "json"}
BLOCKED_CALLS = {
    "eval", "exec", "compile", "open", "input", "__import__", "globals", "locals",
    "vars", "getattr", "setattr", "delattr", "breakpoint", "help", "dir",
}


def _validate_source(source: str, allowed_modules: set[str]) -> None:
    if not source or len(source) > 50_000:
        raise ValueError("sourceCode must contain 1..50000 characters")
    tree = ast.parse(source, mode="exec")
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name.split(".")[0] not in allowed_modules:
                    raise ValueError(f"module is not allowed: {alias.name}")
        elif isinstance(node, ast.ImportFrom):
            if not node.module or node.module.split(".")[0] not in allowed_modules:
                raise ValueError(f"module is not allowed: {node.module}")
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in BLOCKED_CALLS:
            raise ValueError(f"call is not allowed: {node.func.id}")
        elif isinstance(node, ast.Attribute) and node.attr.startswith("__"):
            raise ValueError("dunder attribute access is not allowed")
        elif isinstance(node, ast.Name) and node.id.startswith("__"):
            raise ValueError("dunder names are not allowed")
        elif isinstance(node, (ast.Global, ast.Nonlocal)):
            raise ValueError("global/nonlocal declarations are not allowed")


def _load_entrypoint(source: str, entrypoint: str, allowed_modules: set[str]):
    _validate_source(source, allowed_modules)

    def safe_import(name, globals_=None, locals_=None, fromlist=(), level=0):
        base = name.split(".")[0]
        if base not in allowed_modules:
            raise ImportError(f"module is not allowed: {name}")
        return __import__(name, globals_, locals_, fromlist, level)

    safe_builtins = {
        "None": None, "True": True, "False": False,
        "abs": abs, "all": all, "any": any, "bool": bool, "dict": dict,
        "enumerate": enumerate, "filter": filter, "float": float, "int": int,
        "isinstance": isinstance, "len": len, "list": list, "map": map,
        "max": max, "min": min, "next": next, "range": range, "reversed": reversed,
        "round": round, "set": set, "sorted": sorted, "str": str, "sum": sum,
        "tuple": tuple, "zip": zip, "Exception": Exception, "ValueError": ValueError,
        "TypeError": TypeError, "KeyError": KeyError, "__import__": safe_import,
    }
    namespace = {
        "__builtins__": safe_builtins,
        "math": math, "statistics": statistics, "decimal": decimal,
        "datetime": datetime, "re": re, "json": json,
    }
    exec(compile(source, "<rule-operator>", "exec"), namespace, namespace)
    function = namespace.get(entrypoint)
    if not callable(function):
        raise ValueError(f"entrypoint is not callable: {entrypoint}")
    return function


def _enrich_test_records(records, prefix):
    enriched = []
    for index, item in enumerate(records):
        if not isinstance(item, dict):
            raise ValueError("each input record must be an object")
        record = dict(item)
        record.setdefault("__sourceRefs", [f"{prefix}:{index + 1}"])
        record.setdefault("__sourceSnapshots", [dict(item)])
        enriched.append(record)
    return enriched


def _validate_output(records, max_records):
    if not isinstance(records, list):
        raise ValueError("evaluate must return a records array")
    if len(records) > max_records:
        raise ValueError(f"rule output exceeds {max_records} records")
    for index, record in enumerate(records):
        if not isinstance(record, dict):
            raise ValueError(f"output record #{index + 1} is not an object")
        refs = record.get("__sourceRefs")
        snapshots = record.get("__sourceSnapshots")
        if not isinstance(refs, list) or not refs or not isinstance(snapshots, list) or not snapshots:
            raise ValueError(f"output record #{index + 1} dropped sourceRef lineage")
    json.dumps(records, ensure_ascii=False, allow_nan=False)


def _without_platform_fields(records):
    return [
        {key: value for key, value in record.items() if not key.startswith("__")}
        for record in records
    ]


def _run_tests(function, tests, max_records):
    reports = []
    if not isinstance(tests, list) or len(tests) < 2:
        raise ValueError("at least normal and boundary tests are required")
    for index, test in enumerate(tests):
        if not isinstance(test, dict):
            raise ValueError(f"test #{index + 1} is not an object")
        raw_input = test.get("input", [])
        records = raw_input.get("records", []) if isinstance(raw_input, dict) else raw_input
        records = _enrich_test_records(records, f"test:{index + 1}")
        parameters = test.get("parameters") or {}
        actual = function(records, parameters)
        _validate_output(actual, max_records)
        expected = test.get("expected")
        comparable = _without_platform_fields(actual)
        passed = expected is None or comparable == expected
        report = {"name": test.get("name", f"test-{index + 1}"), "passed": passed}
        if not passed:
            report["expected"] = expected
            report["actual"] = comparable
        reports.append(report)
    failed = [item["name"] for item in reports if not item["passed"]]
    if failed:
        raise ValueError("rule tests failed: " + ", ".join(failed))
    return reports


def _write(path, payload):
    target = Path(path)
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, allow_nan=False), encoding="utf-8")
    temporary.replace(target)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["validate", "execute"])
    parser.add_argument("--request", required=True)
    parser.add_argument("--result", required=True)
    args = parser.parse_args()
    request = json.loads(Path(args.request).read_text(encoding="utf-8"))
    if request.get("protocolVersion") != PROTOCOL_VERSION:
        raise ValueError("unsupported rule runtime protocol")
    max_records = min(max(int(request.get("maxRecords", 1000)), 1), 5000)
    try:
        requested_modules = request.get("allowedModules") or []
        if not isinstance(requested_modules, list) or len(requested_modules) > 100:
            raise ValueError("allowedModules must be an array with at most 100 entries")
        allowed_modules = set(ALLOWED_MODULES)
        for module in requested_modules:
            if not isinstance(module, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", module):
                raise ValueError(f"invalid allowed module: {module}")
            allowed_modules.add(module)
        function = _load_entrypoint(request["sourceCode"], request["entrypoint"], allowed_modules)
        tests = _run_tests(function, request.get("tests"), max_records)
        response = {"protocolVersion": PROTOCOL_VERSION, "status": "success", "tests": tests}
        if args.action == "execute":
            records = request.get("records") or []
            output = function(records, request.get("parameters") or {})
            _validate_output(output, max_records)
            response["records"] = output
        _write(args.result, response)
    except Exception as error:
        _write(args.result, {
            "protocolVersion": PROTOCOL_VERSION,
            "status": "error",
            "error": f"{type(error).__name__}: {error}",
            "trace": traceback.format_exc(limit=5),
        })
        raise


if __name__ == "__main__":
    main()
