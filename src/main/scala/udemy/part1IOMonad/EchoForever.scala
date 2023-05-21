package udemy.part1IOMonad

import cats.effect.{ExitCode, IO, IOApp}

import scala.io.StdIn

object EchoForever extends IOApp {

  private def echoForever: IO[Unit] = {
    val echo = for {
      echo <- IO(StdIn.readLine())
      _ <- IO(println(echo))
    } yield ()
    echo.foreverM
  }

  override def run(args: List[String]): IO[ExitCode] = echoForever.as(ExitCode.Success)
}
