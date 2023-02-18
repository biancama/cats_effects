package part2effects

import cats.effect.IO

import scala.Console.println
import scala.io.StdIn

object IOIntroduction {

  val ourFirstIO: IO[Int] = IO.pure(42) // arg that should not have side effects

  val aDelayedIO: IO[Int] = IO.delay {
    println("I'm producing an integer")
    54
  }

  val shouldNotDoThis: IO[Int] = IO.pure {
    println("I'm producing an integer")
    53
  }

  val aDelayedIO_v2: IO[Int] = IO { // apply == delay
    println("I'm producing an integer")
    54
  }

  // map, flatMap
  val improvedMeaningOfLife = ourFirstIO.map(_ * 2)
  val printedMeaningOfLife = ourFirstIO.flatMap(mol => IO.delay(println(mol)))

  def smallProgram(): IO[Unit] = for {
    line1 <- IO(StdIn.readLine())
    line2 <- IO(StdIn.readLine())
    _ <- IO.delay(println(line1 + line2))
  } yield ()

  // mapN - combine IO effects as tuples
  import cats.syntax.apply._
  val combinedMeaningOfLife: IO[Int] = (ourFirstIO, improvedMeaningOfLife).mapN(_ + _)
  def smallProgram_v2(): IO[Unit] =
    (IO(StdIn.readLine()), IO(StdIn.readLine())).mapN(_ + _).map(println)


  /**
   * Exercises
   */

  // 1 - sequence two IOs and take the result of the LAST one
  // hint: use flatMap
  def sequenceTakeLast[A, B](ioa: IO[A], iob: IO[B]): IO[B] =
    ioa.flatMap(_ => iob)

  def sequenceTakeLast_v2[A, B](ioa: IO[A], iob: IO[B]): IO[B] =
    ioa *> iob // "andThen"

  def sequenceTakeLast_v3[A, B](ioa: IO[A], iob: IO[B]): IO[B] =
    ioa >> iob // "andThen" with by-name call   evalutation of the second one is lazy



  // 2 - sequence two IOs and take the result of the FIRST one
  // hint: use flatMap
  def sequenceTakeFirst[A, B](ioa: IO[A], iob: IO[B]): IO[A] =
    ioa.flatMap(a => iob.map(_ => a))

  def sequenceTakeFirst_v2[A, B](ioa: IO[A], iob: IO[B]): IO[A] =
    ioa <* iob

  // 3 - repeat an IO effect forever
  // hint use flatMap and recursion
  def forever[A](io: IO[A]): IO[A] =
    io.flatMap(_ => forever(io))

  def forever_v2[A](io: IO[A]): IO[A] =
    io >> forever_v2(io) // same

  def forever_v3[A](io: IO[A]): IO[A] =
    io *> forever_v3(io) // same   but this is stack over flow because it's evaluate eagerly even without call runAsync

  def forever_v4[A](io: IO[A]): IO[A] =
    io.foreverM // with tail recursion

  // 4 - convert an IO to a different type
  // hint: use map
  def convert[A, B](ioa: IO[A], value: B): IO[B] =
    ioa.map(_ => value)

  def convert_v2[A, B](ioa: IO[A], value: B): IO[B] =
    ioa.as(value) // same

  def asUnit_v2[A](ioa: IO[A]): IO[Unit] =
    ioa.as(()) // discouraged - don't use this

  def asUnit_v3[A](ioa: IO[A]): IO[Unit] =
    ioa.void // same - encouraged

  // 5 - discard value inside an IO, just return Unit
  def asUnit[A](ioa: IO[A]): IO[Unit] =
    convert(ioa, ())

  // 6 - fix stack recursion
  def sum(n: Int): Int =
    if (n <= 0) 0
    else n + sum(n - 1)

  def sumIO(n: Int): IO[Int] =
    if (n <= 0) IO(0)
    else for {
      n <- IO(n)
      prev <- sumIO(n - 1)
    } yield n + prev

  // 7 (hard) - write a fibonacci IO that does NOT crash on recursion
  // hints: use recursion, ignore exponential complexity, use flatMap heavily
  def fibonacci(n: Int): IO[BigInt] =
    if (n <= 1) IO(n)
    else for {
      n_1 <- IO.defer(fibonacci(n - 1)) // same as .delay(...).flatten
      n_2 <- IO.defer(fibonacci(n - 2))
    } yield n_1 + n_2

  def main(args: Array[String]): Unit = {
    import cats.effect.unsafe.implicits.global // "platform"
    // "end of the world"
    println(aDelayedIO.unsafeRunSync())

    val anIo: IO[Int] = IO {
      println("forever")
      42
    }
    //println(forever(anIo).unsafeRunSync())

    //println(sum(100000))
    println(sumIO(100000).unsafeRunSync())

    println(fibonacci(10).unsafeRunSync())

    (1 to 100).foreach(i => println(fibonacci(i).unsafeRunSync()))
  }
}
