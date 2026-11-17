package io.eitheror.casino.reporting.api


import java.nio.charset.StandardCharsets.UTF_8

/** The documents' routes answer unread roles as `Routes.operatorMay` does (review S6). */
class DocumentsUnreadSuite extends munit.FunSuite:

  test("until the brand's roles are read, a document read is 503 roles_unread with Retry-After") {
    val a = Documents.unread
    assertEquals(a.status, 503)
    assertEquals(a.headers.toMap.get("Retry-After"), Some("5"))
    val body = String(a.body.body, UTF_8)
    assert(body.contains(""""error":"roles_unread""""), body)
  }
