/* Copyright 2019 Kopaniev Vladyslav
 *
 * Copyright 2024 Jack C. Viers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.vladkopanev.cats.saga.example.client

import cats.effect.Sync
import cats.effect.kernel.{Async, Temporal}
import cats.syntax.all.*

import scala.concurrent.duration.*
import scala.util.Random

object FUtil {
  def randomSleep[F[_]: Async](maxTimeout: Int): F[Unit] = {
    for {
      randomSeconds <- Sync[F].delay(Random.nextInt(maxTimeout))
      _             <- Temporal[F].sleep(randomSeconds.seconds)
    } yield ()
  }

  def randomFail[F[_]: Async](operationName: String): F[Unit] =
    for {
      randomInt <- Sync[F].delay(Random.nextInt(100))
      _         <- if (randomInt % 10 == 0) Sync[F].raiseError[Unit](new RuntimeException(s"Failed to execute $operationName"))
      else Sync[F].unit
    } yield ()

}
