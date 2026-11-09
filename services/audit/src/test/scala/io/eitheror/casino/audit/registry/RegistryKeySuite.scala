package io.eitheror.casino.audit.registry

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.SecretStore
import io.eitheror.casino.audit.ids.Ids

import scala.collection.mutable

class RegistryKeySuite extends munit.FunSuite:

  private final class MapStore extends SecretStore:
    val values = mutable.Map.empty[String, String]
    def put(name: String, value: String): Unit = values(name) = value
    def get(name: String): Option[String]      = values.get(name)
    def delete(name: String): Unit             = values.remove(name): Unit

  override def beforeEach(context: BeforeEach): Unit = RegistryKey.forget()

  test("unavailable until the seed has made it; then two starts read the same key") {
    val store = MapStore()
    assertEquals(RegistryKey.read(store), Left("the registry-key is not made yet"))
    val made = RegistryKey.make(store)
    val first = RegistryKey.read(store).toOption.get
    RegistryKey.forget()
    assertEquals(RegistryKey.make(store).toString, made.toString)
    val second = RegistryKey.read(store).toOption.get
    assertEquals(Ids.digest(first, "goldvibe", "x"), Ids.digest(second, "goldvibe", "x"))
    assertEquals(Ids.digest(made, "goldvibe", "x"), Ids.digest(second, "goldvibe", "x"))
    assertEquals(store.values.size, 1)
  }

  test("read once and held: a store that fails later is not asked again") {
    val store = MapStore()
    RegistryKey.make(store): Unit
    val key = RegistryKey.read(store).toOption.get
    val failing = new SecretStore:
      def put(name: String, value: String): Unit = throw CommandError("down", ErrorCode.Unavailable)
      def get(name: String): Option[String]      = throw CommandError("down", ErrorCode.Unavailable)
      def delete(name: String): Unit             = throw CommandError("down", ErrorCode.Unavailable)
    assertEquals(RegistryKey.read(failing).map(Ids.digest(_, "b", "x")), Right(Ids.digest(key, "b", "x")))
    RegistryKey.forget()
    assert(RegistryKey.read(failing).left.exists(_.startsWith("the secret store is unavailable")))
  }

  test("a node the seed did not run on reads the keys from its own secret store on first use") {
    val store = MapStore()
    io.eitheror.casino.audit.Wiring.keys.set(None)
    io.eitheror.casino.audit.Wiring.secretStore.set(None)
    QuarantineKey.forget()
    // Before the seed has made them anywhere, a message is refused (and redelivered).
    intercept[IllegalStateException](io.eitheror.casino.audit.Wiring.keysFrom(store))
    // The seed, on another node: the same database's store, this node's Wiring untouched.
    val made = RegistryKey.make(store)
    QuarantineKey.make(store): Unit
    RegistryKey.forget()
    QuarantineKey.forget()
    val read = io.eitheror.casino.audit.Wiring.keysFrom(store)
    assertEquals(Ids.digest(read.registry, "goldvibe", "x"), Ids.digest(made, "goldvibe", "x"))
    io.eitheror.casino.audit.Wiring.keys.set(None)
  }

