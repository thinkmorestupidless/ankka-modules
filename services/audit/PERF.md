# audit at scale

`just audit-perf` (`perf/IngestPerf.scala`), 2026-11-09, on a laptop (Apple silicon), ankka 0.16's
integration kit over Postgres in Docker, the topics in ankka's in-memory broker. Sizes: 300 seconds of
200 actions and 1,000 money records a second; 100,000 records over a year; 50,000 over a day.

| Criterion | Asked | Measured | |
|---|---|---|---|
| SC-003 rate | 1,200 messages a second (200 kept) | **209 a second** handled | missed |
| SC-003 lag | under 30 seconds | **1,423 seconds** behind at the end of 300 seconds | missed |
| share kept | | 16.7% (60,000 of 360,000; 300,000 passed over) | as designed |
| SC-004 first page | under 2 seconds at a year | 0.75 seconds (100,000 records over the year) | met |
| SC-004 month export | under 10 minutes | 3.2 seconds (8,219 records) | met |
| SC-006 re-read | a day within 10 minutes | **43,934 of 50,000** in 10 minutes (about 73 a second) | missed |

## Why

- **One message at a time.** The in-memory broker is one partition and hands a group its messages
  in turn, so the figures are a single consumer's: about 5 ms a message passed over, about 14 ms a
  message kept. On Kafka a brand's topic is partitioned by key and the consumer runs per partition,
  so the rate scales with partitions, up to the next limit.
- **Every message writes the source's counter.** `SourceState` (one key value entity per source)
  takes a write for each message, kept or passed over, to keep the status's figures. That is
  1,200 writes a second to one entity at SC-003's rate, and it serialises every partition behind it.
  This is the limit partitioning does not lift.
- **A kept record is six calls**, in order: two pseudonyms issued, two bound, the record kept, the
  counter. The re-read is all kept records, hence its lower rate.

## What would meet them

Not made here, because they change the design (data-model.md's `SourceState`, contracts/ingest.md's
order) and so are a decision for the spec:

1. Count in the consumer and write `SourceState` once per batch or per second, not per message; the
   status's figures are then up to a second old.
2. Issue and bind the two pseudonyms concurrently (`invokeAsync`), and the record's keep after them.
3. Measure again on Kafka with the brand topic's partitions, on the kind installation (quickstart §5).

The in-memory broker also leaves a message whose handler failed at the head of its group until the
next publication; the restore tests ask it to redeliver, as a broker does on its own.
