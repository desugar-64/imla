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

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("kotlin-outline.py")
SPEC = importlib.util.spec_from_file_location("kotlin_outline", SCRIPT)
kotlin_outline = importlib.util.module_from_spec(SPEC)
sys.modules["kotlin_outline"] = kotlin_outline
SPEC.loader.exec_module(kotlin_outline)


def record(rule_id, text, start, end, line=0, file="Sample.kt", indent=0):
  return {
      "ruleId": rule_id,
      "text": text,
      "file": file,
      "range": {
          "byteOffset": {"start": start, "end": end},
          "start": {"line": line, "column": 0},
          "end": {"line": line, "column": 1},
      },
      "charCount": {"leading": indent},
  }


class KotlinOutlineTests(unittest.TestCase):

  def test_build_outline_hides_private_and_local_declarations(self):
    records = [
        record("kotlin-packages", "package androidx.sample", 0, 24),
        record(
            "kotlin-functions",
            "@Composable\npublic fun Screen(title: String) {\n  val local = title\n}",
            100,
            170,
            line=10,
        ),
        record("kotlin-properties", "val local = title", 145, 162, line=12),
        record("kotlin-properties", "private const val Hidden = 1", 180, 206, line=16),
        record("kotlin-types", "@Stable\ninternal class Model", 220, 248, line=20),
    ]

    outline = kotlin_outline.build_outline(records)

    self.assertIn("Sample.kt", outline)
    self.assertIn("package androidx.sample", outline)
    self.assertIn("public @Composable fun Screen(title: String) : line 12", outline)
    self.assertIn("internal @Stable class Model : line 22", outline)
    self.assertNotIn("Hidden", outline)
    self.assertNotIn("local", outline)

  def test_build_outline_can_include_private_declarations(self):
    records = [
        record("kotlin-packages", "package androidx.sample", 0, 24),
        record("kotlin-properties", "private const val Hidden: Int = 1", 10, 43, line=3),
    ]

    outline = kotlin_outline.build_outline(records, include_private=True)

    self.assertIn("private const val Hidden : Int : line 4", outline)

  def test_build_outline_hides_members_inside_private_types(self):
    records = [
        record("kotlin-packages", "package androidx.sample", 0, 24),
        record("kotlin-types", "private class Hidden {\n  public fun leak() {}\n}", 10, 55, line=3),
        record("kotlin-functions", "public fun leak() {}", 35, 55, line=4, indent=4),
    ]

    outline = kotlin_outline.build_outline(records)

    self.assertNotIn("Hidden", outline)
    self.assertNotIn("leak", outline)

  def test_build_outline_filters_by_annotation(self):
    records = [
        record("kotlin-packages", "package androidx.sample", 0, 24),
        record("kotlin-functions", "@Composable\npublic fun Screen() {}", 30, 62, line=4),
        record("kotlin-types", "@Stable\npublic class Model", 70, 96, line=8),
    ]

    outline = kotlin_outline.build_outline(records, annotations_filter="Stable")

    self.assertIn("public @Stable class Model : line 10", outline)
    self.assertNotIn("Screen", outline)

  def test_build_outline_keeps_members_in_multiline_constructor_class(self):
    source = """package androidx.sample

class ComposeView
@JvmOverloads
constructor(context: Context) : AbstractComposeView(context) {
    fun setContent(content: @Composable () -> Unit) {
    }
}
"""
    with tempfile.TemporaryDirectory() as temporary_directory:
      sample = Path(temporary_directory) / "Sample.kt"
      sample.write_text(source)
      function_start = source.index("fun setContent")
      function_end = source.index("\n    }\n}") + len("\n    }")
      records = [
          record("kotlin-packages", "package androidx.sample", 0, 24, file=str(sample)),
          record(
              "kotlin-functions",
              "fun setContent(content: @Composable () -> Unit) {\n    }",
              function_start,
              function_end,
              line=5,
              file=str(sample),
              indent=4,
          ),
      ]

      outline = kotlin_outline.build_outline(records)

      self.assertIn(
          "public class ComposeView(context: Context) -> AbstractComposeView",
          outline,
      )
      self.assertIn("public fun setContent(content: @Composable () -> Unit)", outline)

  def test_build_outline_prints_direct_inheritance_breadcrumbs(self):
    records = [
        record("kotlin-packages", "package androidx.sample", 0, 24),
        record(
            "kotlin-types",
            "internal class AndroidComposeView(context: Context) : ViewGroup(context), Owner",
            30,
            105,
            line=4,
        ),
        record(
            "kotlin-types",
            "interface PlatformOwner : Owner, ViewRootForTest",
            120,
            166,
            line=8,
        ),
    ]

    outline = kotlin_outline.build_outline(records)

    self.assertIn(
        "internal class AndroidComposeView(context: Context) -> ViewGroup, Owner : line 5",
        outline,
    )
    self.assertIn(
        "public interface PlatformOwner -> Owner, ViewRootForTest : line 9",
        outline,
    )

  @mock.patch("kotlin_outline.shutil.which", return_value="/opt/homebrew/bin/ast-grep")
  @mock.patch("kotlin_outline.subprocess.run")
  def test_run_ast_grep_uses_stream_json(self, mock_run, _):
    mock_run.return_value = subprocess.CompletedProcess(
        args=["ast-grep"],
        returncode=0,
        stdout='{"ruleId":"kotlin-packages","text":"package androidx.sample","file":"Sample.kt","range":{"byteOffset":{"start":0,"end":24},"start":{"line":0,"column":0},"end":{"line":0,"column":24}}}\n',
        stderr="",
    )

    records = kotlin_outline.run_ast_grep(["Sample.kt"])

    self.assertEqual(1, len(records))
    self.assertEqual("kotlin-packages", records[0]["ruleId"])
    command = mock_run.call_args.args[0]
    self.assertIn("scan", command)
    self.assertIn("--json=stream", command)
    self.assertIn("Sample.kt", command)

  @mock.patch("kotlin_outline.shutil.which", return_value=None)
  def test_run_ast_grep_fails_when_ast_grep_is_missing(self, _):
    with self.assertRaisesRegex(RuntimeError, "ast-grep was not found"):
      kotlin_outline.run_ast_grep(["Sample.kt"])


if __name__ == "__main__":
  unittest.main()
