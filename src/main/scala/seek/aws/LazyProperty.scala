package seek.aws

import cats.effect.IO
import groovy.lang.{Closure, GString}
import org.gradle.api._
import seek.aws.HasLazyProperties.lazyProperty
import seek.aws.config.Lookup

import scala.reflect.ClassTag

class LazyProperty[A](name: String, default: Option[A] = None)(project: Project) {

  private var thing: Option[Any] = None

  def run(implicit tag: ClassTag[A]): IO[A] =
    thing match {
      case Some(v) => render(v)
      case None    =>
        default match {
          case Some(v) => IO.pure(v)
          case None    => IO.raiseError(new GradleException(s"Property ${name} has not been set"))
        }
    }

  def runOptional(implicit tag: ClassTag[A]): IO[Option[A]] =
    if (isSet) run.map(Some(_))
    else IO.pure(None)

  def set(x: Any): Unit =
    thing = Option(x)

  def isSet: Boolean =
    thing.isDefined

  def or(that: LazyProperty[A]): LazyProperty[A] =
    if (isSet) this else that

  private def render(v: Any)(implicit tag: ClassTag[A]): IO[A] =
    v match {
      case l: Lookup     => l.run(project).flatMap(render)
      case c: Closure[_] => IO(c.call()).flatMap(render)
      case g: GString    => render(g.toString)
      case a: A          => IO.pure(a)
      case null          => raiseError(s"Unexpected null value for property ${name}")
    }
}

object LazyProperty {

  def render[A](a: Any, name: String)(implicit p: Project, tag: ClassTag[A]): IO[A] = {
    val lp = lazyProperty[A](name)
    lp.set(a)
    lp.run
  }

  def render[A](a: Any, name: String, default: A)(implicit p: Project, tag: ClassTag[A]): IO[A] = {
    val lp = lazyProperty[A](name)
    lp.set(a)
    lp.runOptional.map(_.getOrElse(default))
  }

  def renderAll[A](s: List[Any])(implicit p: Project): IO[List[A]] =
    s.foldRight(IO.pure(List.empty[A])) { (a, z) =>
      for {
        h <- render(a, "")
        t <- z
      } yield h :: t
    }

  def renderValues[K, A](m: Map[K, Any])(implicit p: Project): IO[Map[K, A]] =
    renderAll[A](m.values.toList).map(rs => m.keys.zip(rs).toMap)
}

trait HasLazyProperties {

  def lazyProperty[A](name: String)(implicit p: Project): LazyProperty[A] =
    new LazyProperty[A](name)(p)

  def lazyProperty[A](name: String, default: A)(implicit p: Project): LazyProperty[A] =
    new LazyProperty[A](name, Some(default))(p)
}

object HasLazyProperties extends HasLazyProperties
