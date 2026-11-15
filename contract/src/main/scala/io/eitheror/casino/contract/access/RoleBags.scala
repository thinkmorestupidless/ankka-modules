package io.eitheror.casino.contract.access

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import java.time.{Clock, Duration, Instant}
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The brand's roles as last read from its `players` service (spec 030 contracts/roles.md, *How a
 * service opens a bag*): every service of a brand keeps one, refreshed every [[RoleBags.Interval]], and
 * [[Routes.operatorMay]] decides from it with no call in the request. Until the first read every
 * decision is unavailable; when `players` cannot be reached the last copy is kept and the problem said.
 *
 * `fetch` is the service's own: its client of `players`' `GET …/roles`, answering the body or why it
 * has none, so the contract stays on its own ankka pin. It is called on one thread at a time.
 */
final class RoleBags(fetch: () => Either[String, String], clock: Clock = Clock.systemUTC()):

  private val current = AtomicReference[RoleBags.State](RoleBags.State.Unread)
  private val lastTry = AtomicReference[Option[Instant]](None)
  private val running = AtomicReference[Option[ScheduledExecutorService]](None)

  def state: RoleBags.State = current.get

  /** Reads the roles now, keeping the last copy when the read fails. */
  def refresh(): Unit = synchronized {
    val now = clock.instant()
    val tried = lastTry.getAndSet(Some(now)).isDefined
    val read = Try(fetch()).toEither.left.map(e => s"the roles could not be read: ${e.getMessage}").flatten.flatMap(RoleBags.parse)
    val before = current.get
    val after = current.updateAndGet {
      case _ if read.isRight                        => RoleBags.State.Read(read.toOption.get, now, None)
      case RoleBags.State.Read(roles, at, _)        => RoleBags.State.Read(roles, at, read.left.toOption)
      case RoleBags.State.Unread                    => RoleBags.State.Unread
    }
    // the service's status (contracts/roles.md): a read that fails is said once, and its recovery
    (problemOf(before, tried), read) match
      case (None, Left(why))    => RoleBags.log.warn(s"the brand's roles could not be read; ${RoleBags.describe(after)}: $why")
      case (Some(_), Right(_))  => RoleBags.log.info(s"the brand's roles are read again; ${RoleBags.describe(after)}")
      case _                    => ()
  }

  private def problemOf(s: RoleBags.State, tried: Boolean): Option[String] = s match
    case RoleBags.State.Read(_, _, problem) => problem
    case RoleBags.State.Unread              => Option.when(tried)("unread")

  /** When the roles were last read and why the latest read failed, for a service's status. */
  def status: String = RoleBags.describe(state)

  /** Reads the roles when [[RoleBags.Interval]] has passed since the last try, or none was made. */
  def refreshIfDue(): Unit =
    val due = lastTry.get.forall(t => !clock.instant().isBefore(t.plus(RoleBags.Interval)))
    if due then refresh()

  /** The permissions `roles` carry, from the kept copy; `None` until the first read, except for `platform_admin`. */
  def permissionsOf(roles: Set[String]): Option[Set[String]] =
    if roles.contains(OperatorPermissions.PlatformAdmin) then Some(OperatorPermissions.All.toSet)
    else state match
      case RoleBags.State.Read(bags, _, _) => Some(OperatorPermissions.permissionsOf(roles, bags))
      case RoleBags.State.Unread           => None

  /**
   * Reads now and then every [[RoleBags.Interval]] on a daemon thread of its own, until `stop`. A
   * service calls it once at start, after its own HTTP client exists.
   */
  def start(): RoleBags =
    val scheduler = RoleBags.scheduler()
    running.set(Some(scheduler))
    scheduler.scheduleWithFixedDelay(() => Try(refresh()): Unit, 0, RoleBags.Interval.toMillis, TimeUnit.MILLISECONDS): Unit
    this

  def stop(): Unit = running.getAndSet(None).foreach(_.shutdownNow(): Unit)

object RoleBags:

  /** How often a service reads the roles again: a change is in force within this and one request (FR-008b's 15 s). */
  val Interval: Duration = Duration.ofSeconds(10)

  enum State:
    /** Never read: every decision but `platform_admin`'s is unavailable. */
    case Unread
    /** The roles as read at `at`; `problem` is why the latest read failed, if it did. */
    case Read(roles: Map[String, Set[String]], at: Instant, problem: Option[String])

  private[access] val log = org.slf4j.LoggerFactory.getLogger(classOf[RoleBags])

  def describe(s: State): String = s match
    case State.Unread                     => "never read: operators are answered unavailable"
    case State.Read(roles, at, None)      => s"${roles.size} roles read at $at"
    case State.Read(roles, at, Some(why)) => s"${roles.size} roles kept from $at; the latest read failed: $why"

  /** A copy that is already read, for a service's own tests and for `players`, which reads its own view. */
  def fixed(roles: Map[String, Set[String]]): RoleBags =
    val bags = RoleBags(() => Right(render(roles)))
    bags.refresh()
    bags

  private val json = ObjectMapper()

  /**
   * `players`' answer, `{"roles":[{"name":…,"permissions":[…],…}]}`, as each role's bag. A permission the
   * contract does not know is dropped; an answer of another shape is a failed read, never an empty brand.
   */
  def parse(body: String): Either[String, Map[String, Set[String]]] =
    Try(json.readTree(body)).toOption.filter(n => n != null && n.path("roles").isArray)
      .toRight("the roles answer is not {\"roles\": [...]}")
      .map { tree =>
        tree.path("roles").elements().asScala.toVector.flatMap { (role: JsonNode) =>
          Option(role.path("name").asText(null)).filter(_.nonEmpty).map { name =>
            name -> role.path("permissions").elements().asScala.map(_.asText).filter(OperatorPermissions.known).toSet
          }
        }.toMap
      }

  /** The answer `parse` reads, for roles given here. */
  def render(roles: Map[String, Set[String]]): String =
    val root  = json.createObjectNode()
    val array = root.putArray("roles")
    roles.toVector.sortBy(_._1).foreach { (name, permissions) =>
      val r = array.addObject()
      r.put("name", name)
      val ps = r.putArray("permissions")
      permissions.toVector.sorted.foreach(ps.add)
    }
    json.writeValueAsString(root)

  private def scheduler(): ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(new ThreadFactory:
      def newThread(r: Runnable): Thread =
        val t = Thread(r, "role-bags")
        t.setDaemon(true)
        t
    )
