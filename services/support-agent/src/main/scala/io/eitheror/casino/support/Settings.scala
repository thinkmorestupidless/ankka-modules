package io.eitheror.casino.support

/** Where the model's answers come from (research R14): Anthropic's API under the module's key, or a script. */
enum ModelSource:
  case Anthropic(key: Settings.Secret)
  /** `scripted:<file>`: a JSON array of turns, for a local run with no key. */
  case Scripted(file: String)

/**
 * What the service is told at start (research R11): the brand, its project, whether it takes real money, the
 * sign-in issuers, compaction's two numbers, the model's source and the judge. Its neighbours are named here too,
 * as their services are named in the project. Everything a brand changes while running is its configuration
 * (`AgentConfiguration`), not this.
 */
final case class Settings(
    brand: String = "goldvibe",
    project: String = "goldvibe",
    realMoney: Boolean = false,
    issuers: Set[String] = Set("player", "staff"),
    compactionBytes: Int = 100_000,
    compactionRecent: Int = 10,
    model: ModelSource = ModelSource.Scripted(""),
    /** The judgment provider's name, `jev`; none means the judged guardrails are off and the status says so. */
    judge: Option[String] = None,
    wallet: String = "wallet",
    payouts: String = "payouts",
    players: String = "players",
    rewards: String = "rewards",
    rg: String = "responsible-gambling"
)

object Settings:
  /** A secret's value, never shown: a settings value printed in a log names that it is set and nothing more. */
  final class Secret(val value: String):
    override def toString: String = "Secret(***)"
    override def equals(other: Any): Boolean = other match
      case s: Secret => s.value == value
      case _         => false
    override def hashCode: Int = value.hashCode

  /** The judgment providers this service can be given. */
  val Judges: Set[String] = Set("jev")

  /** The settings from the environment, refused naming the variable when one is missing or cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val d = Settings()
    for
      brand     <- text(env, "CASINO_BRAND").toRight("CASINO_BRAND: not set; a brand service is deployed by the modules tool")
      realMoney <- flag(env, "CASINO_REAL_MONEY", d.realMoney)
      issuers   <- issuersOf(env)
      bytes     <- positive(env, "AGENT_COMPACTION_BYTES", d.compactionBytes)
      recent    <- positive(env, "AGENT_COMPACTION_RECENT", d.compactionRecent)
      model     <- modelOf(env)
      judge     <- judgeOf(env)
    yield Settings(brand = brand, project = text(env, "CASINO_PROJECT").getOrElse(brand), realMoney = realMoney,
      issuers = issuers, compactionBytes = bytes, compactionRecent = recent, model = model, judge = judge)

  private def text(env: Map[String, String], name: String): Option[String] = env.get(name).map(_.trim).filter(_.nonEmpty)

  private def flag(env: Map[String, String], name: String, default: Boolean): Either[String, Boolean] =
    text(env, name).fold(Right(default)) {
      case v if v.equalsIgnoreCase("true")  => Right(true)
      case v if v.equalsIgnoreCase("false") => Right(false)
      case v                                => Left(s"$name: '$v' is not true or false")
    }

  private def positive(env: Map[String, String], name: String, default: Int): Either[String, Int] =
    text(env, name).fold(Right(default))(v => v.toIntOption.filter(_ > 0).toRight(s"$name: '$v' is not a positive whole number"))

  /** Players sign in at the brand's realm and operators at the staff realm: the service needs both. */
  private def issuersOf(env: Map[String, String]): Either[String, Set[String]] =
    val named = text(env, "ANKKA_AUTH_ISSUERS").toRight("ANKKA_AUTH_ISSUERS: not set; the service needs player,staff")
      .map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet)
    named.flatMap { s =>
      val missing = Vector("player", "staff").filterNot(s)
      if missing.isEmpty then Right(s) else Left(s"ANKKA_AUTH_ISSUERS: names no ${missing.mkString(" or ")} issuer")
    }

  private def modelOf(env: Map[String, String]): Either[String, ModelSource] =
    text(env, "AGENT_MODEL_PROVIDER").getOrElse("anthropic") match
      case "anthropic" =>
        text(env, "MODEL_PROVIDER_KEY").map(k => ModelSource.Anthropic(Secret(k)))
          .toRight("MODEL_PROVIDER_KEY: not set; the support module's model-provider-key secret names it")
      case s if s.startsWith("scripted:") && s.length > "scripted:".length => Right(ModelSource.Scripted(s.stripPrefix("scripted:")))
      case other => Left(s"AGENT_MODEL_PROVIDER: '$other' is not anthropic or scripted:<file>")

  /** Absent, empty or `none` is no judge (the module's default); otherwise one this service knows. */
  private def judgeOf(env: Map[String, String]): Either[String, Option[String]] =
    text(env, "AGENT_JUDGE").filterNot(_.equalsIgnoreCase("none")) match
      case None                    => Right(None)
      case Some(j) if Judges(j)    => Right(Some(j))
      case Some(j)                 => Left(s"AGENT_JUDGE: '$j' is not a judgment provider this service knows (${Judges.mkString(", ")}) or none")
