package io.eitheror.casino.wallet.features

/**
 * features/wallet/reservations.feature (spec 022 User Story 1, and User Story 4's hold and bet):
 * money set aside under an order's key, released, and what a hold or a bet may draw beside it.
 */
class ReservationsFeatures extends WalletFeatures("reservations.feature"):

  Given("a real balance of {string}, no restricted funds and no deposit turnover pending") { (r: String) =>
    seeding(_.copy(real = long(r), pending = 0L))
  }

  Given("a real balance of {string} and restricted funds of {string}") { (r: String, b: String) =>
    seeding(_.copy(real = long(r)))
    addRecord(record("r1", long(b)))
  }

  When("a hold of {string} is placed for request {string}") { (a: String, request: String) => act(hold(long(a), request)): Unit }
