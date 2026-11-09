#!/usr/bin/env python3
"""Regression for the 0/9 device-flow failure: By.res(pkg, id) adds ':id/' itself.

If tests pass 'id/fromBox' as the second argument, they look for
'pkg:id/id/fromBox'. Compilation and flow-manifest checks cannot spot this.
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2] / 'app/src/androidTest/java/in/opentrack/india/e2e'
sources = '\n'.join(f.read_text() for f in root.glob('*.kt'))
assert 'fun res(name: String): BySelector = By.res(PKG, name)' in sources
bad = re.compile(r'\bBy\s*\.\s*res\s*\(\s*[^,\n]+,\s*"id/')
# A selector can be line-wrapped between `By` and `.res`; the release smoke had exactly
# that hidden duplicate-prefix defect while the original one-line guard stayed green.
assert bad.search('By\n  .res(PKG, "id/stopName")')
assert not bad.search('By.res(PKG, "stopName")')
assert not bad.search(sources), 'two-argument By.res already adds :id/'
assert 'By.res(PKG, "fromBox")' not in sources, 'use E2E.res for shared selectors'
print('UiAutomator resource selectors use bare IDs (no doubled :id/)')
