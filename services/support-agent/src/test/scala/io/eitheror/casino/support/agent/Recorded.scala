package io.eitheror.casino.support.agent

import io.eitheror.casino.support.clients.*
import io.eitheror.casino.support.conversation.{EndReason, ToolCall}

import scala.collection.mutable

/**
 * The five neighbours in memory, for a tool's unit suite: each call is recorded as `<service> <call> <args>`, and
 * answered as scripted for that call's name, or 200 with `{}`. A `Trail` that keeps what the tools record.
 */
final class Recorded:
  val calls    = mutable.ArrayBuffer.empty[String]
  val scripted = mutable.Map.empty[String, Called]

  private def answer(service: String, call: String, args: Any*): Called =
    calls += (s"$service $call" +: args.map(_.toString)).mkString(" ")
    scripted.getOrElse(call, Called.Answered(200, "{}"))

  def ok(call: String, text: String): Unit = scripted.update(call, Called.Answered(200, text))

  val neighbours: Neighbours = Neighbours(
    new Players:
      def status(p: String)                          = answer("players", "status", p)
      def agentProfile(p: String, f: Set[String])    = answer("players", "agentProfile", p, f.toVector.sorted.mkString(","))
      def sessions(p: String)                        = answer("players", "sessions", p)
      def endSession(s: String, a: String)           = answer("players", "endSession", s, a)
    ,
    new Wallet:
      def balance(p: String, c: String)              = answer("wallet", "balance", p, c)
      def transactions(p: String, c: String, l: Int) = answer("wallet", "transactions", p, c, l)
      def holdPreview(p: String, c: String, a: Long) = answer("wallet", "holdPreview", p, c, a)
    ,
    new Payouts:
      def requestWithdrawal(p: String, a: Long, c: String, k: String, f: Boolean) = answer("payouts", "requestWithdrawal", p, a, c, k, f)
    ,
    new Rewards:
      def grants(p: String) = answer("rewards", "grants", p)
    ,
    new Rg:
      def limits(p: String, c: String)                                  = answer("rg", "limits", p, c)
      def setLimit(p: String, t: String, v: Long, c: String, k: String) = answer("rg", "setLimit", p, t, v, c, k)
      def coolOff(p: String, u: String, k: String)                      = answer("rg", "coolOff", p, u, k)
      def exclude(p: String, k: String)                                 = answer("rg", "exclude", p, k)
  )

  val toolCalls = mutable.ArrayBuffer.empty[ToolCall]
  val ends      = mutable.ArrayBuffer.empty[EndReason]
  @volatile var approvedId: Option[String] = None
  @volatile var forfeitNamed: Option[Long]  = None

  val trail: Trail = new Trail:
    def called(c: ToolCall): Unit     = toolCalls += c
    def approved: Option[String]      = approvedId
    def namedForfeit: Option[Long]    = forfeitNamed
    def ended(r: EndReason): Unit     = ends += r
