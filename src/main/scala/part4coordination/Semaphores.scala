package part4coordination

import cats.effect.{IO, IOApp}

import scala.concurrent.duration.*
import cats.effect.std.Semaphore
import utils.myDebug

import scala.util.Random
import cats.syntax.parallel.*
object Semaphores extends IOApp.Simple {

  val semaphore:IO[Semaphore[IO]] = Semaphore[IO](2) // 2 total permits

  // example limiting the number of concurrent sessions on a server
  def doWorkWhileLoggedIn(): IO[Int] = IO.sleep(1.second) >> IO(Random.nextInt(100))

  def login(id: Int, sem: Semaphore[IO]): IO[Int] = for {
    _ <- IO(s"[session $id] waiting to log in...").myDebug
    _ <- sem.acquire
    // critical section
    _ <- IO(s"[session $id] logged in, working....").myDebug
    res <- doWorkWhileLoggedIn()
    _ <- IO(s"[session $id] done $res logged out  ...").myDebug
    // end critical section
    _ <- sem.release
  } yield res

  def demoSemaphore() = for {
    sem <- Semaphore[IO](2)
    user1Fib <- login(1, sem).start
    user2Fib <- login(2, sem).start
    user3Fib <- login(3, sem).start
    _ <- user1Fib.join
    _ <- user2Fib.join
    _ <- user3Fib.join
  } yield ()

  def weightedLogin(id: Int, requirePermits:Int, sem: Semaphore[IO]): IO[Int] = for {
    _ <- IO(s"[session $id] waiting to log in...").myDebug
    _ <- sem.acquireN(requirePermits)
    // critical section
    _ <- IO(s"[session $id] logged in, working....").myDebug
    res <- doWorkWhileLoggedIn()
    _ <- IO(s"[session $id] done $res logged out  ...").myDebug
    // end critical section
    _ <- sem.releaseN(requirePermits)
  } yield res


  def demoWeightedSemaphore() = for {
    sem <- Semaphore[IO](2)
    user1Fib <- weightedLogin(1,1, sem).start
    user2Fib <- weightedLogin(2,2, sem).start
    user3Fib <- weightedLogin(3,3, sem).start
    _ <- user1Fib.join
    _ <- user2Fib.join
    _ <- user3Fib.join
  } yield ()

  /**
   * Exercise:
   * 1. find out if there's something wrong with this code
   * 2. why
   * 3. fix it
   */
  // Semaphore with 1 permit == mutex
  val mutex = Semaphore[IO](1)
  val users: IO[List[Int]] = (1 to 10).toList.parTraverse { id =>
    for {
      sem <- mutex
      _ <- IO(s"[session $id] waiting to log in...").myDebug
      _ <- sem.acquire
      // critical section
      _ <- IO(s"[session $id] logged in, working...").myDebug
      res <- doWorkWhileLoggedIn()
      _ <- IO(s"[session $id] done: $res, logging out...").myDebug
      // end of critical section
      _ <- sem.release
    } yield res
  }
  // 2
  // mistake: we flatMap Semaphore[IO](1) so we create a new semaphore every time

  val usersFixed: IO[List[Int]] =
    (1 to 10).toList.parTraverse { id =>
      mutex.flatMap { sem =>
        for {
          _ <- IO(s"[session $id] waiting to log in...").myDebug
          _ <- sem.acquire
          // critical section
          _ <- IO(s"[session $id] logged in, working...").myDebug
          res <- doWorkWhileLoggedIn()
          _ <- IO(s"[session $id] done: $res, logging out...").myDebug
          // end of critical section
          _ <- sem.release
        } yield res
      }
    }


  //override def run: IO[Unit] = demoSemaphore().void
  //override def run: IO[Unit] = demoWeightedSemaphore().void
  //override def run: IO[Unit] = users.myDebug.void
  override def run: IO[Unit] = usersFixed.myDebug.void

}
