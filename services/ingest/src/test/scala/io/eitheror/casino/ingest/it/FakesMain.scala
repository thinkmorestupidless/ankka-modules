package io.eitheror.casino.ingest.it

import io.eitheror.casino.ingest.shape.Digests

/**
 * `merchant` as the suites fake it, on merchant's own local port (9000), for `just ingest-run` without a
 * Keycloak to issue a key through merchant's operator routes: it answers `/v1/cashier/keys` with one key,
 * `mk_local_goldvibe`, held by sub-brand `goldvibe-local`, which `ingest`'s start refresh indexes.
 */
@main def fakes(): Unit =
  val merchant = FakeMerchant(9000)
  merchant.keys = Vector((Digests.ofKey("mk_local_goldvibe"), "k-local", "goldvibe-local"))
  println(s"FakeMerchant on ${merchant.address}: key mk_local_goldvibe for sub-brand goldvibe-local")
  Thread.currentThread().join()
