package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.accounts.Secrets
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.imports.{Bundle, Import, ImportState, SealedAccount, StartImport}
import io.eitheror.casino.programme.sealing.{Digest, Identity, Sealing}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import scala.concurrent.duration.*

object ImportAnswers:
  final case class ImportAnswer(importId: String, brandId: String, status: String, counts: Map[String, Int], startedAt: Option[Instant],
      finishedAt: Option[Instant], duplicates: Map[String, String] = Map.empty)

  given JsonValueCodec[ImportAnswer] = ProgrammeCodecs.wire[ImportAnswer]

  def of(id: String, s: ImportState): ImportAnswer =
    ImportAnswer(id, s.brand, if s.finished then "finished" else "running", s.counts, s.startedAt, s.finishedAt, s.duplicates)

/**
 * O-import (FR-039): a brand's bundle checked whole before anything is written, every identity sealed
 * here so the import's own state never holds one in clear, then the import started. An account whose
 * email another account holds, one of the programme's or an earlier one of the bundle, is imported as a
 * duplicate of it and answered under `duplicates`: two accounts are not merged by an import, and no money
 * is left out (research.md, asked 2026-11-07).
 */
final class Imports(api: Api):
  import ImportAnswers.{*, given}

  def start(brand: String, bundle: Bundle, body: String, by: String): Respond[Bytes] =
    if !api.brand(brand).exists then refuse(404, Codes.BrandUnknown, brand)
    else
      val id   = Import.entityId(brand, Imports.digest(body))
      val held = api.components.forWorkflow(id).call(Import.get).invoke()
      // The same bundle sent again is answered its import's state, with none of the lookups and sealing again.
      if held.started then answer(if held.finished then 200 else 202, of(id.toString, held))
      else Bundle.problem(bundle, brand) match
        case Some(p) => invalid(p)
        case None =>
          val enrolment = Enrolment(api)
          val addresses = bundle.accounts.map(a => a -> enrolment.address(Some(a.email)))
          addresses.collectFirst { case (a, None) => a.id } match
            case Some(id) => invalid(s"account $id has no email the programme takes")
            case None =>
              val looked = Imports.concurrently(addresses)((a, e) => a -> enrolment.lookup(e.get))
              looked.collectFirst { case (_, Left(e)) => e } match
                case Some(e) => refuse(503, "store_unavailable", e)
                case None =>
                  val digests = looked.collect { case (a, Right((digest, _))) => a.id -> digest }.toMap
                  val holders = looked.collect { case (a, Right((_, Some(other)))) if other != a.id => a.id -> other }.toMap
                  // The earlier of the bundle's accounts with one email holds it, unless the programme's already does.
                  val first = bundle.accounts.sortBy(a => (a.createdAt, a.id)).groupBy(a => digests(a.id)).view
                    .mapValues(_.minBy(a => (a.createdAt, a.id)).id).toMap
                  def duplicateOf(id: String): Option[String] =
                    holders.get(id).orElse(Some(first(digests(id))).filter(_ != id))
                  seal(bundle, digests) match
                      case Left(e) => refuse(503, "store_unavailable", e)
                      case Right(made) =>
                        val accounts = made.map(s => s.copy(duplicateOf = duplicateOf(s.account.id)))
                        val workflow = api.components.forWorkflow(id)
                        // The identities leave the bundle here: the import keeps the sealed accounts only.
                        val kept = bundle.copy(accounts = Vector.empty)
                        workflow.call(Import.start).invoke(StartImport(brand, kept, accounts, by, api.now())): Unit
                        // A short wait: the lookups and the sealing above already took their share of the request's 20 s.
                        try ok(of(id.toString, workflow.awaitEnd(Import, 2.seconds)))
                        catch
                          case e: CommandError if e.code == ErrorCode.Timeout =>
                            answer(202, of(id.toString, workflow.call(Import.get).invoke()))

  private def seal(bundle: Bundle, digests: Map[String, String]): Either[String, Vector[SealedAccount]] =
    val store = Secrets(api.secrets)
    // An account the programme already holds, an erased one too, is left as it is: no key is minted for it
    // again, and its identity is not sealed anew (an erased account's would be restored).
    val each = Imports.concurrently(bundle.accounts.filterNot(a => api.account(a.id).exists)) { a =>
      store.dataKey(a.id).left.map(_.reason).map { key =>
        val identity = Identity(a.name, a.displayName, Digest.normalised(a.email), a.companyName, a.website, a.trafficSource, a.paymentMethod)
        SealedAccount(a.copy(email = "", name = "", displayName = None, companyName = None, website = None, trafficSource = None,
          paymentMethod = None), Sealing.seal(identity, key, a.id), digests(a.id))
      }
    }
    each.collectFirst { case Left(e) => e }.toLeft(each.collect { case Right(s) => s })

object Imports:
  /** How many accounts are looked up or sealed at once: each is a few calls, and done one by one a bundle outlasts the request. */
  val Parallelism = 16

  /** Within the request's 20 s: each call has its own timeout, and this bounds them all. */
  val Within: FiniteDuration = 18.seconds

  /** `f` over `as` on a pool of its own, `Parallelism` at a time, the answers in `as`'s order. */
  def concurrently[A, B](as: Vector[A])(f: A => B): Vector[B] =
    val pool = java.util.concurrent.Executors.newFixedThreadPool(Parallelism)
    try
      given scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.fromExecutor(pool)
      scala.concurrent.Await.result(scala.concurrent.Future.traverse(as)(a => scala.concurrent.Future(f(a))), Within)
    finally pool.shutdown()

  /** The bundle's own id: the same bytes posted again are the same import. */
  def digest(body: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(UTF_8))).take(16)
