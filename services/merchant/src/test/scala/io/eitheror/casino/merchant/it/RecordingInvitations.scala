package io.eitheror.casino.merchant.it

import io.eitheror.casino.merchant.operators.{Invitation, Invitations}

import scala.collection.mutable

/** The invitation seam in the suites: what would have been sent, with its token, in order. */
final class RecordingInvitations extends Invitations:
  private val sent = mutable.ArrayBuffer.empty[Invitation]
  @volatile var failing = false
  def send(invitation: Invitation): Either[String, Unit] = synchronized {
    if failing then Left("the provider is down") else { sent += invitation; Right(()) }
  }
  def all: Vector[Invitation] = synchronized(sent.toVector)
  def to(email: String): Vector[Invitation] = all.filter(_.email == email)
  def reset(): Unit = synchronized { sent.clear(); failing = false }
