package io.eitheror.casino.programme.postbacks

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * An affiliate's postback address with its macros filled (`S/Postback.scala:15-25`): `{event}`,
 * `{clickid}`, `{payout}` (default `0`) and `{status}` (default `ok`), each percent-encoded, so a click
 * id the player's browser chose cannot add parameters to the affiliate's tracker.
 */
object Macros:
  def render(template: String, event: String, clickId: Option[String], payout: Option[String] = None, status: Option[String] = None): String =
    template
      .replace("{event}", encode(event))
      .replace("{clickid}", encode(clickId.getOrElse("")))
      .replace("{payout}", encode(payout.getOrElse("0")))
      .replace("{status}", encode(status.getOrElse("ok")))

  // Form encoding writes a space as '+', a space only in a query; %20 is a space anywhere in a URL.
  private def encode(value: String): String = URLEncoder.encode(value, UTF_8).replace("+", "%20")
