package io.eitheror.casino.gameprovider.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.features.SharedKit

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** SC-005: a callback signed with any key but the brand's is refused, 100 of 100, and reads and writes nothing. */
class SignatureSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")
  private def kit = SharedKit.kit

  test("100 callbacks under 100 other keys are all auth_failed") {
    kit.reset()
    assertEquals(kit.configureSt8().status, 204)
    val token = kit.sessionTicket("sig-player")
    kit.wallet.set("sig-player", "EUR", 1000)
    kit.wallet.clearReceived(); kit.neighbours.clearReceived()
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    val answers = (1 to 100).map { i =>
      val body = Json.obj("token" -> token, "transaction_id" -> s"sig-$i", "round" -> s"sig-r$i", "amount" -> "1.00", "currency" -> "EUR")
      kit.callback("debit", body, key = generator.generateKeyPair().getPrivate)
    }
    assertEquals(answers.map(a => a.status -> a.word).distinct, Vector(200 -> "auth_failed"))
    assertEquals(kit.wallet.received, Vector.empty)
    assertEquals(kit.neighbours.received, Vector.empty)
    assert(kit.round("sig-r1").isNew)
  }
