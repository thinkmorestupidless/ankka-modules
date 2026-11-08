package io.eitheror.casino.programme.comms

import java.time.Duration

class CustomerIoSuite extends munit.FunSuite:
  test("a send posts the original's body with the bearer key and answers the status") {
    val stub = Stub((_, _, _) => (200, "{}", Map.empty))
    try
      val cio = CustomerIo.http(Duration.ofSeconds(5), _ => stub.url)
      assertEquals(cio.send("key-1", "eu", "7", "ada@example.com", Map("name" -> "Ada")), Right(200))
      val s = stub.seen.head
      assertEquals((s.method, s.path, s.headers("authorization")), ("POST", "/v1/send/email", "Bearer key-1"))
      assertEquals(s.body, """{"transactional_message_id":"7","to":"ada@example.com","identifiers":{"email":"ada@example.com"},"message_data":{"name":"Ada"}}""")
    finally stub.stop()
  }

  test("the region picks the API's base; eu is the default") {
    assertEquals((CustomerIo.base("us"), CustomerIo.base("eu"), CustomerIo.base("")), ("https://api.customer.io", "https://api-eu.customer.io", "https://api-eu.customer.io"))
  }

  test("nothing listening is an error, not a status") {
    assert(CustomerIo.http(Duration.ofSeconds(1), _ => "http://127.0.0.1:1").send("k", "eu", "1", "a@b.c", Map.empty).isLeft)
  }
