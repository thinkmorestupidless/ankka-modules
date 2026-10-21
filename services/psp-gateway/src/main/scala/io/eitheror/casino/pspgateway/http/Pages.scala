package io.eitheror.casino.pspgateway.http

import com.thinkmorestupidless.ankka.http.Html

/** The pages the player's browser is shown (US6): the relay that POSTs a session's form, and the neutral processing page. */
object Pages:

  /**
   * The original's neutral processing page (`hpp.go:919-927`), word for word: every return route and an
   * unknown reference. It fires whatever the outcome, so it implies none.
   */
  val processing: Html = Html(
    """<!DOCTYPE html><html><head><meta charset="UTF-8"><title>Payment processing</title></head>""" +
      """<body><p>Your payment is being processed. Return to the casino to check your deposit status. """ +
      """You can close this window.</p></body></html>""")

  /**
   * The relay (`dreamzpay/relay.html`; EuPaymentz's `hpp.go:831-839` is the same form): a page that POSTs
   * `form` to `action` as it loads. Every value is escaped, as Go's `html/template` escapes it.
   */
  def relay(action: String, form: Form): Html =
    val fields = form.entries.map((k, v) => s"""    <input type="hidden" name="${escape(k)}" value="${escape(v)}">""").mkString("\n")
    Html(
      s"""<!DOCTYPE html>
         |<html lang="en">
         |<head>
         |  <meta charset="UTF-8">
         |  <meta name="viewport" content="width=device-width, initial-scale=1.0">
         |  <title>Redirecting to payment page…</title>
         |  <style>
         |    body { margin: 0; display: flex; align-items: center; justify-content: center; min-height: 100vh;
         |      font-family: system-ui, sans-serif; background: #f5f5f5; color: #333; }
         |    .message { text-align: center; }
         |    .message p { margin-top: 0.5rem; font-size: 0.9rem; color: #666; }
         |  </style>
         |</head>
         |<body onload="document.getElementById('relay-form').submit()">
         |  <div class="message">
         |    <strong>Redirecting to your payment provider…</strong>
         |    <p>Please do not close this window.</p>
         |  </div>
         |  <form id="relay-form" action="${escape(action)}" method="POST" style="display:none">
         |$fields
         |  </form>
         |</body>
         |</html>
         |""".stripMargin)

  /** As Go's `html/template` escapes an attribute value. */
  def escape(s: String): String =
    s.flatMap {
      case '&'  => "&amp;"
      case '<'  => "&lt;"
      case '>'  => "&gt;"
      case '"'  => "&#34;"
      case '\'' => "&#39;"
      case '+'  => "&#43;"
      case c    => c.toString
    }
