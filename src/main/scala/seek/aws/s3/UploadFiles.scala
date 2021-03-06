package seek.aws
package s3

import java.io.File

import cats.data.Kleisli
import cats.data.Kleisli._
import cats.effect.IO
import com.amazonaws.services.s3.model.AccessControlList
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import org.gradle.api.file.FileCollection
import seek.aws.LazyProperty.renderValuesOptional

import scala.collection.JavaConverters._
import scala.collection.mutable

class UploadFiles extends Upload {
  import S3._

  setDescription("Uploads multiple files to S3")

  private val files = lazyProperty[FileCollection]("files")
  def files(v: Any): Unit = files.set(v)

  private val acl = lazyProperty[AccessControlList]("acl")
  def acl(v: Any): Unit = acl.set(v)

  private val bucket = lazyProperty[String]("bucket")
  def bucket(v: Any): Unit = bucket.set(v)

  private val prefix = lazyProperty[String]("prefix", "")
  def prefix(v: Any): Unit = prefix.set(v)

  private val failIfPrefixExists = lazyProperty[Boolean]("failIfPrefixExists", true)
  def failIfPrefixExists(v: Any): Unit = failIfPrefixExists.set(v)

  private val failIfObjectExists = lazyProperty[Boolean]("failIfObjectExists", false)
  def failIfObjectExists(v: Any): Unit = failIfObjectExists.set(v)

  private val cleanPrefixBeforeUpload = lazyProperty[Boolean]("cleanPrefixBeforeUpload", false)
  def cleanPrefixBeforeUpload(v: Any): Unit = cleanPrefixBeforeUpload.set(v)

  private val tags = mutable.HashMap.empty[String, Any]
  def tags(v: java.util.Map[String, Any]): Unit = {
    tags.clear
    tags ++= v.asScala
  }

  override def run: IO[Unit] =
    for {
      fs <- files.run
      b  <- bucket.run
      p  <- prefix.run.map(_.stripSuffix("/"))
      m  <- IO.pure(keyFileMap(fs, p))
      is <- maybeInterpolate(m.values.toList)
      mx <- IO.pure(m.keys.zip(is).toMap)
      al <- acl.runOptional.value
      ts <- renderValuesOptional[String, String](tags.toMap)
      c  <- buildClient(AmazonS3ClientBuilder.standard())
      _  <- maybeFailIfPrefixExists(b, p).run(c)
      _  <- maybeFailIfObjectExists(b, mx.keys.toList).run(c)
      _  <- maybeCleanPrefixBeforeUpload(b, p).run(c)
      _  <- uploadAll(b, mx, al, ts).run(c)
    } yield ()

  private def maybeFailIfPrefixExists(bucket: String, prefix: String): Kleisli[IO, AmazonS3, Unit] =
    maybeRun(failIfPrefixExists, exists(bucket, prefix), raiseError(s"Prefix '$prefix' already exists in bucket $bucket"))

  private def maybeFailIfObjectExists(bucket: String, keys: List[String]): Kleisli[IO, AmazonS3, Unit] =
    maybeRun(failIfObjectExists, existsAny(bucket, keys), raiseError(s"Upload would overwrite one or more files in bucket $bucket"))

  private def maybeCleanPrefixBeforeUpload(bucket: String, prefix: String): Kleisli[IO, AmazonS3, Unit] =
    Kleisli { c =>
      cleanPrefixBeforeUpload.run.flatMap {
        case false => IO.unit
        case true =>
          if (prefix.isEmpty)
            raiseError("No prefix specified to clean (and refusing to delete entire bucket)")
          else
            deleteAll(bucket, prefix).run(c)
      }
    }

  private def uploadAll(bucket: String, keyFileMap: Map[String, File], acl: Option[AccessControlList], tags: Option[Map[String, Any]]): Kleisli[IO, AmazonS3, Unit] =
    keyFileMap.foldLeft(liftF[IO, AmazonS3, Unit](IO.unit)) {
      case (z, (k, f)) => z.flatMap(_ => upload(bucket, k, f, acl, tags))
    }

  private def keyFileMap(files: FileCollection, prefix: String): Map[String, File] = {
    val p = if (prefix.isEmpty) "" else s"${prefix}/"
    fileTreeElements(files).foldLeft(Map.empty[String, File]) { (z, e) =>
      z + (s"${p}${e.getRelativePath}" -> e.getFile)
    }
  }
}
