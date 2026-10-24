#!/usr/bin/env python3
"""Summarize Android Lint XML reports for GitHub Actions."""

from __future__ import annotations

import html
import os
import re
import sys
import xml.etree.ElementTree as ET

from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path


WORKSPACE = Path(os.environ.get("GITHUB_WORKSPACE", ".")).resolve()

REPORT_ROOT = Path(
    os.environ.get("ANDROID_LINT_REPORT_DIR", "app/build/reports")
)
if not REPORT_ROOT.is_absolute():
    REPORT_ROOT = WORKSPACE / REPORT_ROOT

MAX_ANNOTATIONS = 1000

SEVERITY_ORDER = {
    "fatal": 0,
    "error": 1,
    "warning": 2,
    "informational": 3,
    "ignore": 4,
}


@dataclass(frozen=True)
class Finding:
    issue_id: str
    severity: str
    summary: str
    message: str
    category: str
    file: str
    line: int | None
    end_line: int | None
    column: int | None
    end_column: int | None
    error_line1: str
    error_line2: str
    report: str

    @property
    def blocking(self) -> bool:
        return self.severity.lower() in {"warning", "error", "fatal"}

    @property
    def key(self) -> tuple:
        return (
            self.issue_id,
            self.severity.lower(),
            self.message,
            self.file,
            self.line,
            self.end_line,
            self.column,
            self.end_column,
        )


def parse_positive_int(value: str | None) -> int | None:
    if value is None:
        return None

    try:
        number = int(value)
    except (TypeError, ValueError):
        return None

    return number if number > 0 else None


def normalize_source_path(raw_path: str | None) -> str:
    """Convert a report path to a workspace-relative path when possible."""
    if not raw_path:
        return ""

    value = raw_path.replace("\\", "/").strip()
    value = value.removeprefix("$PROJECT_DIR$/")
    candidate = Path(value)

    if not candidate.is_absolute():
        candidate = WORKSPACE / candidate

    try:
        resolved = candidate.resolve()
        return resolved.relative_to(WORKSPACE).as_posix()
    except (OSError, ValueError):
        # Keep external/generated paths rather than silently dropping them.
        return value


def parse_issue(issue: ET.Element, report: Path) -> list[Finding]:
    issue_id = issue.get("id", "Unknown")
    severity = issue.get("severity", "Unknown")
    message = issue.get("message", "")
    summary = issue.get("summary", "") or message or issue_id
    category = issue.get("category", "")

    # Do not strip these attributes: whitespace may align the source marker.
    error_line1 = issue.get("errorLine1", "")
    error_line2 = issue.get("errorLine2", "")

    locations = issue.findall("location")

    if not locations:
        locations = [None]

    try:
        report_name = report.relative_to(WORKSPACE).as_posix()
    except ValueError:
        report_name = str(report)

    findings = []

    for location in locations:
        raw_file = location.get("file") if location is not None else None
        raw_line = location.get("line") if location is not None else None
        raw_end_line = (
            location.get("endLine") if location is not None else None
        )
        raw_column = (
            location.get("column") if location is not None else None
        )
        raw_end_column = (
            location.get("endColumn") if location is not None else None
        )

        findings.append(
            Finding(
                issue_id=issue_id,
                severity=severity,
                summary=summary,
                message=message or summary or f"{issue_id} (no message in XML)",
                category=category,
                file=normalize_source_path(raw_file),
                line=parse_positive_int(raw_line),
                end_line=parse_positive_int(raw_end_line),
                column=parse_positive_int(raw_column),
                end_column=parse_positive_int(raw_end_column),
                error_line1=error_line1,
                error_line2=error_line2,
                report=report_name,
            )
        )

    return findings


def parse_report(report: Path) -> list[Finding]:
    try:
        root = ET.parse(report).getroot()
    except (ET.ParseError, OSError) as exc:
        raise RuntimeError(f"{report}: {exc}") from exc

    if root.tag != "issues":
        raise RuntimeError(
            f"{report}: expected XML root <issues>, got <{root.tag}>"
        )

    findings = []

    for issue in root.findall("issue"):
        findings.extend(parse_issue(issue, report))

    return findings


def find_reports() -> list[Path]:
    if not REPORT_ROOT.exists():
        return []

    return sorted(
        path
        for path in REPORT_ROOT.rglob("lint-results*.xml")
        if path.is_file()
    )


def deduplicate(findings: list[Finding]) -> list[Finding]:
    unique: dict[tuple, Finding] = {}

    for finding in findings:
        unique.setdefault(finding.key, finding)

    return sorted(
        unique.values(),
        key=lambda item: (
            item.file.casefold(),
            item.line is None,
            item.line or 0,
            item.column or 0,
            SEVERITY_ORDER.get(item.severity.lower(), 99),
            item.issue_id,
        ),
    )


def read_source_line(
    relative_path: str,
    line_number: int,
) -> str | None:
    """Read one source line without stripping its leading spaces."""
    if not relative_path or line_number < 1:
        return None

    candidate = Path(relative_path)

    if not candidate.is_absolute():
        candidate = WORKSPACE / candidate

    try:
        # splitlines removes line terminators, not indentation.
        source_lines = candidate.read_text(
            encoding="utf-8",
            errors="replace",
        ).splitlines()
    except OSError:
        return None

    if line_number > len(source_lines):
        return None

    return source_lines[line_number - 1]


def source_context(finding: Finding) -> list[str]:
    """Prefer Lint's original source excerpt; otherwise read the source file."""
    if finding.error_line1 or finding.error_line2:
        # Preserve all whitespace supplied by the XML.
        if finding.error_line1:
            result = [finding.error_line1]
            if finding.error_line2:
                result.append(finding.error_line2)
            return result

        return [finding.error_line2]

    if not finding.file or not finding.line:
        return []

    source = read_source_line(finding.file, finding.line)

    if source is None:
        return []

    result = [source]

    # XML line/column coordinates are 1-based.
    # Do not strip the source line: indentation is significant.
    if finding.column is not None:
        start = max(finding.column - 1, 0)

        if (
            finding.end_line == finding.line
            and finding.end_column is not None
        ):
            width = max(finding.end_column - finding.column, 1)
        else:
            width = 1

        marker = (" " * start) + ("~" * width)
        result.append(marker)

    return result


def markdown_escape_inline(value: str) -> str:
    """Escape user/report text inserted into Markdown headings or paragraphs."""
    return (
        value.replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("\r", " ")
        .replace("\n", "<br>")
        .replace("`", "\\`")
    )


def annotation_escape(value: str) -> str:
    """Escape values used in GitHub Actions workflow commands."""
    return (
        value.replace("%", "%25")
        .replace("\r", "%0D")
        .replace("\n", "%0A")
        .replace(":", "%3A")
        .replace(",", "%2C")
    )


def render_summary(
    findings: list[Finding],
    report_count: int,
    parse_errors: list[str],
) -> str:
    counts = Counter(item.severity.lower() for item in findings)
    blocking_count = sum(item.blocking for item in findings)

    lines = [
        "# Android Lint",
        "",
        (
            f"Found **{len(findings)}** issues: "
            f"**{counts['warning']}** warnings, "
            f"**{counts['error']}** errors, "
            f"**{counts['fatal']}** fatal."
        ),
        "",
    ]

    if parse_errors:
        lines.extend([
            "## Report errors",
            "",
        ])

        lines.extend(
            f"- `{markdown_escape_inline(error)}`"
            for error in parse_errors
        )

        lines.append("")

    if not findings:
        if report_count == 0:
            lines.extend([
                "No `lint-results*.xml` report was found. "
                "This is not a successful lint run.",
                "",
            ])
        elif parse_errors:
            lines.extend([
                "No findings could be read because one or more reports "
                "failed to parse.",
                "",
            ])
        else:
            lines.extend([
                "No lint issues found.",
                "",
            ])

        return "\n".join(lines).rstrip() + "\n"

    # Group all findings by source file.
    grouped: dict[str, list[Finding]] = defaultdict(list)

    for finding in findings:
        file_path = finding.file or "(source location unavailable)"
        grouped[file_path].append(finding)

    for file_path in sorted(grouped, key=str.casefold):
        file_findings = sorted(
            grouped[file_path],
            key=lambda item: (
                item.line is None,
                item.line or 0,
                item.column or 0,
                SEVERITY_ORDER.get(item.severity.lower(), 99),
                item.issue_id,
                item.message,
            ),
        )

        # Only the file path is collapsible.
        # All descriptions and source contexts are visible when expanded.
        safe_path = html.escape(file_path, quote=False)

        lines.extend([
            (
                f"<details><summary>{safe_path} "
                f"({len(file_findings)} issues)</summary>"
            ),
            "",
        ])

        for finding in file_findings:
            # Put location directly before severity in the issue heading.
            # No separate line/column heading.
            if finding.line is None:
                location_label = "Location unavailable"
            elif finding.column is not None:
                location_label = (
                    f"{finding.line}:{finding.column}"
                )
            else:
                location_label = f"{finding.line}"

            heading = (
                f"#### {location_label} {finding.severity}: "
                f"{finding.summary or finding.issue_id} "
                f"[{finding.issue_id}]"
            )

            lines.extend([
                markdown_escape_inline(heading),
                "",
                markdown_escape_inline(finding.message),
                "",
            ])

            context = source_context(finding)

            if context:
                longest_backtick_run = max(
                    (
                        len(run)
                        for source_line in context
                        for run in re.findall(r"`+", source_line)
                    ),
                    default=0,
                )

                fence = "`" * max(3, longest_backtick_run + 1)

                lines.append(f"{fence}text")
                lines.extend(context)
                lines.extend([
                    fence,
                    "",
                ])

            elif finding.file and finding.line:
                lines.extend([
                    "Source context unavailable.",
                    "",
                ])

        lines.extend([
            "</details>",
            "",
        ])

    lines.extend([
        "---",
        "",
        (
            f"**Quality gate:** {'FAIL' if blocking_count else 'PASS'} "
            f"({blocking_count} warning/error/fatal findings)."
        ),
        "",
    ])

    return "\n".join(lines).rstrip() + "\n"


def publish_annotations(findings: list[Finding]) -> None:
    """Publish file/line annotations in the Actions log and Checks UI."""
    published = 0

    for finding in findings:
        if published >= MAX_ANNOTATIONS:
            print(
                f"::warning::Annotation limit reached ({MAX_ANNOTATIONS}); "
                "remaining findings are available in the job summary."
            )
            break

        if not finding.file or not finding.line:
            continue

        severity = finding.severity.lower()

        if severity in {"error", "fatal"}:
            command = "error"
        elif severity == "warning":
            command = "warning"
        else:
            command = "notice"

        properties = [
            f"file={annotation_escape(finding.file)}",
            f"line={finding.line}",
        ]

        if finding.end_line:
            properties.append(f"endLine={finding.end_line}")

        if finding.column:
            properties.append(f"col={finding.column}")

        if finding.end_column:
            properties.append(f"endColumn={finding.end_column}")

        title = (
            f"{finding.severity}: "
            f"{finding.summary or finding.issue_id} "
            f"[{finding.issue_id}]"
        )
        properties.append(f"title={annotation_escape(title)}")

        message = annotation_escape(
            finding.message or finding.summary or finding.issue_id
        )

        print(f"::{command} {','.join(properties)}::{message}")
        published += 1


def write_outputs(
    findings: list[Finding],
    report_count: int,
    parse_errors: list[str],
) -> None:
    """Export counts for a later GitHub Actions quality-gate step."""
    output_path = os.environ.get("GITHUB_OUTPUT")

    if not output_path:
        return

    counts = Counter(item.severity.lower() for item in findings)
    blocking_count = sum(item.blocking for item in findings)

    if report_count == 0:
        report_status = "missing"
    elif parse_errors:
        report_status = "invalid"
    else:
        report_status = "ok"

    values = {
        "report_status": report_status,
        "report_count": report_count,
        "warning_count": counts["warning"],
        "error_count": counts["error"],
        "fatal_count": counts["fatal"],
        "blocking_count": blocking_count,
        "parse_error_count": len(parse_errors),
    }

    with open(output_path, "a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def write_summary(content: str) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")

    if not summary_path:
        print(content)
        return

    with open(summary_path, "a", encoding="utf-8") as summary:
        summary.write(content)


def main() -> int:
    reports = find_reports()
    findings: list[Finding] = []
    parse_errors: list[str] = []

    for report in reports:
        try:
            findings.extend(parse_report(report))
        except RuntimeError as exc:
            message = str(exc)
            parse_errors.append(message)
            print(f"::error::{annotation_escape(message)}")

    findings = deduplicate(findings)

    summary = render_summary(
        findings=findings,
        report_count=len(reports),
        parse_errors=parse_errors,
    )

    write_summary(summary)
    publish_annotations(findings)
    write_outputs(
        findings=findings,
        report_count=len(reports),
        parse_errors=parse_errors,
    )

    counts = Counter(item.severity.lower() for item in findings)
    blocking_count = sum(item.blocking for item in findings)

    print(
        "Android Lint summary: "
        f"reports={len(reports)}, "
        f"findings={len(findings)}, "
        f"warnings={counts['warning']}, "
        f"errors={counts['error']}, "
        f"fatal={counts['fatal']}, "
        f"parse_errors={len(parse_errors)}"
    )

    # Missing or malformed reports are failures.
    # Warning/error/fatal counts are enforced by the workflow quality gate.
    if not reports or parse_errors:
        return 2

    return 0


if __name__ == "__main__":
    raise SystemExit(main())