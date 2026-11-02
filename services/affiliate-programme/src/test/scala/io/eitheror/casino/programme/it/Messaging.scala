package io.eitheror.casino.programme.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.accounts.{AccountFields, Affiliate, Secrets, UpdateAccount}
import io.eitheror.casino.programme.brands.{AddBrand, BrandProgramme, Plan, Sender, UpdateBrand}

import scala.jdk.CollectionConverters.*

/** What the communications suites start from: a brand that can send, an applicant, and what they were told. */
object Messaging:
  val Kinds = Seq("AffiliateApplicationReceived", "AffiliateApplicationApproved", "AffiliateApplicationRejected", "AffiliatePaymentProcessed",
    "AffiliateInvite", "AffiliatePasswordReset", "AffiliatePasswordChanged", "AffiliateSubPending")

  /** A brand of the scenario's own, with every message as `tid-<kind>` and the key `cio-key`, so its sends touch no other suite. */
  def brand(kit: Kit): String =
    val slug = kit.fresh("b").toLowerCase
    val e = kit.components.forKeyValueEntity(EntityId(slug))
    e.call(BrandProgramme.add).invoke(AddBrand(slug, BrandProgramme.Elsewhere, Some(slug), "UTC", "kit", kit.now)): Unit
    e.call(BrandProgramme.update).invoke(UpdateBrand(sender = Some(Sender(messages = Kinds.map(k => k -> s"tid-$k").toMap)))): Unit
    Secrets(kit.secrets).putBrandSecret(slug, Secrets.CustomerIoKey, "cio-key"): Unit
    slug

  def apply(kit: Kit, brand: String, email: String): Reply =
    kit.request("POST", s"/v1/affiliates/$brand/apply", Some(s"""{"email":"$email","name":"Ann Lee"}"""), kit.fromInternet)

  def approve(kit: Kit, brand: String, aff: String): Reply =
    val plan = kit.plan(brand, kit.fresh("p"), Plan.Cpa, cpa = 1_000)
    kit.request("POST", s"/v1/affiliates/$brand/accounts/$aff/approve", Some(s"""{"planId":"$plan"}"""), kit.asManager(Seq(brand)))

  def notifications(kit: Kit, aff: String): Vector[JsonNode] =
    Option(kit.request("GET", s"/v1/my/$aff/notifications", headers = kit.asAffiliate(aff)).json).toVector.flatMap(_.elements().asScala)

  def told(kit: Kit, aff: String, kind: String): JsonNode =
    kit.eventually(s"$aff told $kind")(notifications(kit, aff).find(_.get("type").asText == kind))

  def emailed(kit: Kit, email: String, kind: String) =
    kit.eventually(s"$kind to $email")(kit.customerIo.to(email).find(_.transactionalId == s"tid-$kind"))

  def postbackTo(kit: Kit, affiliate: String): Unit =
    kit.components.forEventSourcedEntity(EntityId(affiliate)).call(Affiliate.update)
      .invoke(UpdateAccount(AccountFields(postbackUrl = Some(kit.postbacks.url())), "kit", kit.now)): Unit
