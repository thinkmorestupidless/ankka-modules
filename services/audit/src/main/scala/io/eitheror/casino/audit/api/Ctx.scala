package io.eitheror.casino.audit.api

import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients}
import io.eitheror.casino.audit.{Settings, Wiring}
import io.eitheror.casino.audit.ids.Ids
import io.eitheror.casino.audit.ingest.IngestCalls
import io.eitheror.casino.audit.own.Own
import io.eitheror.casino.audit.retention.Retention
import io.eitheror.casino.audit.store.Records

import java.time.Instant

/** What every endpoint is built with: the authenticator and the service's clients; the settings and clock. */
final class Ctx(val authenticate: () => Acl, val clients: EndpointClients):
  def settings: Settings = Wiring.settings.get()
  def now(): Instant     = Wiring.clock.get().instant()

  def calls: IngestCalls = IngestCalls.of(clients.componentClient)
  def records            = clients.viewClient.forView(Records)

  /** Every brand a record can name: the brands read, `payments` for a record naming none, and the installation. */
  def everyBrand: Set[String] = settings.brands.toSet + "payments" + Own.Installation

  /** A raw correlation as the store holds it under each brand asked: a digest per brand. */
  def correlations(raw: String, brands: Set[String]): Vector[String] =
    brands.toVector.sorted.map(Ids.digest(Wiring.requiredKeys.registry, _, raw))

  /** The brand's retention in years. */
  def retentionYears(brand: String): Int = settings.retentionYears

  /** The oldest instant the brand still holds. */
  def retainedFrom(brand: String): Instant = Retention.cutoff(now(), retentionYears(brand))
