package part2effects
import cats.effect.{ExitCode, IO, IOApp}

import scala.io.StdIn

object IOApps {
  val program = for {
    line <- IO(StdIn.readLine())
    _ <- IO(println(s"You've just written $line"))
  } yield ()
}


object TestApp {

  val program = for {
    line <- IO(StdIn.readLine())
    _ <- IO (println(s"You've just written $line"))
  } yield()

  def main(args: Array[String]): Unit = {
    import cats.effect.unsafe.implicits.global

    program.unsafeRunSync()
  }
}

object FirstCEApps extends IOApp {
  import IOApps._
  override def run(args: List[String]): IO[ExitCode] =
    program.map(_ => ExitCode.Success)
}

object MySimpleApp extends IOApp.Simple {
  import IOApps._
  override def run: IO[Unit] =
    program.void
}