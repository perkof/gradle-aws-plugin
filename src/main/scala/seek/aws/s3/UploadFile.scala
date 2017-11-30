package seek.aws
package s3

import java.io.File

import cats.data.Kleisli
import cats.effect.IO
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}

class UploadFile extends AwsTask {

  setDescription("Uploads a single file to S3")

  private val bucket = lazyProp[String]("bucket")
  def bucket(v: Any): Unit = bucket.set(v)

  private val file = lazyProp[File]("file")
  def file(v: Any): Unit = file.set(v)

  private val key = lazyProp[String]("key")
  def key(v: Any): Unit = key.set(v)

  private val failIfObjectExists = lazyProp[Boolean]("failIfObjectExists", false)
  def failIfObjectExists(v: Any): Unit = failIfObjectExists.set(v)

  override def run: IO[Unit] =
    for {
      r <- region
      c <- IO.pure(AmazonS3ClientBuilder.standard().withRegion(r).build())
      b <- bucket.run
      f <- file.run
      k <- key.run
      _ <- maybeFailIfObjectExists(b, k).run(c)
      _ <- upload(b, k, f).run(c)
    } yield ()

  private def maybeFailIfObjectExists(bucket: String, key: String): Kleisli[IO, AmazonS3, Unit] =
    maybeRun(failIfObjectExists, exists(bucket, key),
      raiseError(s"Object ${key} already exists in bucket ${bucket}"))
}
