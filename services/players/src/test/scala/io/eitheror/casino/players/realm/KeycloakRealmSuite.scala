package io.eitheror.casino.players.realm

import io.eitheror.casino.players.Json
import io.eitheror.casino.players.it.FakeRealm

/** contracts/realm.md's first table against Keycloak's answers (T020). */
class KeycloakRealmSuite extends munit.FunSuite:

  private val fake = FakeRealm("goldvibe")
  private def realm(admin: Option[KeycloakRealm.AdminClient] = Some(KeycloakRealm.AdminClient(fake.adminClient, fake.adminSecret))) =
    KeycloakRealm(fake.address, fake.realm, () => admin)

  override def beforeEach(context: BeforeEach): Unit = fake.reset()
  override def afterAll(): Unit                      = fake.stop()

  private def registered(r: Realm, email: String = "ada@example.test") =
    val id = r.createUser(email, "Passw0rd!", "p-1").toOption.get
    r.setVerified(id): Unit
    id

  test("register: the user with the password, unverified, and the player id; the refusals mapped") {
    val r  = realm()
    val id = r.createUser("ada@example.test", "Passw0rd!", "p-1")
    assert(id.isRight, id.toString)
    val u = fake.userByEmail("ada@example.test").get
    assertEquals((u.playerId, u.verified, u.enabled), ("p-1", false, true))
    assertEquals(r.createUser("ada@example.test", "Passw0rd!", "p-2"), Left(RealmRefusal.EmailExists))
    assertEquals(r.createUser("bo@example.test", "a", "p-3"), Left(RealmRefusal.PasswordTooWeak))
    assertEquals(KeycloakRealm(fake.address, "casino-nope", () => Some(KeycloakRealm.AdminClient(fake.adminClient, fake.adminSecret)))
      .createUser("x@example.test", "Passw0rd!", "p"), Left(RealmRefusal.RealmNotFound))
  }

  test("the admin token is fetched once for two calls; no admin client is not configured") {
    val r = realm()
    r.createUser("a@example.test", "Passw0rd!", "p-1"): Unit
    r.userOf("p-1"): Unit
    assertEquals(fake.adminTokens, 1)
    assert(realm(None).deleteUser("x").left.exists(_.isInstanceOf[RealmRefusal.NotConfigured]))
  }

  test("a grant answers the tokens with the player and the realm session; each refusal mapped") {
    val r = realm()
    val pending = r.createUser("new@example.test", "Passw0rd!", "p-9").toOption.get
    assertEquals(r.directGrant("new@example.test", "Passw0rd!"), Left(RealmRefusal.EmailNotVerified))
    r.setVerified(pending): Unit
    val t = r.directGrant("new@example.test", "Passw0rd!").toOption.get
    assertEquals(t.playerId, "p-9")
    assert(fake.sessions.containsKey(t.realmSessionId))
    assertEquals(r.directGrant("new@example.test", "wrong"), Left(RealmRefusal.InvalidCredentials))
    r.setEnabled(pending, enabled = false): Unit
    assertEquals(r.directGrant("new@example.test", "Passw0rd!"), Left(RealmRefusal.Disabled))
  }

  test("a refresh answers new tokens once; the used refresh token is refused invalid_token") {
    val r = realm()
    registered(r)
    val first  = r.directGrant("ada@example.test", "Passw0rd!").toOption.get
    val second = r.refresh(first.refreshToken)
    assert(second.isRight)
    assertEquals(r.refresh(first.refreshToken), Left(RealmRefusal.InvalidToken))
  }

  test("verifying a password leaves no session; revoking one twice is fine") {
    val r = realm()
    registered(r)
    assertEquals(r.verifyPassword("ada@example.test", "Passw0rd!"), Right(()))
    assert(fake.sessions.isEmpty)
    assertEquals(r.verifyPassword("ada@example.test", "nope"), Left(RealmRefusal.InvalidCredentials))
    val t = r.directGrant("ada@example.test", "Passw0rd!").toOption.get
    assertEquals(r.revokeSession(t.realmSessionId), Right(()))
    assertEquals(r.revokeSession(t.realmSessionId), Right(()))
  }

  test("live sessions, address change, enable, reset email, set password, user by player id, delete") {
    val r  = realm()
    val id = registered(r)
    val t  = r.directGrant("ada@example.test", "Passw0rd!").toOption.get
    assertEquals(r.liveSessions().map(_.map(s => (s.sessionId, s.userId))), Right(Vector((t.realmSessionId, id))))
    assertEquals(r.changeAddress(id, "ada2@example.test"), Right(()))
    assertEquals(fake.users.get(id).email, "ada2@example.test")
    assertEquals(r.sendResetEmail(id), Right(()))
    assertEquals(fake.users.get(id).actions, Vector("UPDATE_PASSWORD"))
    assertEquals(r.setPassword(id, "x"), Left(RealmRefusal.PasswordTooWeak))
    assertEquals(r.setPassword(id, "NewPassw0rd!"), Right(()))
    assertEquals(r.userOf("p-1"), Right(id))
    assertEquals(r.userOf("p-none"), Left(RealmRefusal.UserNotFound))
    assertEquals(r.deleteUser(id), Right(()))
    assertEquals(r.deleteUser(id), Left(RealmRefusal.UserNotFound))
  }

  test("a token without the twin-id claim or the session refuses missing_token") {
    val noClaim = FakeRealm.jwt(Json.obj("sub" -> "u", "session_state" -> "s"))
    assertEquals(KeycloakRealm.tokens(Json.obj("access_token" -> noClaim)), Left(RealmRefusal.MissingToken))
    val noSession = FakeRealm.jwt(Json.obj("sub" -> "u", "eitheror_twin_id" -> "casino-goldvibe/p-1"))
    assertEquals(KeycloakRealm.tokens(Json.obj("access_token" -> noSession)), Left(RealmRefusal.MissingToken))
    val both = FakeRealm.jwt(Json.obj("eitheror_twin_id" -> "casino-goldvibe/p-1", "session_state" -> "s-1"))
    assertEquals(KeycloakRealm.tokens(Json.obj("access_token" -> both)).map(t => (t.playerId, t.realmSessionId)), Right(("p-1", "s-1")))
  }

  test("the webhook body and its secret, compared in constant time") {
    assertEquals(Webhook.parse("""{"type":"LOGOUT","realmId":"casino-goldvibe","sessionId":"s-1","userId":"-","timestamp":1700000000000}""")
      .map(e => (e.kind, e.sessionId, e.userId)), Some(("LOGOUT", "s-1", "-")))
    assertEquals(Webhook.parse("""{"type":"REGISTER","sessionId":"s"}"""), None)
    assert(Webhook.verify(Some("s3cret"), Some("s3cret")))
    assert(!Webhook.verify(Some("s3cre"), Some("s3cret")))
    assert(!Webhook.verify(None, Some("s3cret")))
    assert(!Webhook.verify(Some("x"), None))
  }
