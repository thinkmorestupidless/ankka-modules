package io.eitheror.casino.publicapi.api

import io.eitheror.casino.publicapi.contract.Operation

/**
 * What a `/v1` request carries, taken from ankka's `request` on the calling thread before the work starts under the
 * deadline (`Deadline`): the operation, its path ids in the template's order, the query and the `Authorization` header.
 */
final case class Call(
    operation: Option[Operation],
    ids: Vector[(String, String)],
    query: Vector[(String, String)],
    authorization: Option[String],
    path: String
):
  def id(name: String): Option[String]    = ids.collectFirst { case (n, v) if n == name => v }
  def param(name: String): Option[String] = query.collectFirst { case (n, v) if n == name => v }
  def params(name: String): Vector[String] = query.collect { case (n, v) if n == name => v }
