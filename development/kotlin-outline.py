#!/usr/bin/env python3
#
# Copyright 2026 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import argparse
from dataclasses import dataclass
import json
import re
import shutil
import subprocess
import sys


AST_GREP_RULES = """id: kotlin-packages
language: Kotlin
rule:
  kind: package_header
message: package
severity: hint
---
id: kotlin-types
language: Kotlin
rule:
  any:
    - kind: class_declaration
    - kind: object_declaration
    - kind: type_alias
message: type
severity: hint
---
id: kotlin-functions
language: Kotlin
rule:
  kind: function_declaration
message: function
severity: hint
---
id: kotlin-properties
language: Kotlin
rule:
  kind: property_declaration
message: property
severity: hint
"""

KEY_MODIFIERS = (
    "actual",
    "abstract",
    "const",
    "data",
    "expect",
    "external",
    "final",
    "infix",
    "inline",
    "lateinit",
    "open",
    "operator",
    "override",
    "sealed",
    "suspend",
    "tailrec",
    "value",
)
VISIBILITIES = ("public", "internal", "private", "protected")


@dataclass(frozen=True)
class Declaration:
    file: str
    line: int
    indent: int
    start_byte: int
    end_byte: int
    visibility: str
    annotations: tuple[str, ...]
    modifiers: tuple[str, ...]
    kind: str
    name: str
    suffix: str

    def display(self) -> str:
        parts = [self.visibility]
        parts.extend(f"@{annotation}" for annotation in self.annotations)
        parts.extend(self.modifiers)
        parts.append(self.kind)
        parts.append(self.name + self.suffix)
        return f"  {' '.join(parts)} : line {self.line}"


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Print a compact Kotlin declaration outline using ast-grep."
    )
    parser.add_argument("paths", nargs="+", help="Kotlin file or directory to inspect.")
    parser.add_argument(
        "--include-private",
        action="store_true",
        help="Include declarations explicitly marked private.",
    )
    parser.add_argument(
        "--annotations",
        help="Comma-separated annotation names to require, e.g. Composable,Stable.",
    )
    return parser.parse_args(argv)


def run_ast_grep(paths):
    if shutil.which("ast-grep") is None:
        raise RuntimeError(
            "ast-grep was not found on PATH. Install ast-grep or add it to PATH."
        )
    command = [
        "ast-grep",
        "scan",
        "--inline-rules",
        AST_GREP_RULES,
        "--json=stream",
    ]
    command.extend(paths)
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return parse_json_lines(result.stdout.splitlines())


def parse_json_lines(lines):
    records = []
    for line in lines:
        if line.strip():
            records.append(json.loads(line))
    return records


def build_outline(records, include_private=False, annotations_filter=None):
    annotations_filter = normalize_annotation_filter(annotations_filter)
    packages = {}
    declarations = []
    for record in records:
        rule_id = record.get("ruleId")
        if rule_id == "kotlin-packages":
            packages[record["file"]] = package_name(record["text"])
            continue
        declaration = declaration_from_record(record)
        if declaration is not None:
            declarations.append(declaration)

    declarations = merge_declarations(
        declarations + fallback_declarations(records, declarations)
    )
    declarations = remove_duplicate_declarations(declarations)
    declarations = remove_local_declarations(declarations)
    if not include_private:
        declarations = remove_private_scope_members(declarations)
    declarations = [
        declaration
        for declaration in declarations
        if should_include(declaration, include_private, annotations_filter)
    ]
    declarations.sort(key=lambda item: (item.file, item.line, item.start_byte, item.kind))

    if annotations_filter is None:
        files = sorted(set(packages) | {declaration.file for declaration in declarations})
    else:
        files = sorted({declaration.file for declaration in declarations})
    return format_outline(files, packages, declarations)


def source_files(records):
    return sorted({record["file"] for record in records if record.get("file", "").endswith(".kt")})


def fallback_declarations(records, existing_declarations):
    existing_keys = {
        declaration_key(declaration): declaration
        for declaration in existing_declarations
        if declaration.end_byte > declaration.start_byte
    }
    declarations = []
    for file in source_files(records):
        try:
            with open(file, encoding="utf-8") as source_file:
                text = source_file.read()
        except OSError:
            continue
        for declaration in fallback_declarations_for_file(file, text):
            key = declaration_key(declaration)
            existing = existing_keys.get(key)
            if existing is None or declaration.end_byte - declaration.start_byte > existing.end_byte - existing.start_byte:
                declarations.append(declaration)
    return declarations


def fallback_declarations_for_file(file, text):
    offsets = line_offsets(text)
    lines = text.splitlines()
    declarations = []
    for index, line in enumerate(lines):
        parsed = parse_declaration_line(line)
        if parsed is None:
            continue
        kind, name = parsed
        start_line = annotation_start_line(lines, index)
        start_byte = offsets[start_line]
        end_byte = declaration_end_byte(text, offsets[index], kind)
        snippet = text[start_byte:end_byte]
        visibility = visibility_from_text(snippet)
        declarations.append(
            Declaration(
                file=file,
                line=index + 1,
                indent=len(line) - len(line.lstrip()),
                start_byte=start_byte,
                end_byte=end_byte,
                visibility=visibility,
                annotations=annotations_from_text(snippet),
                modifiers=modifiers_from_text(snippet, visibility),
                kind=kind,
                name=name,
                suffix=suffix_from_text(snippet, kind, name),
            )
        )
    return declarations


def declaration_key(declaration):
    return (declaration.file, declaration.kind, declaration.name, declaration.line)


def merge_declarations(declarations):
    merged = {}
    for declaration in declarations:
        key = declaration_key(declaration)
        existing = merged.get(key)
        if existing is None or declaration.end_byte - declaration.start_byte > existing.end_byte - existing.start_byte:
            merged[key] = declaration
    return list(merged.values())


def line_offsets(text):
    offsets = []
    offset = 0
    for line in text.splitlines(keepends=True):
        offsets.append(offset)
        offset += len(line)
    return offsets or [0]


def parse_type_line(line):
    parsed = parse_declaration_line(line)
    if parsed is None or parsed[0] not in (
        "annotation class",
        "enum class",
        "class",
        "interface",
        "object",
        "typealias",
    ):
        return None
    return parsed


def parse_declaration_line(line):
    stripped = line.strip()
    if not stripped or stripped.startswith(("*", "//", "/*")):
        return None
    normalized = strip_prefix_tokens(stripped)
    for kind in ("annotation class", "enum class", "class", "interface", "object", "typealias"):
        pattern = rf"^{kind.replace(' ', r'\s+')}\s+([A-Za-z_][A-Za-z0-9_]*)\b"
        match = re.match(pattern, normalized)
        if match:
            return kind, match.group(1)
    function = parse_function_line(normalized)
    if function is not None:
        return function
    property_declaration = parse_property_line(normalized)
    if property_declaration is not None:
        return property_declaration
    return None


def parse_function_line(text):
    match = re.match(r"^fun\s+(?:[A-Za-z_][A-Za-z0-9_<>,?.\s]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(", text)
    if not match:
        return None
    return "fun", match.group(1)


def parse_property_line(text):
    match = re.match(r"^(val|var)\s+(?:[A-Za-z_][A-Za-z0-9_<>,?.\s]*\.)?([A-Za-z_][A-Za-z0-9_]*)\b", text)
    if not match:
        return None
    return match.group(1), match.group(2)


def strip_prefix_tokens(text):
    previous = None
    result = text
    while previous != result:
        previous = result
        result = strip_annotation_prefix(result)
        for token in VISIBILITIES + KEY_MODIFIERS:
            result = re.sub(rf"^{token}\s+", "", result)
    return result


def strip_annotation_prefix(text):
    if not text.startswith("@"):
        return text
    match = re.match(r"@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s*", text)
    return text[match.end() :] if match else text


def annotation_start_line(lines, declaration_line):
    index = declaration_line - 1
    while index >= 0:
        stripped = lines[index].strip()
        if stripped.startswith("@"):
            index -= 1
            continue
        break
    return index + 1


def declaration_end_byte(text, start_byte, kind):
    if kind == "typealias":
        newline = text.find("\n", start_byte)
        return len(text) if newline == -1 else newline
    if kind in ("val", "var"):
        return property_end_byte(text, start_byte)
    open_brace = text.find("{", start_byte)
    next_declaration = next_top_level_declaration_byte(text, start_byte + 1)
    if open_brace == -1 or (next_declaration != -1 and next_declaration < open_brace):
        newline = text.find("\n", start_byte)
        return len(text) if newline == -1 else newline
    end_byte = matching_brace_end(text, open_brace)
    if next_declaration != -1 and next_declaration < end_byte:
        return next_declaration
    return end_byte


def property_end_byte(text, start_byte):
    newline = text.find("\n", start_byte)
    return len(text) if newline == -1 else newline


def next_top_level_declaration_byte(text, start_byte):
    match = re.search(
        r"(?m)^(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|protected|actual|abstract|const|data|expect|external|final|infix|inline|lateinit|open|operator|override|sealed|suspend|tailrec|value)\s+)*(?:annotation\s+class|enum\s+class|class|interface|object|typealias|fun|val|var)\s+[A-Za-z_][A-Za-z0-9_]*\b",
        text[start_byte:],
    )
    return -1 if match is None else start_byte + match.start()


def matching_brace_end(text, open_brace):
    depth = 0
    for index in range(open_brace, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index + 1
    return len(text)


def normalize_annotation_filter(value):
    if not value:
        return None
    annotations = []
    for annotation in value.split(","):
        normalized = annotation.strip().removeprefix("@")
        if normalized:
            annotations.append(normalized)
    return set(annotations)


def package_name(text):
    return text.removeprefix("package").strip()


def declaration_from_record(record):
    text = record["text"]
    kind = declaration_kind(text, record.get("ruleId"))
    if kind is None:
        return None
    name = declaration_name(text, kind)
    if not name:
        return None

    start_byte = record["range"]["byteOffset"]["start"]
    end_byte = record["range"]["byteOffset"]["end"]
    name_line = name_line_number(record, text, name)
    annotations = annotations_from_text(text)
    visibility = visibility_from_text(text)
    modifiers = modifiers_from_text(text, visibility)
    suffix = suffix_from_text(text, kind, name)
    return Declaration(
        file=record["file"],
        line=name_line,
        indent=record.get("charCount", {}).get("leading", 0),
        start_byte=start_byte,
        end_byte=end_byte,
        visibility=visibility,
        annotations=annotations,
        modifiers=modifiers,
        kind=kind,
        name=name,
        suffix=suffix,
    )


def declaration_kind(text, rule_id):
    signature = signature_text(text)
    if rule_id == "kotlin-functions":
        return "fun"
    if rule_id == "kotlin-properties":
        if re.search(r"\bvar\b", signature):
            return "var"
        return "val"
    if rule_id == "kotlin-types":
        if re.search(r"\btypealias\b", signature):
            return "typealias"
        if re.search(r"\bannotation\s+class\b", signature):
            return "annotation class"
        if re.search(r"\benum\s+class\b", signature):
            return "enum class"
        if re.search(r"\binterface\b", signature):
            return "interface"
        if re.search(r"\bobject\b", signature):
            return "object"
        if re.search(r"\bclass\b", signature):
            return "class"
    return None


def declaration_name(text, kind):
    signature = signature_text(text)
    if kind == "fun":
        match = re.search(
            r"\bfun\s+(?:[A-Za-z_][A-Za-z0-9_<>,?.\s]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(",
            signature,
        )
    elif kind in ("annotation class", "enum class"):
        match = re.search(rf"\b{kind.replace(' ', r'\s+')}\s+([A-Za-z_][A-Za-z0-9_]*)", signature)
    elif kind == "typealias":
        match = re.search(r"\btypealias\s+([A-Za-z_][A-Za-z0-9_]*)", signature)
    elif kind in ("val", "var"):
        match = re.search(
            rf"\b{kind}\s+(?:[A-Za-z_][A-Za-z0-9_<>,?.\s]*\.)?([A-Za-z_][A-Za-z0-9_]*)\b",
            signature,
        )
    else:
        match = re.search(rf"\b{kind}\s+([A-Za-z_][A-Za-z0-9_]*)", signature)
    return match.group(1) if match else None


def signature_text(text):
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("@"):
            continue
        lines.append(stripped)
        if "{" in stripped or "=" in stripped:
            break
    return " ".join(lines)


def type_signature_text(text):
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("@"):
            continue
        lines.append(stripped)
        if "{" in stripped:
            break
    return " ".join(lines)


def annotations_from_text(text):
    annotations = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith("@"):
            if stripped:
                break
            continue
        match = re.match(r"@([A-Za-z_][A-Za-z0-9_.]*)", stripped)
        if match:
            annotations.append(match.group(1).split(".")[-1])
    return tuple(annotations)


def visibility_from_text(text):
    signature = signature_text(text)
    for visibility in VISIBILITIES:
        if re.search(rf"\b{visibility}\b", signature):
            return visibility
    return "public"


def modifiers_from_text(text, visibility):
    signature = signature_text(text)
    declaration_start = first_declaration_keyword(signature)
    prefix = signature[:declaration_start] if declaration_start is not None else signature
    modifiers = []
    for modifier in KEY_MODIFIERS:
        if modifier != visibility and re.search(rf"\b{modifier}\b", prefix):
            modifiers.append(modifier)
    return tuple(modifiers)


def first_declaration_keyword(signature):
    indexes = []
    for keyword in ("typealias", "annotation class", "enum class", "class", "interface", "object", "fun", "val", "var"):
        match = re.search(rf"\b{keyword.replace(' ', r'\s+')}\b", signature)
        if match:
            indexes.append(match.start())
    return min(indexes) if indexes else None


def suffix_from_text(text, kind, name):
    if kind == "fun":
        signature = collapse_signature(signature_text(text))
        return function_suffix(signature, name)
    if kind in ("class", "annotation class", "enum class", "interface", "object"):
        signature = collapse_signature(type_signature_text(text))
        return type_suffix(signature, name)
    if kind in ("val", "var"):
        signature = collapse_signature(signature_text(text))
        return property_suffix(signature, name)
    if kind == "typealias":
        signature = collapse_signature(signature_text(text))
        return typealias_suffix(signature, name)
    return ""


def collapse_signature(signature):
    return re.sub(r"\s+", " ", signature).strip()


def function_suffix(signature, name):
    match = re.search(rf"\b{name}\s*\(", signature)
    if not match:
        return ""
    parameters = balanced_parentheses(signature, match.end() - 1)
    return parameters if len(parameters) <= 80 else "(...)"


def constructor_suffix(signature, name):
    match = re.search(rf"(?:\b{name}\s*|\bconstructor\s*)\(", signature)
    if not match:
        return ""
    parameters = balanced_parentheses(signature, match.end() - 1)
    return parameters if len(parameters) <= 80 else "(...)"


def type_suffix(signature, name):
    parameters = constructor_suffix(signature, name)
    supertypes = inheritance_breadcrumbs(signature)
    suffix = parameters
    if supertypes:
        suffix += f" -> {format_supertypes(supertypes)}"
    return suffix


def inheritance_breadcrumbs(signature):
    colon = inheritance_colon_index(signature)
    if colon is None:
        return []
    supertype_text = signature[colon + 1 :]
    supertype_text = supertype_text.split(" where ", 1)[0].split("{", 1)[0].strip()
    return [
        normalize_supertype(supertype)
        for supertype in split_top_level_commas(supertype_text)
        if normalize_supertype(supertype)
    ]


def inheritance_colon_index(signature):
    declaration_start = first_declaration_keyword(signature)
    if declaration_start is None:
        return None
    depth = 0
    angle_depth = 0
    for index in range(declaration_start, len(signature)):
        char = signature[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth = max(0, depth - 1)
        elif char == "<":
            angle_depth += 1
        elif char == ">":
            angle_depth = max(0, angle_depth - 1)
        elif char == ":" and depth == 0 and angle_depth == 0:
            return index
        elif char == "{" and depth == 0 and angle_depth == 0:
            return None
    return None


def split_top_level_commas(text):
    parts = []
    start = 0
    depth = 0
    angle_depth = 0
    for index, char in enumerate(text):
        if char == "(":
            depth += 1
        elif char == ")":
            depth = max(0, depth - 1)
        elif char == "<":
            angle_depth += 1
        elif char == ">":
            angle_depth = max(0, angle_depth - 1)
        elif char == "," and depth == 0 and angle_depth == 0:
            parts.append(text[start:index].strip())
            start = index + 1
    parts.append(text[start:].strip())
    return parts


def normalize_supertype(supertype):
    supertype = supertype.strip()
    if not supertype:
        return ""
    supertype = supertype.split(" by ", 1)[0].strip()
    supertype = re.sub(r"\s*\(.*$", "", supertype).strip()
    return supertype


def format_supertypes(supertypes):
    visible = supertypes[:4]
    suffix = ", ".join(visible)
    if len(supertypes) > len(visible):
        suffix += ", ..."
    return suffix if len(suffix) <= 100 else suffix[:97].rstrip() + "..."


def property_suffix(signature, name):
    match = re.search(rf"\b(?:val|var)\s+{name}\b\s*(:\s*[^=]+)?", signature)
    if not match:
        return ""
    suffix = (match.group(1) or "").strip()
    return f" {suffix}" if suffix and len(suffix) <= 80 else ""


def typealias_suffix(signature, name):
    match = re.search(rf"\btypealias\s+{name}\b\s*=\s*(.+)$", signature)
    if not match:
        return ""
    suffix = match.group(1).strip()
    return f" = {suffix}" if len(suffix) <= 80 else " = ..."


def balanced_parentheses(text, open_index):
    depth = 0
    for index in range(open_index, len(text)):
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[open_index : index + 1]
    return "(...)"


def name_line_number(record, text, name):
    name_range = record.get("metaVariables", {}).get("single", {}).get("NAME", {}).get("range")
    if name_range:
        return name_range["start"]["line"] + 1
    start_line = record["range"]["start"]["line"] + 1
    for offset, line in enumerate(text.splitlines()):
        if re.search(rf"\b{name}\b", line):
            return start_line + offset
    return start_line


def remove_duplicate_declarations(declarations):
    by_position = {}
    for declaration in declarations:
        key = (declaration.file, declaration.start_byte, declaration.end_byte)
        existing = by_position.get(key)
        if existing is None or declaration_priority(declaration) < declaration_priority(existing):
            by_position[key] = declaration
    return list(by_position.values())


def declaration_priority(declaration):
    priorities = {
        "annotation class": 0,
        "enum class": 1,
        "class": 2,
        "interface": 2,
        "object": 2,
        "typealias": 2,
        "fun": 3,
        "val": 4,
        "var": 4,
    }
    return priorities.get(declaration.kind, 10)


def remove_local_declarations(declarations):
    function_ranges = [
        (declaration.file, declaration.start_byte, declaration.end_byte)
        for declaration in declarations
        if declaration.kind == "fun"
    ]
    type_ranges = [
        declaration
        for declaration in declarations
        if declaration.kind
        in ("annotation class", "enum class", "class", "interface", "object")
    ]
    filtered = []
    for declaration in declarations:
        if is_inside_other_function(declaration, function_ranges):
            continue
        if declaration.kind in ("fun", "val", "var") and not is_direct_scope_declaration(
            declaration, type_ranges
        ):
            continue
        filtered.append(declaration)
    return filtered


def is_inside_other_function(declaration, function_ranges):
    for file, start_byte, end_byte in function_ranges:
        if file != declaration.file:
            continue
        if start_byte == declaration.start_byte and end_byte == declaration.end_byte:
            continue
        if start_byte < declaration.start_byte and declaration.end_byte <= end_byte:
            return True
    return False


def is_direct_scope_declaration(declaration, type_ranges):
    parent = innermost_type_parent(declaration, type_ranges)
    if parent is None:
        return declaration.indent == 0
    return declaration.indent == parent.indent + 4


def innermost_type_parent(declaration, type_ranges):
    parents = [
        parent
        for parent in type_ranges
        if parent.file == declaration.file
        and parent.start_byte < declaration.start_byte
        and declaration.end_byte <= parent.end_byte
    ]
    if not parents:
        return None
    return max(parents, key=lambda parent: parent.start_byte)


def remove_private_scope_members(declarations):
    type_ranges = [
        declaration
        for declaration in declarations
        if declaration.kind
        in ("annotation class", "enum class", "class", "interface", "object")
    ]
    filtered = []
    for declaration in declarations:
        parent = innermost_type_parent(declaration, type_ranges)
        if parent is not None and parent.visibility == "private":
            continue
        filtered.append(declaration)
    return filtered


def should_include(declaration, include_private, annotations_filter):
    if declaration.visibility == "private" and not include_private:
        return False
    if annotations_filter is None:
        return True
    return bool(set(declaration.annotations) & annotations_filter)


def format_outline(files, packages, declarations):
    declarations_by_file = {}
    for declaration in declarations:
        declarations_by_file.setdefault(declaration.file, []).append(declaration)

    lines = []
    for file in files:
        file_declarations = declarations_by_file.get(file, [])
        if not file_declarations and file not in packages:
            continue
        lines.append(file)
        package = packages.get(file)
        if package:
            lines.append(f"  package {package}")
        for declaration in file_declarations:
            lines.append(declaration.display())
    return "\n".join(lines)


def main(argv=None):
    args = parse_args(argv if argv is not None else sys.argv[1:])
    try:
        records = run_ast_grep(args.paths)
        outline = build_outline(
            records,
            include_private=args.include_private,
            annotations_filter=args.annotations,
        )
    except RuntimeError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    if outline:
        print(outline)
    return 0


if __name__ == "__main__":
    sys.exit(main())
