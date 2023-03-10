package part3concurrency

import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.{IO, IOApp, Resource}

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


  /**
   *
   * Resources
   */

  def connFromConfig(path: String): IO[Unit] =
    openFileScanner(path)
      .bracket {
        scanner =>
        // acquire connection based on File
        IO(new Connection(scanner.nextLine())).bracket { conn =>
          conn.open().myDebug >> IO.never
        } { conn =>
          conn.close().myDebug.void
        }
      } { scanner =>
        IO("closing file").myDebug >> IO(scanner.close())
      }

  // nesting resources are tedious
  val connectionResource = Resource.make(IO(new Connection("rockthejvm.com")))(conn => conn.close().void)  // we have acquisition and release,
  // usage it will be at later stage
  val resourceFetchUrl = for {
    fib <- connectionResource.use(conn => conn.open() >> IO.never).start
    _ <- IO.sleep(1.second) >> fib.cancel
  } yield ()

  // resources are equivalent to bracket
  val simpleResource = IO("some resource")
  val usingResource: String => IO[String] = s => IO(s"using the string $s").myDebug
  val releaseResource: String => IO[Unit] = s => IO(s"finalizing the string $s").myDebug.void

  val usingResourceWithBracket =
    simpleResource.bracket(usingResource)(releaseResource)

  val usingResourceWithResource =
    Resource.make(simpleResource)(releaseResource).use(usingResource)

  /**
   * Exercise: read a text file with one line every 100 millis, using Resource
   * (refactor the bracket exercise to use Resource)
   */

  def getResourceFromFile(path: String) = Resource.make(openFileScanner(path)) { scanner =>
    IO(s"closing file at $path").myDebug >> IO(scanner.close())
  }

  def resourceReadFile(path: String) =
    IO(s"opening file at $path") >>
      getResourceFromFile(path).use { scanner =>
        readLineByLine(scanner)
      }

  def cancelReadFile(path: String) = for {
    fib <- resourceReadFile(path).start
    _ <- IO.sleep(2.seconds) >> fib.cancel
  } yield ()

  // nested resources
  def connFromConfResource(path: String) =
    Resource.make(IO("opening file").myDebug >> openFileScanner(path))(scanner => IO("closing file").myDebug >> IO(scanner.close()))
      .flatMap(scanner => Resource.make(IO(new Connection(scanner.nextLine())))(conn =>conn.close().void))

  // equivalent
  def connFromConfResourceClean(path: String) = for {
    scanner <- Resource.make(IO("opening file").myDebug >> openFileScanner(path))(scanner => IO("closing file").myDebug >> IO(scanner.close()))
    conn <- Resource.make(IO(new Connection(scanner.nextLine())))(conn => conn.close().void)
  } yield conn


  val openConnection = connFromConfResource("src/main/resources/connection.txt").use(conn => conn.open() >> IO.never)
  val canceledConnection = for {
    fib <- openConnection.start
    _ <- IO.sleep(1.second) >> IO("cancelling!").myDebug >> fib.cancel
  } yield ()

  // finalizer to regular Ios
  val ioWithFinalizer = IO("some resource").myDebug.guarantee(IO("freeing resource").myDebug.void)
  val ioWithFinalizer_v2 = IO("some resource").myDebug.guaranteeCase {
    case Succeeded(fa) => fa.flatMap(result => IO(s"releasing resource: $result").myDebug).void
    case Errored(e) => IO("nothing to release").myDebug.void
    case Canceled() => IO("resource got canceled, releasing what's left").myDebug.void
  }
  // connection + file will close automatically
  //override def run = bracketReadFile("src/main/scala/part3concurrency/Resources.scala")
  //override def run = resourceFetchUrl.void
  //override def run = cancelReadFile("src/main/scala/part3concurrency/Resources.scala").void
  //override def run = openConnection.void
  //override def run = canceledConnection.void
  override def run = ioWithFinalizer.void
}
