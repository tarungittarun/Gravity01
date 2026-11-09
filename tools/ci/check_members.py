#!/usr/bin/env python3
"""Flag `receiver.member` in our sources where `member` is declared nowhere in the project or its
resources.

Why this exists: there is no Android SDK and no kotlinc in this sandbox, so the first time a mistyped or
invented property is caught would otherwise be the user's CI run — and `check_refs.py`, which proves a
*type* exists, cannot see `Suggestion.RowText` when the object is named `TitleCase`. This is the
deliberately coarse companion: a name is legal if ANY declaration of it exists anywhere (a member of a data
class, a function, a parameter, an XML id or resource name). That cannot catch "right name, wrong object",
but it does catch the failure mode that has actually bitten this project repeatedly: a UI file written
against an API from memory.

False positives are answered by the framework allowlist below, not by deleting the check: a checker with a
curated exception list is trustworthy, one that cries wolf gets switched off.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "app" / "src" / "main" / "java"
TEST = ROOT / "app" / "src" / "test" / "java"
RES = ROOT / "app" / "src" / "main" / "res"

DECL = re.compile(
    r"^\s*(?:(?:private|internal|protected|public|open|override|data|lateinit|const|abstract|inner|"
    r"sealed|companion|enum|annotation|suspend|@\w+)\s+)*(?:val|var|fun|class|object|interface|typealias)\s+"
    r"(?:<[^>]*>\s*)?(?:(\w+)\s*\.\s*)?(\w+)",
    re.M,
)
PARAM = re.compile(r"(?:^|[(,\s])(\w+)\s*:\s*[\w<>?,.]+", re.M)
LOCAL = re.compile(r"\b(?:val|var)\s+(\w+)")
NAMED_ARG = re.compile(r"(\w+)\s*=\s*[^=]", re.M)

# Receivers that only ever hold types this project owns; chained calls on anything else (View, Context,
# collections) are framework surface and are out of scope here.
OUR_RECEIVERS = {
    "RailTime", "TrackModel", "Suggestion", "RowText", "PositionEngine", "RefQueries", "Prefs", "RefDb",
    "RailRepository", "LiveViewModel", "TrainFinder", "Geo", "Text", "App", "TrackInsightView",
    "JourneyProgressView", "SuggestBox", "SnapshotCodec", "ErailWire", "Theme", "R", "Render", "Shot",
}
LOCAL_RECEIVERS = {"hit", "s", "m", "ui", "pos", "route", "ans", "row", "st", "v", "b", "pair", "stop"}

# The framework/stdlib surface our UI layer legitimately touches. Curated from the reports, in one place.
ALLOW = set("""
equals hashCode toString copy name ordinal values of entries size isEmpty isNotEmpty isBlank isNotBlank
trim lowercase uppercase substring substringBefore substringAfter split replace startsWith endsWith
contains indexOf lastIndex firstOrNull find filter filterNot map mapIndexed mapNotNull forEach sumBy maxBy
minByOrNull sortedBy distinct distinctBy take drop joinToString orEmpty padStart padEnd repeat toList
toTypedArray toSet toMutableList associate associateBy zip mapIndexedNotNull onEach fold any all none
count groupBy sum average min max require requireNotNull check checkNotNull format append appendLine
text set visibility measure layout draw invalidate requestLayout post postDelayed removeCallbacks
context resources activity findViewById setBackgroundResource setBackgroundColor setTextColor
setText setAdapter setLayoutManager layoutManager layoutManager itemDecoration addItemDecoration
adapter notifyDataSetChanged registerAdapterDataObserver unregisterAdapterDataObserver alpha setColorFilter setPadding width height paddingLeft paddingTop
paddingRight paddingBottom measuredWidth measuredHeight isNestedScrollingEnabled setHasFixedSize
setOnItemClickListener addTextChangedListener afterTextChanged beforeTextChanged text toString textIsSelectable
getLocationInWindow getPixel drawColor mkdirs compress recycle zipWithNext getChildAt lineCount getEllipsisCount assets idle scrollX scrollY measuredScrollX
""".split())



# Android + JDK + Kotlin stdlib members these files actually call, gathered by running this checker and
# keeping what was framework. Listed rather than guessed so a typo cannot hide behind a wildcard.
ALLOW |= set("""
isEmpty
postUrl loadUrl evaluateJavascript isForMainFrame countDown await removeAllCookies flush destroy
stopLoading compareAndSet incrementAndGet optInt setInstanceFollowRedirects optJSONArray length lastPathSegment exceptionOrNull
javaClass getMethod invoke
firstInstallTime versionCode substringBeforeLast
setFixedRouteToAddress getAllByName hostAddress filterIsInstance
isSuccess launch
# v2 live-search slice (2026-10-12): Kotlin Map.getValue, HttpURLConnection.setRequestProperty,
# URL.protocol - the erail transport and the merge tests call these; framework, not typos.
getValue setRequestProperty protocol remove addAll removeSuffix
# v2 identity-overlay slice (2026-10-12): Kotlin stdlib collection lookups. singleOrNull guards the
# identity parse (exactly one record must carry the number); indexOfLast sat unflagged in
# LiveViewModel.segmentRange for weeks because no local named `rows` existed - the identity fetch
# added one, and the checker rightly began scanning the receiver. Both stdlib, not typos.
singleOrNull indexOfLast findAll range
lastModified openOutputStream toByteArray registerForActivityResult clear text
packageName contentResolver connect takeLast log
removeFirst addLast appendText appendBytes readBytes copyOfRange versionName lastUpdateTime getPackageInfo packageManager getLinkProperties hasTransport hasCapability getNetworkCapabilities activeNetwork mkdirs resume install tailLabels exportName rotationPlan getAllByName hostAddress getSharedPreferences putString putInt
length getString getOrNull edit toInt toFloat coerceIn coerceAtLeast coerceAtMost delete lastOrNull
groupValues exists applicationContext lineTo moveTo rewind toEpochDay parentFile toIntOrNull takeIf str
removeAllViews bool responseCode isFile absolutePath withIndex second accuracy latitude longitude
submitList postValue year monthValue dayOfMonth dayOfWeek inv add getBoolean renameTo copyTo readText
writeText listFiles notifyDataSetChanged withLock getHeaderField errorStream inputStream outputStream
bufferedReader use forEachIndexed flatten flatMap sortByDescending reversed indexOfFirst lastIndexOf
minus plus rangeUntil mod rem times div unaryMinus unaryPlus inc dec step until rangeTo
getOrPut getOrElse onFailure onSuccess awaitCancellable current isDisposed dispose clear
# framework calls the render harness makes to paint a screen at another size: Context.createConfigurationContext,
# DisplayMetrics.density, Layout.getLineWidth.
# the live-feed client: javax.crypto, java.security, org.json and the stdlib calls on their results
init doFinal indices optJSONObject optBoolean opt optString has keys getJSONArray encodeToString decode nextBytes lines
setPrimaryClip
# Android Drawable bounds and Bitmap pixel-parity checks in the source-neutral route render tests.
setBounds sameAs
# Android ViewGroup translates measured clock bounds into shared route-item coordinates.
offsetDescendantRectToMyCoords
# Android Paint used for the locomotive age pill width.
measureText
# Android TextView's compound drawable accessor/setter for the no-motion battery row.
compoundDrawables setCompoundDrawables
# Android View's built-in animator/background, listener and TextView colour accessor;
# SharedPreferences stores the draggable refresh position as orientation-safe fractions.
animate background typeface setOnTouchListener currentTextColor getFloat
# ViewTreeObserver is the attached-layout measurement hook for More/Less. Unlike a detached
# UNSPECIFIED measure it yields the real height after visibility changes on large-font phones.
viewTreeObserver addOnPreDrawListener removeOnPreDrawListener isAlive
# Android SpannableString underlines only the days that operate.
setSpan getSpans getSpanStart replaceFirst
# legacy-cipher factory (2026-10-12): SSLSocket suite lists and the delegate pattern
enabledCipherSuites supportedCipherSuites defaultCipherSuites createSocket getOrDefault
# mirror witness (2026-10-12): stdlib Map merge so traveller lines keep their stop and the mirror fills the rest
filterKeys
# Android framework: static route pair selector uses two Spinner selections and Android's built-in
# spinner layouts (not application resources). No project declaration can satisfy these names.
selectedItemPosition simple_spinner_item simple_spinner_dropdown_item getLong
ifBlank maxByOrNull
# Explicit canvas text baseline for the right-hand column header: Paint/FontMetrics.
fontMetrics ascent descent
# pointing the screen at the train (2026-10-12): RecyclerView/LayoutManager positioning
scrollToPositionWithOffset findViewByPosition smoothScrollTo
# bind every route row in the shipped-data render test without assuming off-screen RecyclerView children exist
itemCount createViewHolder getItemViewType bindViewHolder itemView
# stale-while-revalidate mirror cache (2026-10-12): org.json reads + sorted-view walk + time format
optLong optInt SimpleDateFormat format Date Locale US put sortedByDescending second
isNullOrBlank containsKey isNullOrEmpty takeWhile toLongOrNull substringAfter asSequence toList filter
createConfigurationContext density getLineWidth setTextSize requestRectangleOnScreen idleFor application
addView await childCount contentEncoding countDown disconnect filterIndexed getInt getLastKnownLocation getSystemService hasAccuracy hasSpeed ifEmpty isProviderEnabled lastModified matchEntire maxOrNull minOrNull putIfAbsent rawQuery removeUpdates requestFocus requestLocationUpdates right setOnClickListener performClick setOnEditorActionListener setOnFocusChangeListener setSelection sortedWith subList toByteArray toDouble toDoubleOrNull toLong trimEnd windowToken
""".split())

# `R.drawable`, `R.string`, …: nested objects of the generated R class, lowercase by convention, and the
# names inside them are validated by check_resources.py, not here.
R_NESTED = {"drawable", "layout", "id", "string", "color", "dimen", "style", "plurals", "xml", "anim",
            "array", "integer", "bool", "raw", "mipmap", "font"}

def strip_noise(line: str) -> str:
    """Blank out string literals and line comments so prose cannot satisfy or trip the scan."""
    out, i, n = [], 0, len(line)
    while i < n:
        c = line[i]
        if c == '"':
            i += 1
            while i < n and line[i] != '"':
                i += 2 if line[i] == "\\" else 1
            i += 1
            out.append('""')
        elif c == "/" and i + 1 < n and line[i + 1] == "/":
            break
        else:
            out.append(c)
            i += 1
    return "".join(out)


def declared(text: str) -> set[str]:
    out: set[str] = set()
    for rx in (DECL, PARAM, LOCAL, NAMED_ARG):
        for m in rx.finditer(text):
            out.update(g for g in m.groups() if g)
    return out


def resource_names() -> set[str]:
    out: set[str] = set()
    for xml in sorted(RES.rglob("*.xml")):
        text = xml.read_text()
        out |= set(re.findall(r'@(?:\+)?(?:id|drawable|color|dimen|string|style|layout)/(\w+)', text))
        out |= set(re.findall(r'name="(\w+)"', text))
    return out


def usages(path: Path, known: set[str]) -> list[str]:
    problems: list[str] = []
    src = path.read_text()
    locals_ = set(LOCAL.findall(src)) | LOCAL_RECEIVERS
    for line_no, line in enumerate(src.splitlines(), 1):
        code = strip_noise(line)
        for m in re.finditer(r"\b([A-Za-z_]\w*)\.(\w+)", code):
            recv, member = m.group(1), m.group(2)
            if recv not in locals_ and recv not in OUR_RECEIVERS:
                continue
            if member in known or member in ALLOW:
                continue
            if recv == "R" and member in R_NESTED:
                continue
            if member[:1].isupper():  # nested type or companion, not a member
                continue
            problems.append(f"{path.relative_to(ROOT)}:{line_no}: {recv}.{member} — not declared anywhere")
    return problems


def object_inner(files) -> list[str]:
    """`inner class` needs an outer *instance* to capture, and a standalone `object` has none, so Kotlin
    rejects the modifier outright: "Modifier 'inner' is not applicable inside 'standalone object'". CI
    reported exactly that for `BrowserFetch.Call`, which every textual gate passed - the same family of
    mistake as `companion_nested` above, and invisible for the same reason.

    The enclosing declaration is found by indentation rather than by brace counting: this codebase is
    consistently formatted, a brace walk has to guess about multi-line signatures, and a rule that
    occasionally mis-flags is worse than one that occasionally misses.
    """
    problems: list[str] = []
    for f in files:
        lines = _clean(f.read_text()).splitlines()
        for no, line in enumerate(lines, 1):
            if not re.search(r"\binner\s+class\b", line):
                continue
            pad = len(line) - len(line.lstrip())
            for back in range(no - 2, -1, -1):
                outer = lines[back]
                if not outer.strip():
                    continue
                opad = len(outer) - len(outer.lstrip())
                if opad >= pad:
                    continue
                decl = re.search(r"\b(companion object|object|class|interface)\b\s*(\w+)?", outer)
                if decl:
                    kind = decl.group(1)
                    if kind in ("object", "companion object"):
                        problems.append(
                            f"{f.relative_to(ROOT)}:{no}: `inner class` lexically inside "
                            f"{kind}{' ' + decl.group(2) if decl.group(2) else ''} — no outer instance "
                            "exists there; drop `inner` and name the singleton where its members are used")
                    break
    return problems


def backing_field(files) -> list[str]:
    """`field.` inside an accessor body is never legal: there, `field` is the keyword for the property's
    own backing field, so it cannot be dereferenced — Kotlin reports "Property must be initialized" plus a
    cascade of unresolved references. This project hit it by naming a private EditText `field`, which is
    fine everywhere except inside a custom getter/setter, where the keyword shadows it.
    """
    # Comments and strings are blanked first, not just the noise tokens: prose that happens to contain
    # "a form field. `postUrl`" is not code, and it used to be reported as if it were. `strip_noise` works a
    # line at a time and cannot see a KDoc's interior, so block comments are masked here before the per-line
    # pass; newlines are preserved so the number reported is the number in the file.
    problems: list[str] = []
    for f in files:
        src = f.read_text()
        masked, i = [], 0
        while i < len(src):
            start = src.find("/*", i)
            if start < 0:
                masked.append(src[i:])
                break
            end = src.find("*/", start + 2)
            end = len(src) - 1 if end < 0 else end + 2
            masked.append(src[i:start])
            masked.append("".join(ch if ch == "\n" else " " for ch in src[start:end]))
            i = end
        body = "".join(masked)
        if len(body) < len(src):
            body += "\n" * (src.count("\n") - body.count("\n"))
        for no, line in enumerate(_clean(body).splitlines(), 1):
            if re.search(r"(?:^|[=({.\s])field\.", line):
                problems.append(f"{f.relative_to(ROOT)}:{no}: `field.` — `field` is the keyword for a "
                                "property's own backing value inside an accessor, so a member named `field` "
                                "cannot be dereferenced there; rename it")
    return problems



def _clean(src: str) -> str:
    """Same line breaks, strings and comments blanked, so brace counting cannot be fooled by a `{"`."""
    return "\n".join(strip_noise(line) for line in src.splitlines())


def _block_end(src: str, open_at: int) -> int:
    depth, i = 0, open_at
    while i < len(src):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(src) - 1


def companion_nested(files) -> list[str]:
    """A class or object declared inside a `companion object` is *not* reachable as `Outer.Nested` — only
    the companion's functions and properties are, which is the asymmetry that makes this easy to write and
    impossible to spot without a compiler. `Suggestion.TitleCase` cost a CI run for exactly that reason.
    """
    # outer class name -> classifiers nested in its companion
    owners: dict[str, set[str]] = {}
    cleaned: dict[Path, str] = {}
    for f in files:
        src = _clean(f.read_text())
        cleaned[f] = src
        for m in re.finditer(r"\bclass\s+(\w+)[^{}]*?\{", src):
            name = m.group(1)
            end = _block_end(src, src.index("{", m.start()))
            body = src[m.end():end]
            cm = re.search(r"\bcompanion\s+object(?:\s+\w+)?\s*\{", body)
            if not cm:
                continue
            cend = _block_end(body, cm.end() - 1)
            inner = re.findall(r"\b(?:object|interface|class)\s+(\w+)", body[cm.end():cend])
            if inner:
                owners.setdefault(name, set()).update(inner)
    problems: list[str] = []
    for f, src in cleaned.items():
        for no, line in enumerate(src.splitlines(), 1):
            for m in re.finditer(r"\b([A-Z]\w*)\.([A-Z]\w*)\b", line):
                outer, nested = m.group(1), m.group(2)
                if nested in owners.get(outer, ()):
                    problems.append(
                        f"{f.relative_to(ROOT)}:{no}: {outer}.{nested} — `{nested}` is declared inside "
                        f"{outer}'s companion object, which does not expose nested classifiers; refer to "
                        f"it by its own name")
    return problems


def main() -> int:
    files = sorted(MAIN.rglob("*.kt")) + sorted(TEST.rglob("*.kt"))
    known: set[str] = resource_names()
    for f in files:
        known |= declared(f.read_text())
    problems = [p for f in files for p in usages(f, known)] + backing_field(files) + companion_nested(files) + object_inner(files)
    for p in problems:
        print(p)
    print(f"checked {len(files)} files against {len(known)} declared names, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
