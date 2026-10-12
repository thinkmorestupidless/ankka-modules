package io.eitheror.casino.simulator.core

import scala.concurrent.duration.FiniteDuration

/** Sends one signed request to a brand and says what came back (research R3: never a platform identity). */
trait Outbound:
  /**
   * @param key which key signs: `current`, `next`, `wrong` (never published) or `withdrawn`
   * @param attempt the attempt number recorded with the result
   */
  def send(brand: String, route: String, body: Array[Byte], key: String, answerWait: FiniteDuration, attempt: Int): Attempt

/** The simulator's signing keys, as far as the core needs them: a rotation (FR-011). */
trait KeyRing:
  def currentKeyId: String
  def nextKeyId: Option[String]
  /** Publishes a next key in the key document beside the current one; answers its id. */
  def publishNext(): String
  /** Makes the next key current, keeping the old one as withdrawn; answers the new current id. */
  def promote(): String
  /** Removes the withdrawn key from the key document. */
  def withdrawOld(): Unit

/** What a brand's configuration says that the core needs: its waits and bounds (FR-025). */
trait Brands:
  def known(slug: String): Boolean
  def testBrand(slug: String): Boolean
  def answerWait(slug: String): FiniteDuration
  def keyRefreshBound(slug: String): FiniteDuration
  def slugs: Vector[String]

/**
 * The service's collaborators, set once by `Main` (or a test kit) before any component runs. A
 * workflow step or an endpoint reaches the outside through these; an entity never does.
 */
object Wiring:
  @volatile private var installed: Option[Installed] = None

  final case class Installed(protocol: Protocol, outbound: Outbound, keys: KeyRing, brands: Brands, clock: () => Long)

  def install(protocol: Protocol, outbound: Outbound, keys: KeyRing, brands: Brands, clock: () => Long = () => System.currentTimeMillis()): Unit =
    installed = Some(Installed(protocol, outbound, keys, brands, clock))

  private def get: Installed = installed.getOrElse(throw IllegalStateException("Wiring.install has not been called"))

  def protocol: Protocol = get.protocol
  def outbound: Outbound = get.outbound
  def keys: KeyRing      = get.keys
  def brands: Brands     = get.brands
  def now(): Long        = get.clock()
