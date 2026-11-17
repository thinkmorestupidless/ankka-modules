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
 * FR-037), and the two files the backoffice reads (spec 030 research R5, R6):
 * `access/operator-permissions.v1.json` and `money/currencies.v1.json`.
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
    // The currency table beside the topics (spec 027 FR-037), and its copy in the player SDK.
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
  import io.eitheror.casino.contract.money.{Amounts, Currency}

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
    json.writeValueAsString(root) + "\n"

  /**
   * The ISO 4217 codes published, current and withdrawn, as JDK 25 lists them. A list of the contract's
   * own, so the file is the same whichever JDK writes it; add a code here when ISO adds one.
   */
  val CurrencyCodes: Vector[String] = """
    ADP AED AFA AFN ALL AMD ANG AOA ARS ATS AUD AWG AYM AZM AZN BAM BBD BDT BEF BGL BGN BHD BIF
    BMD BND BOB BOV BRL BSD BTN BWP BYB BYN BYR BZD CAD CDF CHE CHF CHW CLF CLP CNY COP COU CRC
    CSD CUC CUP CVE CYP CZK DEM DJF DKK DOP DZD EEK EGP ERN ESP ETB EUR FIM FJD FKP FRF GBP GEL
    GHC GHS GIP GMD GNF GRD GTQ GWP GYD HKD HNL HRK HTG HUF IDR IEP ILS INR IQD IRR ISK ITL JMD
    JOD JPY KES KGS KHR KMF KPW KRW KWD KYD KZT LAK LBP LKR LRD LSL LTL LUF LVL LYD MAD MDL MGA
    MGF MKD MMK MNT MOP MRO MRU MTL MUR MVR MWK MXN MXV MYR MZM MZN NAD NGN NIO NLG NOK NPR NZD
    OMR PAB PEN PGK PHP PKR PLN PTE PYG QAR ROL RON RSD RUB RUR RWF SAR SBD SCR SDD SDG SEK SGD
    SHP SIT SKK SLE SLL SOS SRD SRG SSP STD STN SVC SYP SZL THB TJS TMM TMT TND TOP TPE TRL TRY
    TTD TWD TZS UAH UGX USD USN USS UYI UYU UZS VEB VED VEF VES VND VUV WST XAD XAF XAG XAU XBA
    XBB XBC XBD XCD XCG XDR XFO XFU XOF XPD XPF XPT XSU XTS XUA XXX YER YUM ZAR ZMK ZMW ZWD ZWG
    ZWL ZWN ZWR
  """.trim.split("\\s+").toVector

  /**
   * Each code with its minor-unit count as `Amounts.exponent` reads it (research R6). A code
   * `Currency.from` refuses is an error in [[CurrencyCodes]], never written with a guessed exponent.
   */
  def currencies: String =
    val root = json.createObjectNode()
    root.put("version", 1)
    val cs = root.putObject("currencies")
    CurrencyCodes.foreach { code =>
      val exponent = Currency.from(code).map(Amounts.exponent)
        .fold(why => throw IllegalStateException(s"$code is in CurrencyCodes but Currency.from refuses it: $why"), identity)
      cs.put(code, exponent)
    }
    json.writeValueAsString(root) + "\n"

  val files: Vector[(String, String)] = Vector(
    "access/operator-permissions.v1.json" -> operatorPermissions,
    "money/currencies.v1.json"            -> currencies
  )
