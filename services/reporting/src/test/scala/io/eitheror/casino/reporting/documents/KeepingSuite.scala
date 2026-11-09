package io.eitheror.casino.reporting.documents

import com.thinkmorestupidless.ankka.sdk.{Bucket, ObjectEntry, ObjectInfo, SignedAccess, StoredObject}
import com.thinkmorestupidless.ankka.testkit.InMemoryObjectStorage
import io.eitheror.casino.reporting.api.Wire
import io.eitheror.casino.reporting.render.Csv

import java.io.{FilterInputStream, InputStream}
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/** Review, should fix 3: an export over the synchronous limit is streamed into its document, never held whole. */
class KeepingSuite extends munit.FunSuite:

  /** The memory bucket, recording each part opened and, at each read of the document's stream, how many were open. */
  private final class Watched(memory: Bucket) extends Bucket:
    val opened  = mutable.Buffer.empty[String]
    val atReads = mutable.Buffer.empty[Int]
    var whole   = false
    def name     = memory.name
    def exposure = memory.exposure
    def put(name: String, body: InputStream, length: Long, contentType: String): Unit =
      val watched = new FilterInputStream(body):
        override def read(b: Array[Byte], off: Int, len: Int): Int =
          atReads += opened.size
          super.read(b, off, len)
      memory.put(name, watched, length, contentType)
    override def putBytes(name: String, body: Array[Byte], contentType: String): Unit =
      if !name.contains(".parts/") then whole = true
      memory.putBytes(name, body, contentType)
    def get(name: String): Option[StoredObject] =
      opened += name
      memory.get(name)
    def delete(name: String): Unit                        = memory.delete(name)
    def list(prefix: String): Iterator[ObjectEntry]       = memory.list(prefix)
    def stat(name: String): Option[ObjectInfo]            = memory.stat(name)
    def signedUrl(name: String, access: SignedAccess, span: FiniteDuration): URI = memory.signedUrl(name, access, span)
    def url(name: String): URI                            = memory.url(name)

  test("200,000 rows in 200 pages: each page opened only once the one before it is read, the document their bytes in order") {
    val bucket = Watched(InMemoryObjectStorage().default)
    val name   = "reports/report-export/ref-1.csv"
    val pages  = 200
    val rows   = 1_000
    val expected = new StringBuilder
    for p <- 0 until pages do
      val text = (if p == 0 then Csv.row(Vector("transactionId", "amount")) else "") +
        (0 until rows).map(r => Csv.row(Vector(s"tx-$p-$r", s"${p * rows + r}.00"))).mkString
      expected ++= text
      bucket.putBytes(Document.part(name, p), text.getBytes(UTF_8), Keeping.PlayersType)
    bucket.opened.clear()
    Keeping.joinParts(bucket, name, pages, "text/csv")
    assert(!bucket.whole, "the document was put whole")
    assertEquals(bucket.opened.toVector, (0 until pages).map(Document.part(name, _)).toVector)
    // the first read had one page open, and the pages were opened one at a time as the reads went on
    assertEquals(bucket.atReads.head, 1)
    assertEquals(bucket.atReads.toVector, bucket.atReads.toVector.sorted)
    val kept = bucket.getBytes(name).get._2
    assertEquals(kept.length, expected.toString.getBytes(UTF_8).length)
    assertEquals(String(kept, UTF_8).linesIterator.size, pages * rows + 1)
    assert(String(kept, UTF_8) == expected.toString)
  }

  test("a stream of chunks makes each only when the one before it has been read") {
    val made  = mutable.Buffer.empty[Int]
    val in    = Wire.concatenated(Iterator.tabulate(3) { i => made += i; s"chunk-$i;".getBytes(UTF_8) })
    assertEquals(made.toVector, Vector(0))
    assertEquals(String(in.readNBytes(8), UTF_8), "chunk-0;")
    assertEquals(String(in.readAllBytes(), UTF_8), "chunk-1;chunk-2;")
    assertEquals(made.toVector, Vector(0, 1, 2))
  }
