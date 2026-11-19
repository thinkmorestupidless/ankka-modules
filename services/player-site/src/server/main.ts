// The reference site's process: the player SDK's site server over the built pages, on PORT, its
// brand and the services' addresses from the environment the module gives it (FR-045).
import path from "node:path";
import { startSiteServer } from "@eitheror/player-sdk/server";

const here = path.dirname(new URL(import.meta.url).pathname);

startSiteServer({ static: path.join(here, "../../dist") });
