package com.thinkmorestupidless.ankka.http

/** Test only: an endpoint's declared routes as `METHOD /template`, which ankka keeps package-private. */
object RoutesOf:
  def apply(e: HttpEndpoint): Vector[String] = e.routes.map(r => s"${r.method} ${r.template.render}")
