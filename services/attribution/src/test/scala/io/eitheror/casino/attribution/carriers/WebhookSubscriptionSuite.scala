package io.eitheror.casino.attribution.carriers

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit

import java.time.Instant

class WebhookSubscriptionSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-10-25T10:00:00Z")
  private val id  = "0b7e2c56-1111-4222-8333-444455556666"
  private def kit = KeyValueEntityTestKit.of(WebhookSubscription, id)
  private def create(address: String = "https://n.example/hook") = CreateSubscription(id, "programme", address, "staff-1", at)

  test("create with an http:// address is refused naming the field") {
    assert(kit.call(WebhookSubscription.subscribe)(create("http://n.example/hook")).errorMessage.startsWith("address"))
  }

  test("create holds the secret's name, never a secret; a second create is refused") {
    val k = kit
    val s = k.call(WebhookSubscription.subscribe)(create()).replyValue
    assertEquals((s.network, s.active, s.secretName), ("programme", true, s"webhook/$id/secret"))
    assert(!s.productIterator.exists(_ == "s3cret"))
    assert(k.call(WebhookSubscription.subscribe)(create()).isError)
  }

  test("update the address and the active flag") {
    val k = kit
    k.call(WebhookSubscription.subscribe)(create()): Unit
    val s = k.call(WebhookSubscription.update)(UpdateSubscription(Some("https://n.example/v2"), Some(false), at.plusSeconds(5))).replyValue
    assertEquals((s.address, s.active, s.updatedAt), ("https://n.example/v2", false, Some(at.plusSeconds(5))))
    assert(k.call(WebhookSubscription.update)(UpdateSubscription(Some("http://x"), None, at)).isError)
  }

  test("delete; then it no longer exists and may be created again") {
    val k = kit
    k.call(WebhookSubscription.subscribe)(create()): Unit
    val d = k.call(WebhookSubscription.delete)(at.plusSeconds(9)).replyValue
    assert(!d.exists)
    assert(k.call(WebhookSubscription.delete)(at).isError)
    assert(k.call(WebhookSubscription.update)(UpdateSubscription(None, Some(true), at)).isError)
    assert(k.call(WebhookSubscription.subscribe)(create()).replyValue.exists)
  }
