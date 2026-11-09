# audit

The installation's shared audit, the one service of the `reasoner` project (spec 018):
- every brand's `platform.audit` and `platform.modules`, and `payments`' `platform.audit`, read under
  their grants;
- the records of actions kept, and money events no person caused passed over (they stay in the
  brand's ledger);
- every person's id replaced by a pseudonym from the registry, and the ids that would carry one
  digested;
- queries by brand and across brands, exports as CSV or JSON lines, re-identification under a brand's
  role, and role grants, each a record of its own;
- retention per brand, a daily sweep, and a re-read after a restore.

An ankka 0.16 service in Scala, one sbt build. It holds no `Personal` value: the registry's `Identity`
is the one place a real id is kept.

## Components

Filled in as they are built (plan.md, data-model.md).
