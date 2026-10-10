package io.eitheror.casino.contract.facts

import io.eitheror.casino.contract.Layout

/** Every topic of the contract (spec 001 FR-011), in the order the spec lists them. */
object Topics:
  val brand: Vector[Topic[?]] = Vector(
    MoneyTransactions, PaymentsPayments, CasinoPlayers, CasinoSessions, CasinoRounds, CasinoWithdrawals,
    CasinoRewards, AffiliatesAttribution, CasinoPush, PlatformAudit
  )
  val shared: Vector[Topic[?]] = Vector(PaymentsMerchants)
  val all: Vector[Topic[?]]    = brand ++ shared

  def named(name: String): Option[Topic[?]] = all.find(_.name == name)

  // The layout and the facts name the same topics, or the build says so.
  require(brand.map(_.name) == Layout.BrandTopics, s"facts ${brand.map(_.name)} differ from Layout ${Layout.BrandTopics}")
  require(shared.map(_.name) == Layout.SharedTopics.values.flatten.toVector)

/** `sbt contract/schemas`: writes `<dir>/<topic>.v1.json` for every topic. */
object WriteSchemas:
  def main(args: Array[String]): Unit =
    val dir = java.nio.file.Paths.get(args.headOption.getOrElse("contract/schemas"))
    java.nio.file.Files.createDirectories(dir)
    Topics.all.foreach { topic =>
      val file = dir.resolve(s"${topic.contractName}.json")
      java.nio.file.Files.writeString(file, topic.schema.render() + "\n")
      println(s"wrote $file")
    }
