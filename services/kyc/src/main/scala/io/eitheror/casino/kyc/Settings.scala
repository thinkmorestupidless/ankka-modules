package io.eitheror.casino.kyc

import java.time.Duration

/**
 * Where the service's bucket is and how to sign for it, as the platform gives it (`ANKKA_S3_*`,
 * platform/object-storage.md). The public endpoint is what a signed link names; the other is what the
 * service reads and writes through. The keys are the bucket's credential: never logged, never printed.
 */
final case class Bucket(endpoint: String, publicEndpoint: String, region: String, name: String, accessKey: String, secretKey: String):
  override def toString: String = s"Bucket($endpoint, $publicEndpoint, $region, $name)"

/**
 * What the service is told at start (research R18): the brand, the largest upload the brand takes and the
 * bucket. The content types and the links' lifetimes are fixed (FR-005, FR-008). The only secret is the
 * bucket's credential, which the platform writes.
 */
final case class Settings(
    brand: String = "goldvibe",
    maxUploadBytes: Long = Settings.DefaultMaxUploadBytes,
    bucket: Option[Bucket] = None,
    uploadLink: Duration = Duration.ofHours(1),
    viewLink: Duration = Duration.ofMinutes(15)
)

object Settings:
  val DefaultMaxUploadBytes: Long = 5242880L

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val d = Settings()
    for
      brand  <- env.get("CASINO_BRAND").map(_.trim).filter(_.nonEmpty)
                  .toRight("CASINO_BRAND: not set; a brand service is deployed by the modules tool")
      max    <- env.get("KYC_MAX_UPLOAD_BYTES").map(_.trim).filter(_.nonEmpty)
                  .fold(Right(d.maxUploadBytes))(v => v.toLongOption.filter(_ > 0).toRight(s"KYC_MAX_UPLOAD_BYTES: '$v' is not a positive whole number"))
      bucket <- bucketOf(env)
    yield Settings(brand, max, bucket)

  /**
   * The bucket, when the platform gave one: all six variables, or none (a local run without a store,
   * where every upload and view answers `storage_unavailable`). Some but not all is refused.
   */
  def bucketOf(env: Map[String, String]): Either[String, Option[Bucket]] =
    val names  = Vector("ANKKA_S3_ENDPOINT", "ANKKA_S3_PUBLIC_ENDPOINT", "ANKKA_S3_REGION", "ANKKA_S3_BUCKET", "ANKKA_S3_ACCESS_KEY", "ANKKA_S3_SECRET_KEY")
    val values = names.map(n => n -> env.get(n).map(_.trim).filter(_.nonEmpty))
    values.collect { case (n, None) => n } match
      case missing if missing.size == names.size => Right(None)
      case Vector() =>
        val v = values.map(_._2.get)
        Right(Some(Bucket(v(0), v(1), v(2), v(3), v(4), v(5))))
      case missing => Left(s"${missing.mkString(", ")}: not set, while the other ANKKA_S3_ variables are")
