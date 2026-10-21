package io.eitheror.casino.merchant.routing

import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.merchant.hierarchy.Actor

import java.time.Instant

/** FR-010 at the entity, with the original's deploy, rollback and retire rules. */
class RoutingTreeSuite extends munit.FunSuite:

  private val at  = Instant.parse("2026-10-12T10:00:00Z")
  private val op  = Actor.operator("op-1")
  private def tree(t: String) = Tree("root", Map("root" -> Node("root", None, Some(Vector(t)))))

  test("deploying with no draft is NO_DRAFT") {
    val kit = EventSourcedTestKit.of(RoutingTree, "subBrand/sb-1")
    assertEquals(kit.call(RoutingTree.deploy)(TreeChange(op, at)).errorMessage, "NO_DRAFT")
  }

  test("the first deploy is version 1, live with its time; the draft is cleared; a later deploy is version 2 in the history") {
    val kit = EventSourcedTestKit.of(RoutingTree, "subBrand/sb-1")
    kit.call(RoutingTree.saveDraft)(SaveDraft(tree("A"), op, at)): Unit
    assertEquals(kit.call(RoutingTree.deploy)(TreeChange(op, at)).replyValue, Version(1, at, tree("A")))
    assertEquals((kit.currentState.liveVersion, kit.currentState.liveDeployedAt, kit.currentState.draft), (Some(1), Some(at), None))
    kit.call(RoutingTree.saveDraft)(SaveDraft(tree("B"), op, at)): Unit
    assertEquals(kit.call(RoutingTree.deploy)(TreeChange(op, at.plusSeconds(60))).replyValue.version, 2)
    assertEquals(kit.currentState.history.map(_.version), Vector(1, 2))
    assertEquals(kit.currentState.liveTree, Some(tree("B")))
  }

  test("a rollback copies the version's tree into the draft, and deploying it makes it live again") {
    val kit = EventSourcedTestKit.of(RoutingTree, "subBrand/sb-1")
    kit.call(RoutingTree.saveDraft)(SaveDraft(tree("A"), op, at)): Unit
    kit.call(RoutingTree.deploy)(TreeChange(op, at)): Unit
    kit.call(RoutingTree.saveDraft)(SaveDraft(tree("B"), op, at)): Unit
    kit.call(RoutingTree.deploy)(TreeChange(op, at)): Unit
    kit.call(RoutingTree.rollback)(Rollback(1, op, at)): Unit
    assertEquals(kit.currentState.draft, Some(tree("A")))
    assertEquals(kit.call(RoutingTree.deploy)(TreeChange(op, at)).replyValue.version, 3)
    assertEquals(kit.currentState.liveTree, Some(tree("A")))
    assertEquals(kit.call(RoutingTree.rollback)(Rollback(9, op, at)).error.code, ErrorCode.NotFound)
  }

  test("retiring leaves nothing live, and retiring with nothing live is a no-op") {
    val kit = EventSourcedTestKit.of(RoutingTree, "holding/h-1")
    assert(!kit.call(RoutingTree.retire)(TreeChange(op, at)).persisted)
    kit.call(RoutingTree.saveDraft)(SaveDraft(tree("A"), op, at)): Unit
    kit.call(RoutingTree.deploy)(TreeChange(op, at)): Unit
    kit.call(RoutingTree.retire)(TreeChange(op, at)): Unit
    assertEquals(kit.currentState.liveTree, None)
    kit.call(RoutingTree.saveDraft)(SaveDraft(tree("C"), op, at)): Unit
    assertEquals(kit.call(RoutingTree.deploy)(TreeChange(op, at)).replyValue.version, 2, "never a second version 1")
  }
