package io.eitheror.casino.pspgateway.it

import io.eitheror.casino.pspgateway.sessions.*

import java.util.{Base64, UUID}
import scala.concurrent.duration.*

/**
 * US5, SC-006, FR-019: a credential rotated in `merchant` is what the next call to the provider carries,
 * with no restart, because it is read on every call and held by nothing. A service of its own (build.sbt),
 * since it rotates the credentials every other suite relies on.
 */
class RotationSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 5.minutes

  private lazy val kit = Kit.withStub()

  override def beforeAll(): Unit = kit: Unit
  override def afterAll(): Unit  = kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    Provider.values.foreach(kit.credential("sb-1", _))

  /** Per provider, a value its request carries and the key it is read from. */
  private val Rotated: Map[Provider, (String, String)] = Map(
    Provider.DreamzPay    -> ("memberId" -> "rotated-member"),
    Provider.UnityFinance -> ("checkoutId.VISA" -> "rotated-checkout"),
    Provider.T365         -> ("apiKey" -> "rotated-t365"),
    Provider.Cozeni       -> ("merchantId" -> "rotated-merchant"),
    Provider.EuPaymentz   -> ("accountId" -> "rotated-account"),
    Provider.Gigadat      -> ("accessToken" -> "rotated-access"),
    Provider.BounceMoney  -> ("keyId" -> "rotated-key-id"),
    Provider.Digitarium   -> ("apiKey" -> "rotated-digitarium"),
    Provider.Morphosis    -> ("user" -> "rotated-user")
  )

  /** Whether a request the stub received carries `value`, as itself or inside a Basic credential. */
  private def carries(request: String, value: String): Boolean =
    request.contains(value) || "Basic ([A-Za-z0-9+/=]+)".r.findAllMatchIn(request).exists { m =>
      scala.util.Try(String(Base64.getDecoder.decode(m.group(1)))).toOption.exists(_.contains(value))
    }

  /** Initiates under a fresh payment id, posting the relay form where the provider has one; the stub's requests it caused. */
  private def initiated(provider: Provider): Vector[String] =
    val before    = kit.psp.received(provider).size
    val paymentId = UUID.randomUUID().toString
    val r         = kit.initiate(paymentId, provider)
    assertEquals(r.status, 200, s"${provider.wire}: ${r.text}")
    kit.relay(paymentId): Unit
    kit.psp.received(provider).drop(before).map(_.toString)

  Provider.values.foreach { provider =>
    test(s"${provider.wire}: the deposit after a rotation carries the new value, read again from merchant") {
      val (key, value) = Rotated(provider)
      val old          = PspStub.credentials(provider)(key)
      val first        = initiated(provider)
      assert(first.exists(carries(_, old)), s"the first call does not carry $key:\n${first.mkString("\n")}")

      val reads = kit.merchant.credentialReads.count(_ == s"sb-1/${provider.wire}")
      kit.merchant.rotate("sb-1", provider.wire, PspStub.credentials(provider).updated(key, value))
      val second = initiated(provider)
      assert(second.exists(carries(_, value)), s"the call after the rotation does not carry the new $key:\n${second.mkString("\n")}")
      assert(!second.exists(carries(_, old)), s"the call after the rotation still carries the old $key")
      assert(kit.merchant.credentialReads.count(_ == s"sb-1/${provider.wire}") > reads, "the credential was not read again")
    }
  }

  test("a webhook verifies under the secret read when it arrives: not after a rotation away from the provider's, again after one back") {
    val provider = Provider.UnityFinance
    kit.psp.control(provider, "pending")
    val paymentId = UUID.randomUUID().toString
    assertEquals(kit.initiate(paymentId, provider).status, 200)
    kit.merchant.rotate("sb-1", provider.wire, PspStub.credentials(provider).updated("signKey", "a-key-the-stub-does-not-sign-with"))
    kit.psp.webhook(provider, paymentId, "outcome" -> "success"): Unit
    Thread.sleep(500)
    assertEquals(kit.deposit(paymentId).stage, Stage.Initiated, "verified under a key the stub did not sign with")

    kit.merchant.rotate("sb-1", provider.wire, PspStub.credentials(provider))
    kit.psp.webhook(provider, paymentId, "outcome" -> "success"): Unit
    kit.eventually(s"$paymentId settled")(kit.deposit(paymentId).outcome): Unit
  }

  test("BounceMoney: a rotated private key signs the next call; a malformed one refuses that initiate only, and the first is not reused") {
    val provider = Provider.BounceMoney
    initiated(provider): Unit
    val pem = PspStub.freshEd25519Pem()
    kit.merchant.rotate("sb-1", provider.wire, PspStub.credentials(provider) ++ Map("keyId" -> "rotated-key-3", "privateKey" -> pem))
    val second = initiated(provider)
    assert(second.exists(carries(_, "rotated-key-3")), second.mkString("\n"))

    kit.merchant.rotate("sb-1", provider.wire, PspStub.credentials(provider).updated("privateKey", "-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----"))
    val before = kit.psp.received(provider).size
    val r      = kit.initiate(UUID.randomUUID().toString, provider)
    assertEquals((r.status, r.str("error")), (502, "provider_unavailable"), r.text)
    assertEquals(kit.psp.received(provider).size, before, "nothing is signed with the key read before")

    kit.merchant.rotate("sb-1", provider.wire, PspStub.credentials(provider))
    initiated(provider): Unit
  }
