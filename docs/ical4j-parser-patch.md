# iCal4j parser compatibility patch

This records the parser adaptation already present in the audited baseline. Its copied source and attribution remain unchanged by the independent-audit corrections. Current structural case, UTF-8 folding and additional validation use application-owned integration described in [calendar semantics](calendar-semantics.md).

The production calendaring dependency remains `org.mnode.ical4j:ical4j:4.4.0` (BSD-3-Clause). The application keeps an isolated copy of its established content-line parser in its own namespace; the original copyright and complete license header remain in that file. No independent calendar grammar was introduced.

Upstream source: https://repo.maven.apache.org/maven2/org/mnode/ical4j/ical4j/4.4.0/ical4j-4.4.0-sources.jar

Extracted `net/fortuna/ical4j/data/CalendarParserImpl.java` SHA-256: `9e1c11f080eaced9a7f54983994bef6ab57ab83f47882cd73a29f69c7e833f63`.

The adjacent patch records namespace relocation and the narrow parameter-list loop correction. The callback supplies the already-tokenized individual values to the application content handler; ordinary callbacks remain compatible. Lists are bounded at 1000 values. The application handler decodes RFC 6868 in sequence, preserving literal carets and quotes, and uses established parameter model factories. This avoids the upstream decoder replacement order and whole-value quote stripping bugs.

Observed regressions before the patch: valid multi-valued MEMBER was rejected; CN=^^n became a caret plus newline; CN=^'Babe^' lost its literal quotation marks. Ordinary GET remains byte-preserving; parsed models serve derived queries and report copies only.
