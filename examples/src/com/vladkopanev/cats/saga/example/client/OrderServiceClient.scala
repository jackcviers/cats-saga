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

import java.util.UUID
import cats.effect.kernel.Async
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait OrderServiceClient[F[_]] {

  def closeOrder(userId: UUID, orderId: BigInt, traceId: String): F[Unit]

  def reopenOrder(userId: UUID, orderId: BigInt, traceId: String): F[Unit]
}

class OrderServiceClientStub[F[_]: Async](logger: Logger[F],
                                          maxRequestTimeout: Int,
                                          flaky: Boolean) extends OrderServiceClient[F] {
  import FUtil.*

  override def closeOrder(userId: UUID, orderId: BigInt, traceId: String): F[Unit] =
    for {
      _ <- randomSleep(maxRequestTimeout)
      _ <- randomFail("closeOrder").whenA(flaky)
      _ <- logger.info(s"Order #$orderId closed")
    } yield ()

  override def reopenOrder(userId: UUID, orderId: BigInt, traceId: String): F[Unit] =
    for {
      _ <- randomSleep(maxRequestTimeout)
      _ <- randomFail("reopenOrder").whenA(flaky)
      _ <- logger.info(s"Order #$orderId reopened")
    } yield ()
}

object OrderServiceClientStub {

  def apply[F[_]: Async](maxRequestTimeout: Int, flaky: Boolean): F[OrderServiceClientStub[F]] =
    Slf4jLogger.create[F].map(new OrderServiceClientStub(_, maxRequestTimeout, flaky))
}
