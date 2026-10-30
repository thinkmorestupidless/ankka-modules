package io.eitheror.casino.kyc.documents

import io.eitheror.casino.kyc.Bucket
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.checksums.{RequestChecksumCalculation, ResponseChecksumValidation}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.interceptor.{Context, ExecutionAttributes, ExecutionInterceptor}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.{GetObjectPresignRequest, PutObjectPresignRequest}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import java.io.{ByteArrayInputStream, InputStream}
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** An object's size and type, as the store reports them at confirm (research R7). */
final case class Head(size: Long, contentType: String)

/**
 * The service's bucket (research R7): signed links for the browser, and the reads and deletes the
 * service makes itself. Every operation answers `Left` with what failed when the store cannot be
 * reached, which the routes answer `503 storage_unavailable`; nothing here throws.
 */
trait Store:
  /** A PUT of `objectName` with its content type signed, valid for `ttl`. */
  def signPut(objectName: String, contentType: String, ttl: Duration): Either[String, String]

  /** A GET of `objectName`, valid for `ttl`. */
  def signGet(objectName: String, ttl: Duration): Either[String, String]

  /** The object's head, or none when there is no such object. */
  def head(objectName: String): Either[String, Option[Head]]

  def delete(objectName: String): Either[String, Unit]

  /** Every version of every object under `prefix` deleted; the count of versions deleted (FR-033). */
  def deleteAll(prefix: String): Either[String, Int]

object Store:
  /** No bucket was given (a local run without a store): every operation is refused. */
  object Unavailable extends Store:
    private val no                                                        = Left("no bucket is configured")
    def signPut(objectName: String, contentType: String, ttl: Duration) = no
    def signGet(objectName: String, ttl: Duration)                        = no
    def head(objectName: String)                                          = no
    def delete(objectName: String)                                        = no
    def deleteAll(prefix: String)                                         = no

  def of(bucket: Option[Bucket]): Store = bucket.fold(Unavailable)(S3Store(_))

/**
 * The bucket through the AWS SDK as platform/object-storage.md configures it: path style, the
 * platform's region, checksums only when required, and Google's `<Generation>` read as `<VersionId>`.
 * Links are signed against the public endpoint; the service reads and deletes through the other.
 */
final class S3Store(bucket: Bucket) extends Store:
  private val log         = LoggerFactory.getLogger("io.eitheror.casino.kyc.documents")
  private val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(bucket.accessKey, bucket.secretKey))
  private val pathStyle   = S3Configuration.builder().pathStyleAccessEnabled(true).build()

  private val client: S3Client =
    S3Client
      .builder()
      .endpointOverride(URI.create(bucket.endpoint))
      .region(Region.of(bucket.region))
      .forcePathStyle(true)
      .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
      .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
      .overrideConfiguration(ClientOverrideConfiguration.builder().addExecutionInterceptor(GenerationAsVersionId).build())
      .credentialsProvider(credentials)
      .build()

  private val presigner: S3Presigner =
    S3Presigner
      .builder()
      .endpointOverride(URI.create(bucket.publicEndpoint))
      .region(Region.of(bucket.region))
      .serviceConfiguration(pathStyle)
      .credentialsProvider(credentials)
      .build()

  /** The failure, named by the operation and the store's code, never by the credential. */
  private def attempt[A](operation: String)(f: => A): Either[String, A] =
    Try(f).toEither.left.map { e =>
      val reason = e match
        case s: S3Exception => s"${s.statusCode} ${Option(s.awsErrorDetails).map(_.errorCode).getOrElse("")}".trim
        case other          => other.getClass.getSimpleName
      log.warn("the bucket refused {}: {}", operation, reason)
      s"$operation: $reason"
    }

  def signPut(objectName: String, contentType: String, ttl: Duration): Either[String, String] =
    attempt("sign put") {
      val put = PutObjectRequest.builder().bucket(bucket.name).key(objectName).contentType(contentType).build()
      presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(ttl).putObjectRequest(put).build()).url.toString
    }

  def signGet(objectName: String, ttl: Duration): Either[String, String] =
    attempt("sign get") {
      val get = GetObjectRequest.builder().bucket(bucket.name).key(objectName).build()
      presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get).build()).url.toString
    }

  def head(objectName: String): Either[String, Option[Head]] =
    Try(client.headObject(HeadObjectRequest.builder().bucket(bucket.name).key(objectName).build())).toEither match
      case Right(h)                                         => Right(Some(Head(h.contentLength, Option(h.contentType).getOrElse(""))))
      case Left(e: S3Exception) if e.statusCode == 404      => Right(None)
      case Left(e)                                          => attempt("head")(throw e)

  def delete(objectName: String): Either[String, Unit] =
    attempt("delete")(client.deleteObject(DeleteObjectRequest.builder().bucket(bucket.name).key(objectName).build())).map(_ => ())

  /**
   * Every version under the prefix, where the store keeps versions (Google Cloud Storage); a store that
   * keeps one and lists none (Garage, which does not implement the versions listing) has each current
   * object deleted instead. Either way nothing under the prefix is left listed.
   */
  def deleteAll(prefix: String): Either[String, Int] =
    versions(prefix) match
      case Right(vs) =>
        attempt("delete versions") {
          vs.foreach((key, version) =>
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket.name).key(key).versionId(version).build()))
          vs.size
        }.flatMap(n => current(prefix).flatMap(rest => deleteKeys(rest).map(_ + n)))
      case Left(_) => current(prefix).flatMap(deleteKeys)

  private def deleteKeys(keys: Vector[String]): Either[String, Int] =
    attempt("delete objects") {
      keys.foreach(k => client.deleteObject(DeleteObjectRequest.builder().bucket(bucket.name).key(k).build()))
      keys.size
    }

  private def versions(prefix: String): Either[String, Vector[(String, String)]] =
    attempt("list versions") {
      client.listObjectVersionsPaginator(ListObjectVersionsRequest.builder().bucket(bucket.name).prefix(prefix).build())
        .asScala.toVector.flatMap { page =>
          page.versions.asScala.map(v => v.key -> v.versionId) ++ page.deleteMarkers.asScala.map(m => m.key -> m.versionId)
        }.filter(_._2 != null)
    }

  private def current(prefix: String): Either[String, Vector[String]] =
    attempt("list objects") {
      client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket.name).prefix(prefix).build())
        .asScala.toVector.flatMap(_.contents.asScala.map(_.key))
    }

/** Google lists an object's versions as `<Generation>`; S3 clients read `<VersionId>` (platform/object-storage.md). */
private object GenerationAsVersionId extends ExecutionInterceptor:
  override def modifyHttpResponseContent(context: Context.ModifyHttpResponse, attributes: ExecutionAttributes): Optional[InputStream] =
    val listsVersions = context.httpRequest().rawQueryParameters().containsKey("versions")
    if !listsVersions || !context.responseBody().isPresent then context.responseBody()
    else
      val xml = new String(context.responseBody().get.readAllBytes(), UTF_8)
        .replace("<Generation>", "<VersionId>")
        .replace("</Generation>", "</VersionId>")
      Optional.of(new ByteArrayInputStream(xml.getBytes(UTF_8)))
