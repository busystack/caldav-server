# ADR 0001: CalDAV engine integration gate

Status: accepted application-owned DAV adapter; qualification in progress. Checked 2026-10-27.

## Selected implementation

Use Spring MVC for the embedded HTTP boundary, the JDK's namespace-aware hardened XML parser/serializer for protocol documents, and iCal4j 4.4.0 for calendaring models, validation, parsing and recurrence generation. Spring Security remains the authentication and authorization foundation. This is an application-owned CalDAV implementation, not a claim that Spring or iCal4j supplies a complete protocol engine.

The released Bedework servlet runs on the approved Java/Jakarta/Spring runtime. It was not rejected as incompatible. The integration exercise below reproduced incorrect atomic PROPPATCH behavior, URI form decoding and ETag encoding, as well as unconditional unsupported capability advertising. Correcting those handlers would require maintaining a protocol fork alongside a substantial PostgreSQL/backend implementation. Its 3.x iCal4j fork also shares package names with current upstream 4.4.0; the two cannot simply coexist on the application's classpath. The application-owned adapter avoids that combined fork/model boundary while retaining the established calendaring library. Its cost is explicit ownership of the RFC responsibilities below and a larger independent conformance-testing obligation.

| Application responsibility | Implementation and evidence |
| --- | --- |
| HTTP methods, canonical URIs, conditions and bounded XML | `protocol/DavController`, `DavPaths`, `HttpConditions`, `DavIf`, `DavXml`; `DavHttpIT`, `DavPathsTest`, `DavXmlTest`, `HttpConditionsTest` |
| Discovery, live/dead properties, atomic updates and ACL/principal reports | `DavProperties`, `DavPrincipalReports`, `DavResources`; raw HTTPS tests against PostgreSQL |
| Query filters, recurrence, task ranges, calendar-data projection and free/busy | `calendar/query`, `calendar/recurrence`; semantic/projection tests and independent clients |
| Authoritative resource bytes, UID constraints, conditional mutations and namespace operations | `CalendarStore`, `DavStore`, versioned migrations; PostgreSQL transaction and concurrency tests |
| Snapshot synchronization, deletion history and permission/restore invalidation | Per-collection row-locked revisions plus generation/recovery epochs; `CalendarStoreIT`, `DavStoreIT`, raw REPORT tests |
| Browser/DAV/OAuth boundaries | Spring Security filter chains and persistent application repositories; security HTTP and real browser qualification |

The initial integrated run exposed Spring MVC's disabled OPTIONS dispatch; enabling dispatch corrected it and the later raw HTTPS suite passed. The selected implementation now includes bounded query/recurrence semantics, standard group ACLs and required principal reports. Actual python-caldav3.6.0 and Thunderbird157.0.1 native calendar/task operations have passed through the application with the explicit sync Depth1 compatibility profile. The [conformance matrix](../conformance.md) and final artifact evidence record the exact qualification boundaries; these results do not retrospectively qualify the rejected Bedework backend.

## Published candidate

Bedework's generic server is a real reusable candidate, rather than iCalendar parsing alone. The latest release in [Maven Central metadata](https://repo.maven.apache.org/maven2/org/bedework/bw-caldav-server/maven-metadata.xml) is `org.bedework:bw-caldav-server:6.0.1`, published 2025-07-17. Its [module POM](https://repo.maven.apache.org/maven2/org/bedework/bw-caldav-server/6.0.1/bw-caldav-server-6.0.1.pom) explicitly declares Apache-2.0 and provided `jakarta.servlet-api:6.0.0`. The associated `bw-webdav:6.0.0` module also explicitly declares Apache-2.0. These statements do not establish the licenses of every transitive artifact.

The upstream [source repository](https://github.com/Bedework/bw-caldav) was at `ae0071112e9107c7d034227f610e4282b629821c` (2026-08-08). Its development POM is `6.1.0-SNAPSHOT`, with `bedework-parent:260-SNAPSHOT`. Development-head sources must not be mistaken for the released artifact. The probe uses release 6.0.1 source and binaries from Central.

The released module depends on Bedework's fork `org.bedework.ical4j:ical4j:3.0.24`, not current upstream iCal4j 4.x. The fork's [specific published POM](https://repo.maven.apache.org/maven2/org/bedework/ical4j/ical4j/3.0.24/ical4j-3.0.24.pom) declares BSD-3-Clause. Other direct dependencies include Bedework CalDAV utility/XML modules, WebDAV, access controls, utility/calendar/JMX/XML modules, SOAP and synchronization XML bindings, vCard, and JDKIM. The generic server POM does not require the full Bedework calendar database implementation, Hibernate, a broker, or a separate identity service. The complete resolved dependency inventory must nevertheless be reviewed before adoption.

## Backend boundary and gaps

`org.bedework.caldav.server.sysinterface.SysIntf` is the backend integration contract. It receives a `jakarta.servlet.http.HttpServletRequest` and account name during initialization. Its calendar objects and methods cover collections, events/tasks, access checks, queries, free/busy, resource conversion, and synchronization. Implementing the contract with application PostgreSQL repositories is structurally possible; this is not evidence that it has been implemented or tested.

The servlet constructs `SysIntf` by a configured class name and public no-argument constructor, rather than Spring constructor injection. A narrow lifecycle adapter would be needed. Spring Security's request principal is available through the servlet request, so authentication need not be replaced by Bedework authentication.

Released source has handlers for calendar-query, calendar-multiget, free-busy-query, WebDAV Sync and principal/ACL reports, plus component/property/parameter filtering. `VTODO` appears in component capability and report processing. These observations are source evidence only: they do not qualify task time-range matching, recurrence, lossless storage, conditional requests, or report conformance against a PostgreSQL backend.

Two concrete integration risks are already visible in release sources:

* `CaldavBWIntf#getDavHeader` unconditionally adds `calendar-schedule`, `calendar-auto-schedule`, `calendar-default-alarms`, and `calendarserver-sharing` for authenticated users. `WebdavNsIntf#getDavHeader` adds `extended-mkcol`. The BusyStack profile cannot use those defaults without suppressing unimplemented capabilities.
* `WebdavServlet#service` supports `X-HTTP-Method-Override`, and invalidates an existing HTTP session by default unless `preserve-session` is configured. Those behaviors need explicit security-boundary decisions. The servlet also contains optional request/content dumping; private calendar content must never be enabled in production logging.

`UrlHandler` offers a constructor with a configured prefix/context, but namespace initialization also derives prefixes from the request. Correct public URLs behind a proxy therefore require validation and real tests, not simply passing Host/Forwarded headers through.

## Alternatives

Upstream [iCal4j](https://www.ical4j.org/) is a calendaring parser/model/recurrence library, not a complete protocol engine. Apache [Jackrabbit WebDAV](https://jackrabbit.apache.org/jcr/components/jackrabbit-webdav-library.html) is DAV infrastructure, not a turnkey CalDAV server. Either route leaves the complete CalDAV responsibility with this application.

[Milton pricing](https://milton.io/pricing/) specifically includes CalDAV in its licensed offering. No redistribution decision has been made based on an unrelated Milton community module's license. The exact required CalDAV module and redistribution grant must be established before it can be adopted.

## Investigation gate and historical findings

The separate `investigation/protocol` Maven project records narrow runtime compatibility evidence only. It does not qualify a Bedework PostgreSQL backend. The selected adapter is exercised separately through the real application, PostgreSQL and Spring Security. A successful compilation or OPTIONS request cannot satisfy this gate.

The selected implementation above records the concrete released-handler/model problems and the responsibilities assumed by this project. The remaining investigation notes preserve the evidence behind that choice.

## Concrete PostgreSQL adapter implications

At the investigation checkpoint, the initial `CalendarStore` supplied principal-authorized collection discovery, resource reads, serialized writes/deletes, sharing and sync snapshots. The table below records the concrete work a Bedework adoption would have required at that boundary. The selected application-owned adapter has since implemented those protocol responsibilities directly; this table is historical candidate analysis, not a list of current application stubs.

| Integration responsibility | Work required before adoption |
| --- | --- |
| Principal/collection/event models | Concrete adapters for `AccessPrincipal`, `CalPrincipalInfo`, `CalDAVCollection`, `CalDAVEvent`, `SysiIcalendar`, system/auth properties, and property handlers; stable UUID href mapping |
| Calendar content | Preserve authoritative accepted bytes while supplying parsed models for report projection; `fromIcal`, `toCalendar`, and `writeCalendar` cannot become lossy reconstruction paths |
| Filtering/recurrence/free-busy | `FilterHandler.query` delegates the filter tree and retrieval mode to `SysIntf.getEvents`; the application must implement and test the complete query, task time-range and recurrence semantics |
| Conditional mutations | Retain request conditions in the request-specific adapter and pass them to the serialized storage operation; checking an ETag before starting the mutation transaction is insufficient |
| PROPPATCH | Replace/fix the released handler's atomicity/status behavior; a normal backend `updateCollection` cannot infer the handler's failed property results |
| COPY/MOVE/collection deletion | Add authorized transactional storage operations, destination preconditions, UID collision checks, revision updates, and deletion history; no unqualified rejection of required methods |
| ACLs and reports | Map collection sharing policy into Bedework `Acl`/`CurrentAccess`, enforce reads/writes at storage boundaries, persist supported ACL changes, and implement principal reports without enumeration leaks |
| Synchronization | Adapt each returned resource/deletion from one storage snapshot; avoid fetching a newer live object when serializing an earlier page/token; implement RFC 6578 truncation behavior |
| Runtime/library model | Choose the released Bedework iCal4j 3 fork or prove a compatible replacement; upstream iCal4j 4 has overlapping `net.fortuna.ical4j` packages and cannot simply be added alongside the fork |
| Transaction completion | Ensure errors roll back before committing; ensure committed status/headers cannot be sent before a database commit failure is known |

This is substantial backend and integration work, rather than a servlet registration exercise. Unsupported scheduling, notifications, generic files, and proprietary sharing extensions must be removed from advertised/registered behavior. That exclusion does not permit stubs for required calendar-access methods.

## Executed embedded HTTP evidence

The [probe source](../../investigation/protocol/src/test/java/org/busystack/caldav/investigation/BedeworkHttpTest.java) executed six tests with no failures, errors, or skips. [Recorded command and environment](../../investigation/protocol/evidence/runtime-result.txt) distinguish compatibility checks from expected-defect reproductions. These fixtures do not qualify PostgreSQL or real calendar semantics.

* Spring-authenticated OPTIONS and component PROPFIND work through the real released servlet in Tomcat 11.0.27 / Servlet 6.1 on Boot 4.1.2, Security 7.1.2 and Java 25.
* Empty `VTODO` calendar-query and mandatory `DAV:expand-property` return 207. Calendar-query initially failed 500 because a JAXB implementation was absent; adding Boot-managed `org.glassfish.jaxb:jaxb-runtime:4.0.9` resolved it.
* Mixed PROPPATCH of writable displayname and protected getetag invokes the successful setter and then backend `updateCollection`, despite the protected-property failure. No backend rollback occurs, and the successful property's required 424 dependency status is absent. This is a concrete handler defect to correct before adoption.
* A PUT fixture returning a quoted backend ETag `"probe-etag"` produces the HTTP header `%22probe-etag%22`. `WebdavNsIntf.putContent` applies form URL encoding to the header. A production integration must fix validator serialization as well as ensure byte-preserving semantics.
* A calendar-multiget href with literal `+` reaches backend collection lookup with a space. `CaldavReportMethod` applies form decoding to the href. URI handling must preserve literal plus and validate percent encoding before the engine can satisfy BusyStack's path contract.

These reproductions establish released-handler corrections that a Bedework integration would require. They do not establish a Java/Jakarta/Spring incompatibility or a complete-engine qualification. The application-owned adapter decision above supersedes the earlier investigation-only status.
