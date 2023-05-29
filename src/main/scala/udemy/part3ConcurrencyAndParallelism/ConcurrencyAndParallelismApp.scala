package udemy.part3ConcurrencyAndParallelism

import cats.*
import cats.Parallel.parTraverse
import cats.effect.*
import cats.implicits.*
import cats.effect.implicits.*

import scala.concurrent.duration.*
object ConcurrencyAndParallelismApp extends IOApp {

  // parMapN
  case class Image(bytes: List[Byte])

  def httpImages(n: Int): IO[List[Image]] = IO.sleep(100.millis) *> (1 to n).toList.map(i => Image(List(i.toByte))).pure[IO]

  def dbImages(n: Int): IO[List[Image]] = IO.sleep(100.millis) *> (1 to n).toList.map(i => Image(List((10 + i).toByte))).pure[IO]


  def parMapExample(n: Int): IO[ExitCode] = (httpImages(n), dbImages(n)).parMapN { case (httpImages, dbImages) =>
    httpImages ++ dbImages
  }.flatTap(IO.println).as(ExitCode.Success)

  // parTraverse
  case class Person(name: String)

  def save(person: Person): IO[Long] = IO.sleep(100.millis ) *> person.name.length.toLong.pure[IO]

  val people = List(Person("leandro"), Person("martin"), Person("max"))

  // race



  override def run(args: List[String]): IO[ExitCode] =  {
    val n = 50
    parMapExample(n)

    (people.parTraverse(save)).flatTap(IO.println).as(ExitCode.Success)


    IO.race(httpImages(n), dbImages(n)).map {
      case Left(httpImages) => s"Http won: $httpImages"
      case Right(dbImages) => s"Db won: $dbImages"
    } .flatTap(IO.println).as(ExitCode.Success)
  }
}
