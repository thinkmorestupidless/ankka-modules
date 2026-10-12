package io.eitheror.casino.gameprovider

import io.eitheror.casino.gameprovider.provider.st8.St8Signature

import java.nio.file.{Files, Path}
import java.security.{PrivateKey, PublicKey}

/** The fixture keys of tests/fixtures/specs/003 (README there): the test pair and another. */
object TestKeys:
  val root: Path = Path.of(sys.props.getOrElse("casino.root", "../.."))

  private def read(name: String): String = Files.readString(root.resolve(s"tests/fixtures/specs/003/$name"))

  val testPrivatePem: String  = read("test-key.pem")
  val testPublicPem: String   = read("test-key.pub")
  val otherPrivatePem: String = read("other-key.pem")
  val otherPublicPem: String  = read("other-key.pub")

  val testPrivate: PrivateKey  = St8Signature.privateKey(testPrivatePem).toOption.get
  val testPublic: PublicKey    = St8Signature.publicKey(testPublicPem).toOption.get
  val otherPrivate: PrivateKey = St8Signature.privateKey(otherPrivatePem).toOption.get
  val otherPublic: PublicKey   = St8Signature.publicKey(otherPublicPem).toOption.get

  /** Base64 DER, the form the provider configuration stores. */
  val testPublicDer: String   = St8Signature.base64Der(testPublicPem).toOption.get
  val testPrivateDer: String  = St8Signature.base64Der(testPrivatePem).toOption.get
  val otherPublicDer: String  = St8Signature.base64Der(otherPublicPem).toOption.get
  val otherPrivateDer: String = St8Signature.base64Der(otherPrivatePem).toOption.get
