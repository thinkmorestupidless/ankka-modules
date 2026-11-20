package io.eitheror.casino.contract.facts

import io.eitheror.casino.contract.Layout

/** Every topic of the contract (spec 001 FR-011, plus spec 021's `platform.modules`), in the order the specs list them. */
object Topics:
  val brand: Vector[Topic[?]] = Vector(
    MoneyTransactions, PaymentsPayments, CasinoPlayers, CasinoSessions, CasinoRounds, CasinoWithdrawals,
    CasinoRewards, AffiliatesAttribution, CasinoPush, PlatformAudit, PlatformModules,
    MarketsEvents, ExchangeOrders, ExchangeTrades, ExchangePrices
  )
  val shared: Vector[Topic[?]] = Vector(PaymentsMerchants)
  val all: Vector[Topic[?]]    = brand ++ shared

  def named(name: String): Option[Topic[?]] = all.find(_.name == name)

  // The modules' manifests and the facts name the same topics, or the build says so.
  require(brand.map(_.name).toSet == Layout.BrandTopics.toSet, s"facts ${brand.map(_.name)} differ from the modules' topics ${Layout.BrandTopics}")
  // A shared project's topic is the contract's own or a brand topic's shape reused (payments' platform.audit, spec 004 R13).
  require(Layout.SharedTopics.values.flatten.toSet.subsetOf(all.map(_.name).toSet), s"a shared topic of ${Layout.SharedTopics} is no topic of the contract")
  require(shared.map(_.name).toSet.subsetOf(Layout.SharedTopics.values.flatten.toSet))

/**
 * `sbt contract/schemas`: writes `<dir>/<topic>.v1.json` for every topic, the currency table (spec 027
 * FR-037, spec 030 research R6) and `access/operator-permissions.v1.json` (spec 030 research R5).
 */
object WriteSchemas:
  def main(args: Array[String]): Unit =
    val dir = java.nio.file.Paths.get(args.headOption.getOrElse("contract/schemas"))
    java.nio.file.Files.createDirectories(dir)
    Topics.all.foreach { topic =>
      val file = dir.resolve(s"${topic.contractName}.json")
      java.nio.file.Files.writeString(file, topic.schema.render() + "\n")
      println(s"wrote $file")
    }
    // The one currency table beside the topics, and its copy in the player SDK; the backoffice copies it
    // with `just backoffice-contract`.
    val currencies = dir.resolve("currencies.v1.json")
    java.nio.file.Files.writeString(currencies, io.eitheror.casino.contract.money.Currencies.render())
    println(s"wrote $currencies")
    val sdk = java.nio.file.Paths.get("sdk/player/src")
    if java.nio.file.Files.isDirectory(sdk) then
      java.nio.file.Files.copy(currencies, sdk.resolve("currencies.v1.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      println(s"wrote ${sdk.resolve("currencies.v1.json")}")
    Published.files.foreach { (name, text) =>
      val file = dir.resolve(name)
      java.nio.file.Files.createDirectories(file.getParent)
      java.nio.file.Files.writeString(file, text)
      println(s"wrote $file")
    }

/** The contract's published files that are not topics' schemas: what a page outside the JVM reads. */
object Published:
  import com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
  import io.eitheror.casino.contract.access.OperatorPermissions

  private val json = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

  /** The permissions' names and the role matrix as `defaults`: the seed, never what a role carries at run time. */
  def operatorPermissions: String =
    val root = json.createObjectNode()
    root.put("version", 1)
    val ps = root.putArray("permissions")
    OperatorPermissions.All.foreach(ps.add)
    val defaults = root.putObject("defaults")
    OperatorPermissions.DefaultRoles.toVector.sortBy(_._1).foreach { (role, bag) =>
      val a = defaults.putArray(role)
      OperatorPermissions.All.filter(bag).foreach(a.add)
    }
    // spec 032 FR-012: the reserved roles a brand gives as it gives its others
    val givenRoles = root.putArray("given")
    io.eitheror.casino.contract.access.BrandRoles.Given.toVector.sorted.foreach(givenRoles.add)
    json.writeValueAsString(root) + "\n"

  val files: Vector[(String, String)] = Vector(
    "access/operator-permissions.v1.json" -> operatorPermissions
  )
