# Player routes no recording calls

One file per described service (spec 031 FR-014), `<service>.txt`: one line per player route of
`services/<service>/openapi.player.json` that no recording under `tests/port/` calls, as
`<METHOD> <path as the description has it>`, sorted. A line beginning `#` is a comment. A service
whose every route is recorded has an empty file.

Each service's `port/DescriptionSuite` runs the description check over every recording and compares
the routes it found unrecorded with its own file. The file is written from the first run and then
reviewed line by line: a route a recording does call, under a path the service's route table of the
original missed, is a missing row of that table, not an unrecorded route.

A route leaves its file when a recording of it is added: the suite fails until the line is removed.
