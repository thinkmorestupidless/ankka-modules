// rewards — a brand's offers, the rewards they grant, and their life at the wallet (spec 011).
//
// ankka's libraries come from Maven Central (or ~/.ivy2/local while the platform is being
// developed). The same version is declared in service.json as `runtime`: change both together
// when upgrading — see README.md, "Upgrading ankka".
val ankkaVersion = "0.16.2"

// The casino's contract (facts, money, route classes, personal markers, the recording replayer),
// published from the repository root with `sbt contract/publishLocal`.
val contractVersion = sys.env.getOrElse("CASINO_VERSION", "0.2.0-SNAPSHOT")

/** The suites that need Docker: the integration kit, the parity replay and the bound features. */
def needsDocker(name: String): Boolean = Seq(".it.", ".port.", ".features.").exists(name.contains)

// The service name is also the image name, the descriptor's name, and — once exposed — the first
// label of the hostname, so it has to be one Kubernetes and DNS accept. Checked here so that a
// name the template's normalisation could not fix fails at the first `sbt` invocation, with the
// rule, rather than at `ankka services apply`.
val serviceName = "rewards"
// (Top-level statements in an sbt build must be settings or vals, hence the val.)
val serviceNameChecked: String = {
  val valid = serviceName.matches("[a-z]([-a-z0-9]{0,61}[a-z0-9])?")
  if (!valid)
    sys.error(
      s"'$serviceName' is not a valid service name: lowercase letters, digits and hyphens, " +
        "starting with a letter, not ending with a hyphen, at most 63 characters"
    )
  serviceName
}

// The contract is built on ankka 0.13.0 and runs here on 0.16.2, as audit does: sbt's early-semver
// check calls that a conflict, so it is lifted for ankka's artifacts alone.
ThisBuild / libraryDependencySchemes ++= Seq("core", "sdk", "http", "runtime", "auth-oidc", "telemetry-otlp", "testkit")
  .map(m => "com.thinkmorestupidless" %% s"ankka-$m" % VersionScheme.Always)

// For the whole build, so a subproject added beside the service — the generated code of a gRPC
// service definition, say — is built with the same Scala version.
ThisBuild / scalaVersion := "3.9.0"

lazy val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name         := serviceNameChecked,
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
    libraryDependencies ++= Seq(
      "com.thinkmorestupidless" %% "ankka-sdk"     % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-runtime" % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-http"    % ankkaVersion,
      // Exports this service's traces and metrics to the collector the installation names; with
      // none named it starts nothing. Nothing in the code names it.
      "com.thinkmorestupidless" %% "ankka-telemetry-otlp" % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-auth-oidc" % ankkaVersion,
      "io.eitheror"             %% "casino-contract" % contractVersion,
      "com.thinkmorestupidless" %% "ankka-testkit" % ankkaVersion % Test,
      "org.scalameta"           %% "munit"         % "1.3.6"      % Test,
      "org.scalameta"           %% "munit-scalacheck" % "1.2.0"   % Test,
      // LeakSuite reads every table directly.
      "org.postgresql"           % "postgresql"       % "42.7.4"  % Test,
    ),
    // Suites read the living features and the recordings from the repository root.
    Test / javaOptions += s"-Dcasino.root=${(baseDirectory.value / ".." / "..").getCanonicalPath}",
    Test / envVars += "CASINO_BRAND" -> "goldvibe",
    run / envVars += "CASINO_BRAND" -> sys.env.getOrElse("CASINO_BRAND", "goldvibe"),
    run / fork := true,
    // The forked run sees only what is passed on: `just rewards-run`'s `-Dankka.local-services.*` name its neighbours.
    run / javaOptions ++= sys.props.toSeq.collect { case (k, v) if k.startsWith("ankka.") => s"-D$k=$v" },
    // The test kit starts a throwaway Postgres in Docker; a forked JVM keeps its lifecycle out of
    // sbt's own process.
    Test / fork := true,
    // `sbt test` runs the unit suites only; `sbt -Dit=true "testOnly …"` (`just rewards-it`) runs the
    // integration, parity and feature suites, which need Docker.
    Test / testOptions ++= (if (sys.props.contains("it")) Nil else Seq(Tests.Filter(n => !needsDocker(n)))),

    // The version the image is tagged with. Locally sbt's own default; in CI the tag being
    // released, or the commit for a manual run — see .github/workflows/deploy.yml.
    version := sys.env.getOrElse("SERVICE_VERSION", "0.1.0-SNAPSHOT"),

    // The image the descriptor names: <name>:<version> and <name>:latest, built into the local
    // Docker daemon by `sbt Docker/publishLocal`. No registry is assumed; `kind load docker-image`
    // puts it where a local cluster can see it.
    Docker / packageName := serviceName,
    // Unset, the image is tagged unqualified, which is what `kind load docker-image` wants. Set,
    // the image is tagged for that registry and `sbt Docker/publish` pushes there — which is how
    // the deploy workflow gets the image somewhere the cluster can pull from.
    Docker / dockerRepository := sys.env.get("DOCKER_REPOSITORY"),
    dockerBaseImage      := "eclipse-temurin:21-jre",
    dockerUpdateLatest   := true,
    dockerExposedPorts   := Seq(9000),
    // A Docker tag may not contain '+'; a git-derived snapshot version might.
    Docker / version     := version.value.replace('+', '-')
  )

// The platform's database schema, taken out of the ankka-runtime artifact into target/ddl, where
// docker-compose.yml mounts it as Postgres' init directory. One copy of the schema — the
// runtime's — and nothing to keep in step: `sbt schema` after every ankka upgrade.
lazy val schema = taskKey[File]("Writes ankka's database schema from the runtime artifact into target/ddl")
schema := {
  val log = streams.value.log
  val jar = (Compile / dependencyClasspath).value
    .map(_.data)
    .find(f => f.getName.startsWith("ankka-runtime_") && f.getName.endsWith(".jar"))
    .getOrElse(sys.error("ankka-runtime is not on the classpath — is ankkaVersion published?"))
  val out = target.value / "ddl"
  IO.delete(out)
  IO.createDirectory(out)
  IO.unzip(jar, out, (entry: String) => entry.startsWith("ankka/ddl/") && entry.endsWith(".sql"))
  val files = (out / "ankka" / "ddl").listFiles().toList.sortBy(_.getName)
  files.foreach(f => IO.move(f, out / f.getName))
  IO.delete(out / "ankka")
  log.info(s"schema: ${files.map(_.getName).mkString(", ")} -> $out")
  out
}
