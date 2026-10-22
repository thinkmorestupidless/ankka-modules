package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.testkit.GherkinSuite
import io.eitheror.casino.contract.port.{Recording, Replay}

import java.nio.file.Files

/** features/brands/recordings.feature: the checks and the replayer are bound here; the runs are not. */
class RecordingFeatures extends GherkinSuite("features/brands/recordings.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "running the original's end-to-end suite with every provider stubbed leaves one recording per scenario and nothing leaves the machine" ->
      "the recording run against the local original (tests/original/README.md), checked by `brands recordings check`",
    "recording refuses to start while a payment provider the original calls has no stub" ->
      "tests/original/preflight's Go tests",
    "the original's frontend suite passes against a rebuilt brand with only its addresses changed" ->
      "spec 019's rehearsal: it needs every service of a brand rebuilt"
  )

  private val mapper = ObjectMapper()
  private var specs: Vector[(String, String)] = Vector.empty
  private var recording: Option[Recording]    = None

  Given("a spec") { () =>
    specs = Vector("established", "unestablished").map(n => n -> Files.readString(Fixtures.root.resolve(s"tests/fixtures/specs/$n.md")))
  }

  When("it is written") { () => () }

  Then("every requirement it carries names the recording, the original's source or the decision that established it") { () =>
    assertEquals(Established.unnamed(specs.toMap.apply("established")), Vector.empty)
    assertEquals(Established.unnamed(specs.toMap.apply("unestablished")), Vector("FR-002"), "a requirement naming nothing is listed")
  }

  Given("a rebuilt part and the recordings its spec names") { () =>
    recording = Recording.load(Fixtures.root.resolve("tests/fixtures/port/wallet/TestFixture_BetThenBalance.json")).toOption
    assert(recording.isDefined)
  }

  When("its parity tests run") { () => () }

  private def answering(balance: String): Recording.Request => Recording.Response =
    case r if r.method == "POST" => Recording.Response(200, Some(mapper.readTree(s"""{"transaction_id":"n1","balance":"$balance"}""")))
    case r if r.path.endsWith("/n1") => Recording.Response(200, Some(mapper.readTree("""{"transaction_id":"n1","amount":1050,"created_at":"x"}""")))
    case _ => Recording.Response(404, None)

  Then("every recording replays") { () =>
    assertEquals(Replay.run(recording.get, answering("89.50")), Vector.empty)
  }

  Then("a changed answer fails the parity test, naming the recording and the field") { () =>
    val d = Replay.run(recording.get, answering("90.00"))
    assertEquals(d.map(_.at), Vector("$.steps[0].response.body.balance"))
    assert(d.head.message.contains("TestFixture_BetThenBalance.json"))
  }
