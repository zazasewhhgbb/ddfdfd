# Police Report (Max) — rebuilt from zero

## Latest fixes
- **Translations only showed the first sentence - found two real bugs**: (1) Google Translate's fallback API splits its response into one array entry *per sentence*, and the code was only ever reading the first entry, silently discarding every sentence after it. (2) MyMemory (tried first) has a hard ~500 character limit per request on its free tier, but the code was sending up to 2000 characters, which gets silently truncated rather than erroring. Fixed both: Google is now tried first (all its sentence chunks are stitched back together properly), and MyMemory - now the fallback - respects its real length limit so it can't silently return a partial translation.
- **Translation was frequently incomplete (separate, earlier fix)**: every fetched incident was translated eagerly, all at once, sharing one fixed 8-second budget - but the list view never even displays translated text (it shows an algorithmic English headline like "Fire at Storgata" instead), only the detail view does. With no per-municipality count cap anymore (just a rolling few-day window), that could mean dozens-to-hundreds of incidents competing for that one shared timeout on every refresh, most of which nobody would ever open - and whichever lost the race kept raw Norwegian text with no translation at all. Translation is now lazy: nothing is translated at fetch time, and opening a report translates just that report's handful of messages, each with the full time budget to itself. Far more reliable, and refreshes are faster since no bulk translation happens up front. A small "Translating…" hint shows for the brief moment before it lands.
- **Report timestamps could silently show "now" instead of when a report was actually filed**: if the API's timestamp ever failed to parse for any reason, the code silently substituted the current time - which is actively misleading, not just imprecise. Timestamp parsing now tries a couple of standard alternate formats before giving up, so this should no longer happen in practice.
- **Dismiss (X) button redesigned to prevent accidental deletion**: removed the decorative arrow icon next to it (it wasn't a real dropdown, just visual clutter that made mis-taps more likely), made the X itself clearly red and given a larger touch target, and it now requires a confirming tap in a small dialog before anything is actually dismissed - a single accidental tap can no longer permanently remove a report.

## Earlier fixes
- Max's report cards are back to a short headline (category + street/area, e.g. "Fire · Storgata") with only the English translation as the body text - no more full Norwegian paragraph shown in the card. Tapping still opens the real Politiloggen source page (Norwegian-only, since that's the only language the site has).
- **Reports now show their full update history, not just one message**: a single incident on Politiloggen is often a thread of several updates over time (initial report, then "fire extinguished", "road reopened", etc.) - the real page shows all of them together. This app used to treat every update as its own separate card. Incidents now carry a `threadId` and are grouped by it, so each report is one card containing every update in chronological order, each fully translated, with an "N updates" badge when there's more than one. The municipality header's count now reflects distinct reports (threads), not raw message count.
- **Removed municipalities kept reappearing**: the disk cache (used as a fallback whenever a live fetch fails) returned its entire saved list unfiltered, regardless of what's currently selected in Settings. If a municipality was cached before being removed, and any later refresh fell back to that cache (which happens on any network hiccup, not just total outages), its old incidents kept resurfacing indefinitely. Fixed: the cache is now re-filtered against the *current* municipality/category selection before being served, every time.
- **Municipality sections are collapsible**: tapping a "<Municipality> Police Reports" header collapses/expands that municipality's list (with an incident count and chevron indicator), so multiple municipalities don't turn the screen into one long wall of text. Sections start expanded.

## Latest fixes
- **Source link was broken**: it pointed at `politiet.no/en/politiloggen/...`, a URL that never existed - Politiloggen has **no English version at all** (confirmed via an official App Store review listing "missing: English translation" as a complaint). Fixed to the real, confirmed working pattern: `https://www.politiet.no/politiloggen/hendelse/{threadId}`.
- **Norwegian original was being discarded**: translation used to overwrite the only copy of the source text. `Incident` now keeps both `text` (Norwegian original) and `englishText` (translation) - the app shows the Norwegian original first, labeled, with the English translation underneath it, since the source itself has no English to fall back on.
- **Quiet municipalities' older-but-recent reports were invisible**: the fetcher only ever requested a single page (`Skip=0`) of the national feed. A municipality with no incidents in the very latest ~100 messages nationwide would show nothing, even with incidents from a few days back. Now pages up to 1,500 messages deep (stopping early once enough matches are found), so "a few days old" reports for the municipality you picked actually surface.
- Reordered the two fetch strategies so `/messages` (confirmed working against a real device) is tried first; `/messagethreads` is a fallback only, and had its own unverified/unconfirmed query parameters removed.
- Newest-first sorting was already correct - the "missing reports" issue above was a data-availability problem, not a sort-order one.

This feature was rebuilt completely after several earlier attempts kept
returning "couldn't reach the police report service" even when each attempt
had been individually "confirmed" against either a decompiled version of the
official Politiloggen app or a real device. The most likely explanation:
something specific to a given device/network path (carrier filtering, a DNS
blocker, etc.) was interfering with a single hardcoded request shape - not
that the public API is closed to outside callers (a separate, independent
open-source tool calling the same host works fine).

Rather than keep betting everything on getting one exact undocumented request
right, the new design assumes any single request can fail on any given
device/network, and is built to stay useful anyway.

## Architecture

**`PoliceDistricts.kt`** - the Settings picker (police district → municipality)
is now **fully offline**, a static, hardcoded reference of Norway's 12 police
districts and their municipalities. It has zero network dependency. This was
a deliberate fix: in every earlier version, the picker itself depended on a
live API call, so a single failed request broke the picker too, even though
this geography barely ever changes. Now the picker always works, regardless
of what the actual incident feed is doing.

**`PoliceReportFetcher.kt`** - fetches the incident feed itself:
1. Tries the `/messagethreads` request shape first (Skip/Take/SortByEnum/
   TimeSpanType/category, nested `messages` per thread).
2. If that fails for any reason, tries a second, independent `/messages`
   shape (Take/Skip, flat list) as a fallback strategy.
3. Whichever one returns real, parseable data wins. Both failing outright
   just means "try again later" - it doesn't have to mean "no report."
4. **The last successful fetch is cached to disk** (`police_report_cache.json`
   in the app's private files dir). If a refresh fails, the cached report is
   served instead, so a temporary network hiccup never means the report
   disappears - only a genuinely first-ever failure with no prior successful
   fetch surfaces a hard error.
5. That hard-error message is diagnostic-rich on purpose: which strategies
   were tried and what each one actually returned (HTTP status, byte count),
   so a real failure is readable from the error text alone.
6. Every incident's text is translated to English once (cached after that),
   and its real timestamp is preserved so the UI can show an actual date and
   time, not a vague "a few hours ago."
7. Tapping an incident opens its Politiloggen source page (`politiet.no/en/...`
   the English-language version of the site).

## Settings

Exactly two fields, matching the official app's own picker:
1. **Police district** dropdown (Agder, Finnmark, Innlandet, Møre og Romsdal,
   Nordland, Oslo, Sør-Vest, Sør-Øst, Troms, Trøndelag, Vest, Øst).
2. **Municipality/city** dropdown, scoped to whichever district is selected.

No freeform "type a municipality directly" option - removed on request, since
the two-step picker already covers every real municipality.

## Dashboard

A spinning shield icon (🛡️, matching Max's own mascot animation style) appears
next to the weather-alert icon whenever there's a fresh, unseen police report.
Tapping it opens Max's full report and marks the current incidents as seen.

## Background refresh

A WorkManager job checks for new incidents every hour when police alerts are
enabled, independent of whether the app is open, and posts a notification if
anything new shows up.

## If it still fails

Because every failure mode now surfaces its actual cause in the error text
(rather than one generic message), the most useful thing to do if this ever
errors again is to report that exact message back - it will say precisely
which request strategies were attempted and what happened with each one,
which is enough to diagnose without another guessing round.
