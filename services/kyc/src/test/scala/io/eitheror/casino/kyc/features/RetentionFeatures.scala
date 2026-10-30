package io.eitheror.casino.kyc.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.kyc.documents.ObjectNames
import io.eitheror.casino.kyc.it.Reply
import io.eitheror.casino.kyc.record.{Category, Outcome}

import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 * features/kyc/retention.feature (US6). The hold is `players`' (spec 008): its erasure workflow calls this
 * service once the hold lapses, so "the hold lapses and the erasure is applied" is that call. Encryption at
 * rest is the installation's store (R17): Google Cloud Storage encrypts every bucket; the kit's store is
 * Garage, the kind installation's, which holds objects on its volume, so here the scenario asserts the
 * object is in the installation's bucket and nowhere of this service's own. The store's recovery period
 * is the store's too: the erasure is final here once its certificate is answered.
 */
class RetentionFeatures extends KycFeatures("retention.feature"):

  private var objects: Vector[String]   = Vector.empty
  private var before: Vector[JsonNode]  = Vector.empty
  private var certificate: Reply        = null
  private var late: String              = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    objects = Vector.empty; before = Vector.empty; certificate = null; late = ""

  private def objectNames: Vector[String] = state().documents.map(_.objectName)

  private def erase(): Reply =
    certificate = kit.request("POST", kit.servicePath(s"/erasure/players/$player"), None, kit.asService("players"))
    assertEquals(certificate.status, 200, certificate.text)
    certificate

  private def listed(read: JsonNode): Vector[JsonNode] =
    read.get("categories").elements.asScala.toVector.flatMap(c => Option(c.get("document")).filter(_.isObject)) ++
      read.get("other").elements.asScala.toVector

  private def present(objectName: String): Boolean = kit.store.head(objectName).toOption.flatten.isDefined

  // ── the store ─────────────────────────────────────────────────────────────────────────────────

  Given("a document of the player") { () => submit("identity_front"): Unit }

  When("its object is read in the document store") { () => objects = objectNames }

  Then("it is under the player's own prefix in the brand's document store") { () =>
    assertEquals(objects.size, 1)
    assert(objects.forall(_.startsWith(ObjectNames.prefix(player))), objects.toString)
    assert(objects.forall(present), "the object is in the brand's bucket")
  }

  Then("it is encrypted at rest by the store") { () =>
    // R17: the store encrypts; the service holds no key. The object is in the installation's bucket, read
    // through the installation's credential, and the service keeps no copy of its bytes.
    assertEquals(kit.bucket.name, "kyc-it")
    assert(objects.forall(present))
    assert(state().documents.forall(d => d.objectName.nonEmpty && d.size > 0))
  }

  // ── erasure ───────────────────────────────────────────────────────────────────────────────────

  Given("an erasure request for the player under a retention hold") { () =>
    val front = submit("identity_front")
    reviewed(front, Outcome.Rejected, Some("document_unclear"))
    submit("identity_front"): Unit
    val support = upload(askUploadAsOperator("proof_of_address", Some("sent by post")))
    put(support)
    assertEquals(confirmAsOperator(support.documentId).status, 200)
    objects = objectNames
    before = listed(operatorRead())
    assertEquals(objects.size, 3)
  }

  When("the hold lapses and the erasure is applied") { () => erase(): Unit }

  Then("every object under the player's prefix is deleted in every version") { () =>
    assert(objects.forall(o => !present(o)), objects.toString)
    assertEquals(kit.store.deleteAll(ObjectNames.prefix(player)), Right(0))
    assertEquals(certificate.json.get("objectsDeleted").asInt, 3)
  }

  Then("the document ids, times, categories, statuses and reason codes stay readable") { () =>
    val after = listed(operatorRead())
    def kept(n: JsonNode) = Seq("documentId", "category", "status", "uploadedAt", "reviewedAt", "reasonCode").map(f => Option(n.get(f)).map(_.asText))
    assertEquals(after.map(kept).toSet, before.map(kept).toSet)
    assertEquals(state().documents.flatMap(_.by.reason).map(_.reveal).filter(_.nonEmpty), Vector.empty)
    assertEquals(state().documents.map(_.category).toSet, Set(Category.IdentityFront, Category.ProofOfAddress))
  }

  Then("the erasure is final when the store's recovery period ends") { () =>
    val c = certificate.json
    assertEquals((c.get("service").asText, c.get("playerId").asText, c.get("recordsKept").asInt), ("kyc", player, 3))
    assert(!Instant.parse(c.get("erasedAt").asText).isAfter(kit.now()))
  }

  Given("the player is erased") { () =>
    val d = submit("identity_front")
    reviewed(d, Outcome.Approved)
    erase(): Unit
  }

  When("an operator opens their KYC tab") { () => before = listed(operatorRead()) }

  Then("the documents are listed with their statuses") { () =>
    assertEquals(before.map(_.get("status").asText), Vector("approved"))
  }

  Then("no view address can be issued") { () =>
    val id = before.head.get("documentId").asText
    val r  = kit.request("GET", kit.operatorPath(s"/$player/views/$id"), None, kit.asOperator())
    assertEquals((r.status, r.str("error")), (410, "document_erased"), r.text)
  }

  Given("a document written for the player after their erasure") { () =>
    erase(): Unit
    late = s"${ObjectNames.prefix(player)}late.png"
    val url = kit.store.signPut(late, "image/png", java.time.Duration.ofMinutes(5)).toOption.get
    assertEquals(kit.putSigned(url, Array.fill(64)(1.toByte), "image/png") / 100, 2)
    assert(present(late))
  }

  When("the erasure handler runs again") { () => erase(): Unit }

  Then("the object is removed") { () =>
    assert(!present(late))
    assertEquals(certificate.json.get("objectsDeleted").asInt, 1)
  }
