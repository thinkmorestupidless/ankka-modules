package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.testkit.TestTransport

/** Test only: an endpoint's declared routes as `METHOD /prefix/template`, which ankka keeps package-private. */
object RoutesOf:
  def apply(e: HttpEndpoint): Vector[String] = e.routes.map(r => s"${r.method} ${e.prefix}${r.template.render}".stripSuffix("/"))

  /** Clients for building an endpoint whose constructor calls nothing. */
  def clients: EndpointClients = new EndpointClients(TestTransport.unroutedClient, null)
