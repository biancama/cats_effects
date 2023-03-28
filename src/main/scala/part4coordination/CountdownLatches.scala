package part4coordination

import cats.effect.std.CountDownLatch
import cats.effect.{IO, IOApp, Resource}

import scala.concurrent.duration.*
import utils.*
import cats.syntax.parallel.*
import cats.syntax.traverse.*

import java.io.{File, FileWriter}
import scala.io.Source
import scala.util.Random

object CountdownLatches extends IOApp.Simple {
  /*
      CDLatches are a coordination primitive initialized with a count.
      All fibers calling await() on the CDLatch are (semantically) blocked.
      When the internal count of the latch reaches 0 (via release() calls from other fibers), all waiting fibers are unblocked.
     */

  def announcer (latch: CountDownLatch[IO]): IO[Unit] = for {
    _ <- IO("Starting race shortly...").myDebug >> IO.sleep(2.seconds)
    _ <- IO("5...").myDebug >> IO.sleep(1.second)
    _ <- latch.release
    _ <- IO("4...").myDebug >> IO.sleep(1.second)
    _ <- latch.release
    _ <- IO("3...").myDebug >> IO.sleep(1.second)
    _ <- latch.release
    _ <- IO("2...").myDebug >> IO.sleep(1.second)
    _ <- latch.release
    _ <- IO("1...").myDebug >> IO.sleep(1.second)
    _ <- latch.release // gun firing
    _ <- IO("GO GO GO!").myDebug
  } yield ()

  def createRunner(id: Int, latch: CountDownLatch[IO]): IO[Unit] = for {
    _ <- IO(s"[runner $id] waiting for signal...").myDebug
    _ <- latch.await // block this fiber until the count reaches 0
    _ <- IO(s"[runner $id] RUNNING!").myDebug
  } yield ()

  def sprint(): IO[Unit] = for {
    latch <- CountDownLatch[IO](5)
    announcerFib <- announcer(latch).start
    _ <- (1 to 10).toList.parTraverse(id => createRunner(id, latch))
    _ <- announcerFib.join
  } yield ()

  /**
   * Exercise: simulate a file downloader on multiple threads
   */
  object FileServer {
    val fileChunksList = Array(
      "I love Scala.",
      "Cats Effect seems quite fun.",
      "Never would I have thought I would do low-level concurrency WITH pure FP."
    )

    def getNumChunks: IO[Int] = IO(fileChunksList.length)

    def getFileChunk(n: Int): IO[String] = IO(fileChunksList(n))
  }

  def writeToFile(path: String, contents: String): IO[Unit] = {
    val fileResource = Resource.make(IO(new FileWriter(new File(path))))(writer => IO(writer.close()))
    fileResource.use { writer =>
      IO(writer.write(contents))
    }
  }

  def appendFileContents(fromPath: String, toPath: String): IO[Unit] = {
    val compositeResource = for {
      reader <- Resource.make(IO(Source.fromFile(fromPath)))(source => IO(source.close()))
      writer <- Resource.make(IO(new FileWriter(new File(toPath), true)))(writer => IO(writer.close()))
    } yield (reader, writer)

    compositeResource.use {
      case (reader, writer) => IO(reader.getLines().foreach(writer.write))
    }
  }
  /*
      - call file server API and get the number of chunks (n)
      - start a CDLatch
      - start n fibers which download a chunk of the file (use the file server's download chunk API)
      - block on the latch until each task has finished
      - after all chunks are done, stitch the files together under the same file on disk
     */
  // def downloadFile(filename: String, destFolder: String): IO[Unit] = ??? start exercise
  def downloadFile(filename: String, destFolder: String): IO[Unit] = for {
    numberOfChunks <- FileServer.getNumChunks
    latch <- CountDownLatch[IO](numberOfChunks)
    _ <- IO(s"Downloaded started on $numberOfChunks fibers.").myDebug
    _ <- (0 until numberOfChunks).toVector.parTraverse(id => createFileDownloaderTask(id, latch, filename, destFolder))
    _ <- latch.await
    _ <- (0 until numberOfChunks).toVector.traverse(id => appendFileContents(s"$destFolder/$filename.part$id", s"$destFolder/$filename"))
    } yield()


  def createFileDownloaderTask(chunkIndex: Int, latch: CountDownLatch[IO], filename: String, destFolder: String): IO[Unit] = for {
    _ <- IO(s"[task $chunkIndex] downloading chunk...").myDebug
    _ <- IO.sleep((Random.nextDouble * 1000).toInt.millis)
    chunk <- FileServer.getFileChunk(chunkIndex)
    _ <- writeToFile(s"$destFolder/$filename.part$chunkIndex", chunk)
    _ <- IO(s"[task $chunkIndex] chunk download complete").myDebug
    _ <- latch.release
  } yield ()

  //override def run: IO[Unit] = sprint()
  override def run: IO[Unit] = downloadFile("myScalaFile.txt", "src/main/resources")
}
