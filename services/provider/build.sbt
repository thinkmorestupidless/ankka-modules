// provider — the house provider's edge (spec 026): eitheror's own game provider, in the shared
// project `games`, speaking ST8's protocol to every brand on the installation.
//
// ankka's libraries come from Maven Central. The same version is declared in service.json as
// `runtime`: change both together when upgrading.
val ankkaVersion = "0.13.0"

// The casino's contract and the provider core, published from the repository root with
// `sbt contract/publishLocal providerCore/publishLocal` (`just service provider …` does both).
val contractVersion = sys.env.getOrElse("CASINO_VERSION", "0.2.0-SNAPSHOT")

val serviceName = "provider"
val serviceNameChecked: String = {
  val valid = serviceName.matches("[a-z]([-a-z0-9]{0,61}[a-z0-9])?")
  if (!valid)
    sys.error(
      s"'$serviceName' is not a valid service name: lowercase letters, digits and hyphens, " +
        "starting with a letter, not ending with a hyphen, at most 63 characters"
    )
  serviceName
}

ThisBuild / scalaVersion := "3.9.0"

lazy val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name         := serviceNameChecked,
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
    libraryDependencies ++= Seq(
      "com.thinkmorestupidless" %% "ankka-sdk"            % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-runtime"        % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-http"           % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-telemetry-otlp" % ankkaVersion,
      "com.thinkmorestupidless" %% "ankka-auth-oidc"      % ankkaVersion,
      "io.eitheror"             %% "casino-contract"      % contractVersion,
      // The core shared with the simulator; never the simulator itself (FR-005, ProductSuite).
      "io.eitheror"             %% "casino-provider-core" % contractVersion,
      "io.eitheror"             %% "casino-provider-core" % contractVersion % Test classifier "tests",
      "com.thinkmorestupidless" %% "ankka-testkit"        % ankkaVersion % Test,
      "org.scalameta"           %% "munit"                % "1.3.6"      % Test
    ),
    // The test kit starts a throwaway Postgres in Docker; a forked JVM keeps its lifecycle out of
    // sbt's own process.
    Test / fork := true,
    // Suites read features/ and tests/port/ from the repository root, two levels up.
    Test / javaOptions += s"-Dcasino.root=${(baseDirectory.value / ".." / "..").getCanonicalPath}",

    version := sys.env.getOrElse("SERVICE_VERSION", "0.1.0-SNAPSHOT"),
    Docker / packageName := serviceName,
    Docker / dockerRepository := sys.env.get("DOCKER_REPOSITORY"),
    dockerBaseImage      := "eclipse-temurin:21-jre",
    dockerUpdateLatest   := true,
    dockerExposedPorts   := Seq(9000),
    Docker / version     := version.value.replace('+', '-')
  )

// The platform's database schema, taken out of the ankka-runtime artifact into target/ddl, where
// docker-compose.yml mounts it as Postgres' init directory: `sbt schema` after every ankka upgrade.
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
