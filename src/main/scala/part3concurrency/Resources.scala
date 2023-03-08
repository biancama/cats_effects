package part3concurrency

import cats.effect.{IO, IOApp}

import java.io.{File, FileReader}
import java.util.Scanner
import scala.concurrent.duration.*

object Resources extends IOApp.Simple {
  import utils._
  // use-case: manage a connection lifecycle
  class Connection(url: String) {
    def open(): IO[String] = IO(s"Opening connection to $url").myDebug
    def close(): IO[String] = IO(s"Closing connection to $url").myDebug
  }

  val asyncFetchUrl = for {
    fib <- (new Connection("rockthejvm.com").open() *> IO.sleep((Int.MaxValue).seconds)).start // leak connection because open but not closes
    _ <- IO.sleep(1.second) *> fib.cancel
  } yield ()

  val correctAsyncFetchUrl = for {
    conn <- IO(new Connection ("rockthejvm.com"))
    fib <- (conn.open() *> IO.sleep ((Int.MaxValue).seconds)).onCancel(conn.close().void).start // no leak connection because open will be closed
    _ <- IO.sleep (1.second) *> fib.cancel
  } yield ()

  /*
      bracket pattern: someIO.bracket(useResourceCb)(releaseResourceCb)
      bracket is equivalent to try-catches (pure FP)
     */

  val bracketFetchUrl = IO(new Connection ("rockthejvm.com"))
    .bracket(conn => conn.open() *> IO.sleep ((Int.MaxValue).seconds))(conn=> conn.close().void)

  /**
   * Exercise: read the file with the bracket pattern
   *  - open a scanner
   *  - read the file line by line, every 100 millis
   *  - close the scanner
   *  - if cancelled/throws error, close the scanner
   */

  def openFileScanner(path: String): IO[Scanner] =
    IO(new Scanner(new FileReader(new File(path))))
  def readLineByLine(scanner: Scanner): IO[Unit] =
    if (scanner.hasNext) IO(scanner.nextLine()).myDebug >> IO.sleep(100.millis) >> readLineByLine(scanner) else IO.unit

  def bracketReadFile(path: String):IO[Unit] =
    IO(s"Opening file at $path").myDebug >>
    openFileScanner(path)
    .bracket(readLineByLine)(scanner =>  IO(s"Closing file at $path").myDebug >> IO(scanner.close()).myDebug)

  override def run = bracketReadFile("src/main/scala/part3concurrency/Resources.scala")
}
