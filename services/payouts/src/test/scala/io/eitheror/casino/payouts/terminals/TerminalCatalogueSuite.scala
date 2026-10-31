package io.eitheror.casino.payouts.terminals

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit

import java.time.{Duration, Instant}

/** The catalogue's refresh, one currency at a time, and the timer's rule: a failed read keeps the last list. */
class TerminalCatalogueSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-10-15T12:00:00Z")
  private val uf  = Terminal("t-1", "sb-1", "Bank", "EUBANKING", "unityfinance", true, true, true, hasStatus = true)
  private val coz = Terminal("t-2", "sb-1", "Crypto", "USDT", "cozeni", true, true, false, Vector("USDT"), reviewWindow = Some(Duration.ofHours(24)))
  private def kit = KeyValueEntityTestKit.of(TerminalCatalogue, "goldvibe")

  test("a refresh replaces one currency's list and keeps the others"):
    val k = kit
    k.call(TerminalCatalogue.refresh)(Refresh("EUR", Vector(uf), at)): Unit
    k.call(TerminalCatalogue.refresh)(Refresh("USD", Vector(coz), at)): Unit
    k.call(TerminalCatalogue.refresh)(Refresh("EUR", Vector(uf, coz), at.plusSeconds(60))): Unit
    val s = k.call(TerminalCatalogue.get).replyValue
    assertEquals((s.of("EUR"), s.of("USD"), s.of("CAD")), (Some(Vector(uf, coz)), Some(Vector(coz)), None))
    assertEquals(s.refreshedAt("EUR"), at.plusSeconds(60))

  test("the timer reads every held currency in turn"):
    val held  = CatalogueState(Map("USD" -> Vector.empty, "EUR" -> Vector(uf)))
    var asked = Vector.empty[String]
    val out = Catalogue.refreshes(held, c => { asked :+= c.code; Right(Vector(coz)) }, at)
    assertEquals((asked, out.map(_.currency)), (Vector("EUR", "USD"), Vector("EUR", "USD")))

  test("a currency the gateway could not give keeps its last list"):
    val held = CatalogueState(Map("EUR" -> Vector(uf), "USD" -> Vector(coz)))
    val out  = Catalogue.refreshes(held, c => if c.code == "EUR" then Left("503") else Right(Vector.empty), at)
    assertEquals(out, Vector(Refresh("USD", Vector.empty, at)))

  test("the timer's name is the slug's"):
    assertEquals(CatalogueTimer.name("goldvibe"), "terminals-goldvibe")
