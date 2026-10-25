package io.eitheror.casino.players.api

import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.players.player.PlayersView
import io.eitheror.casino.players.registration.RegistrationWorkflow

import java.util.Locale

/** Who holds an address in this brand: the registration that took it, else the players view's primary address. */
object Lookup:
  def playerOf(components: ComponentClient, views: ViewClient, slug: String, email: String): Option[String] =
    val lower = email.trim.toLowerCase(Locale.ROOT)
    val flow  = components.forWorkflow(RegistrationWorkflow.idOf(slug, lower)).call(RegistrationWorkflow.status).invoke()
    flow.outcome.flatMap(_.playerId).orElse {
      PlayersView.find(views, lower, 5).find(_.primaryEmail.reveal.equalsIgnoreCase(lower)).map(_.playerId)
    }
