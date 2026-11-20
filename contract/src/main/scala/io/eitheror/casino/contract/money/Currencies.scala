package io.eitheror.casino.contract.money

/**
 * The contract's one currency table: every ISO 4217 code it publishes with its exponent by
 * `Amounts.exponent`, so a reader in another language reads major units by the same rule the services
 * write them (spec 027 FR-037, spec 030 research R6). `sbt contract/schemas` writes it as
 * `contract/schemas/currencies.v1.json`; the player SDK and the backoffice keep copies, which CI holds
 * to this one.
 */
object Currencies:
  /**
   * The codes published, current and withdrawn, as JDK 25 lists them. A list of the contract's own, so
   * the file is the same whichever JDK writes it; add a code here when ISO adds one.
   */
  val Codes: Vector[String] = """
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
   * Code to exponent, sorted by code. A code the JDK gives no digits (`XAU`, `XXX`) is 2, as the wallet
   * treats it. A code `Currency.from` refuses is an error in [[Codes]], never written with a guessed
   * exponent.
   */
  lazy val table: Vector[(String, Int)] =
    Codes.sorted.map { code =>
      code -> Currency.from(code).map(Amounts.exponent)
        .fold(why => throw IllegalStateException(s"$code is in Currencies.Codes but Currency.from refuses it: $why"), identity)
    }

  /** The table as one JSON object, a code per line. */
  def render(): String =
    table.map((code, exp) => s"""  "$code": $exp""").mkString("{\n", ",\n", "\n}\n")
