// V1 probe (spec 030 T001): does an owner judge a web-hosted service's call by the operator token it carries?
// GET /probe/<service>/<path> calls $ANKKA_SERVICES_URL/<service>/<path>, passing on only Authorization,
// and answers what came back: status, whether the proxy answered, and the first 400 characters.
import http from "node:http";

const services = process.env.ANKKA_SERVICES_URL;
const port = Number(process.env.PORT ?? 8080);

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://probe");
  if (url.pathname === "/healthz") return void res.end("ok");
  if (!url.pathname.startsWith("/probe/")) { res.statusCode = 404; return void res.end(); }
  const target = `${services}/${url.pathname.slice("/probe/".length)}${url.search}`;
  const headers = req.headers.authorization ? { authorization: req.headers.authorization } : {};
  try {
    const answer = await fetch(target, { headers });
    const body = await answer.text();
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({
      target: target.slice(services.length),
      tokenSent: Boolean(headers.authorization),
      status: answer.status,
      answeredByProxy: answer.headers.get("x-ankka-answered-by") === "proxy",
      body: body.slice(0, 400),
    }));
  } catch (e) {
    res.statusCode = 502;
    res.end(JSON.stringify({ target, error: String(e) }));
  }
});
server.listen(port, () => console.log(`probe on ${port}, services at ${services}`));
process.on("SIGTERM", () => server.close(() => process.exit(0)));
