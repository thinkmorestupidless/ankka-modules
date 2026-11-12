package io.eitheror.casino.publicapi.it

import com.fasterxml.jackson.databind.JsonNode

/** A key the kit's operator created through the operator routes: its id and its secret. */
final case class MadeKey(id: String, secret: String)

/** Keys of every kind (T024): created, changed, disabled and deleted through `/access-keys`, as an operator does. */
object Keys:
  def create(kit: Kit, name: String = "bi", fields: Seq[String] = Nil, rate: Option[Int] = None, expiresAt: Option[String] = None): MadeKey =
    val parts = Vector(s""""name":"$name"""") ++
      Option.when(fields.nonEmpty)(s""""identifyingFields":[${fields.map(f => s""""$f"""").mkString(",")}]""") ++
      rate.map(r => s""""requestsPerMinute":$r""") ++ expiresAt.map(x => s""""expiresAt":"$x"""")
    val r = kit.request("POST", s"/access-keys/${kit.brand}", Some(parts.mkString("{", ",", "}")), kit.asKeyManager)
    assert(r.status == 201, r.text)
    MadeKey(r.json.path("key").path("id").asText(), r.json.path("secret").asText())

  def noFields(kit: Kit): MadeKey  = create(kit, "none")
  def emailOnly(kit: Kit): MadeKey = create(kit, "email", Seq("email"))
  def allFields(kit: Kit): MadeKey =
    create(kit, "all", io.eitheror.casino.publicapi.keys.IdentifyingFields.Names)

  def disabled(kit: Kit): MadeKey = { val k = noFields(kit); act(kit, k, "disable"); k }
  def deleted(kit: Kit): MadeKey  = { val k = noFields(kit); kit.request("DELETE", s"/access-keys/${kit.brand}/${k.id}", headers = kit.asKeyManager): Unit; k }

  /** A key whose expiry, a minute after now, the kit's clock is then moved past. */
  def expiring(kit: Kit): MadeKey = create(kit, "expiring", expiresAt = Some(kit.now.plusSeconds(60).toString))

  def act(kit: Kit, k: MadeKey, action: String): JsonNode =
    val r = kit.request("POST", s"/access-keys/${kit.brand}/${k.id}/$action", headers = kit.asKeyManager)
    assert(r.status == 200, r.text)
    r.json

  def change(kit: Kit, k: MadeKey, body: String): Reply =
    kit.request("PATCH", s"/access-keys/${kit.brand}/${k.id}", Some(body), kit.asKeyManager)
