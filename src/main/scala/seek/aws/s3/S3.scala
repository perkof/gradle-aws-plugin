package seek.aws.s3

import java.io.File

import cats.data.Kleisli
import cats.effect.IO
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.s3.model.Tag
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model._
import fs2.Stream
import fs2.Stream._

import scala.collection.JavaConverters._

sealed trait S3 {

  private val S3PageSize = 1000

  def upload(bucket: String, key: String, file: File, acl: Option[AccessControlList], tags: Option[Map[String, Any]]): Kleisli[IO, AmazonS3, Unit] =
    Kleisli { c =>
      {
        val req = (acl, tags) match {
          case (Some(a), Some(ts)) => new PutObjectRequest(bucket, key, file).withAccessControlList(a).withTagging(s3Tags(ts))
          case (Some(a), None)     => new PutObjectRequest(bucket, key, file).withAccessControlList(a)
          case (None, Some(ts))    => new PutObjectRequest(bucket, key, file).withTagging(s3Tags(ts))
          case (None, None)        => new PutObjectRequest(bucket, key, file)
        }
        IO(c.putObject(req))
      }
    }

  def exists(bucket: String, keyOrPrefix: String): Kleisli[IO, AmazonS3, Boolean] =
    listObjects(bucket, keyOrPrefix).mapF(_.head.compile.toList.map(_.nonEmpty))

  def existsAny(bucket: String, keys: List[String]): Kleisli[IO, AmazonS3, Boolean] =
    Kleisli { c =>
      keys match {
        case h :: t =>
          IO(c.getObjectMetadata(bucket, h)).attempt.flatMap {
            case Left(ex: AmazonServiceException) if ex.getStatusCode == 404 =>
              existsAny(bucket, t).run(c)
            case Left(ex) => IO.raiseError(ex)
            case Right(_) => IO.pure(true)
          }
        case Nil => IO.pure(false)
      }
    }

  def deleteAll(bucket: String, prefix: String): Kleisli[IO, AmazonS3, Unit] =
    Kleisli { c =>
      val keys = listObjects(bucket, prefix).run(c).map(_.getKey)
      keys
        .sliding(S3PageSize)
        .evalMap { q =>
          val req = new DeleteObjectsRequest(bucket)
            .withQuiet(true)
            .withKeys(q: _*)
          IO(c.deleteObjects(req))
        }
        .compile
        .drain
    }

  def listObjects(bucket: String, prefix: String): Kleisli[Stream[IO, ?], AmazonS3, S3ObjectSummary] =
    Kleisli[Stream[IO, ?], AmazonS3, S3ObjectSummary] { c =>
      val pages = unfoldEval[IO, Option[String], Seq[S3ObjectSummary]](None) { startAfter =>
        val req = new ListObjectsV2Request()
          .withBucketName(bucket)
          .withPrefix(prefix)
          .withStartAfter(startAfter.orNull)
        IO(c.listObjectsV2(req)).map { res =>
          val os = res.getObjectSummaries.asScala
          os.lastOption.map(last => (os, Some(last.getKey)))
        }
      }
      pages.flatMap(emits(_))
    }

  private def s3Tags(tags: Map[String, Any]): ObjectTagging =
    new ObjectTagging(tags.toList.map {
      case (k, v) => new Tag(k, v.toString)
    }.asJava)
}

object S3 extends S3
