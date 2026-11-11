package io.eitheror.casino.gameprovider.config

import io.eitheror.casino.gameprovider.TestKeys
import io.eitheror.casino.gameprovider.api.Settings
import io.eitheror.casino.gameprovider.provider.st8.St8Signature

import ProviderConfiguration.given

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Spec 026's amendment of spec 003: `casino-providers`, and the operator public key a configuration read answers. */
class ProvidersValueSuite extends munit.FunSuite:

  test("casino-providers names the providers a brand runs, each an ST8 adapter under its name"):
    assertEquals(Settings.providers(Settings.DefaultProviders).map(_.map(_.name)), Right(Vector("st8", "eitheror")))
    assertEquals(Settings.providers(" st8:st8 , eitheror:st8 ,").map(_.map(_.name)), Right(Vector("st8", "eitheror")))

  test("casino-providers refuses an unknown protocol, a malformed entry, a name twice and none, naming the value"):
    assertEquals(Settings.providers("st8:st8,eitheror:pragmatic"), Left("casino-providers: 'pragmatic' is not a protocol; st8 is"))
    assertEquals(Settings.providers("st8"), Left("casino-providers: 'st8' is not name:protocol"))
    assertEquals(Settings.providers("Eitheror:st8"), Left("casino-providers: 'Eitheror' is not a provider name (lower-case letters, digits and hyphens)"))
    assertEquals(Settings.providers("st8:st8,st8:st8"), Left("casino-providers names a provider twice: 'st8:st8,st8:st8'"))
    assertEquals(Settings.providers(" , "), Left("casino-providers names no provider"))

  test("the operator public key derived from the private key is the key pair's own"):
    assertEquals(St8Signature.publicKeyOf(TestKeys.testPrivatePem), St8Signature.base64Der(TestKeys.testPublicPem))
    // And for fresh pairs, whichever square root the public point's y is.
    (1 to 20).foreach { _ =>
      val g = KeyPairGenerator.getInstance("EC")
      g.initialize(ECGenParameterSpec("secp256r1"))
      val pair = g.generateKeyPair()
      val priv = Base64.getEncoder.encodeToString(pair.getPrivate.getEncoded)
      assertEquals(St8Signature.publicKeyOf(priv), Right(Base64.getEncoder.encodeToString(pair.getPublic.getEncoded)))
    }

  test("a configuration's view answers the operator public key and never the private one"):
    val c = ProviderConfiguration(apiUrl = "http://st8.test", site = "goldvibe", operatorPrivateKey = St8Signature.base64Der(TestKeys.testPrivatePem).toOption.get,
      providerPublicKey = St8Signature.base64Der(TestKeys.testPublicPem).toOption, providerKeyDocumentUrl = None, jurisdiction = "MT",
      lobbyUrl = "https://goldvibe.example", depositUrl = "https://goldvibe.example/cashier", walletUrl = None)
    val view = c.view("eitheror")
    assertEquals(view.operatorPublicKey, St8Signature.base64Der(TestKeys.testPublicPem).toOption)
    assert(!com.github.plokhotnyuk.jsoniter_scala.core.writeToString(view).contains(c.operatorPrivateKey))
