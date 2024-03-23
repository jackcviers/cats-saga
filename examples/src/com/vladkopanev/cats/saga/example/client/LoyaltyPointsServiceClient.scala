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
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import cats.syntax.all.*

trait LoyaltyPointsServiceClient[F[_]] {

  def assignLoyaltyPoints(userId: UUID, amount: Double, traceId: String): F[Unit]

  def cancelLoyaltyPoints(userId: UUID, amount: Double, traceId: String): F[Unit]
}

class LoyaltyPointsServiceClientStub[F[_]: Async](logger: Logger[F], maxRequestTimeout: Int, flaky: Boolean)
    extends LoyaltyPointsServiceClient[F] {
  import FUtil.*

  override def assignLoyaltyPoints(userId: UUID, amount: Double, traceId: String): F[Unit] =
    for {
      _ <- randomSleep(maxRequestTimeout)
      _ <- randomFail("assignLoyaltyPoints").whenA(flaky)
      _ <- logger.info(s"Loyalty points assigned to user $userId")
    } yield ()

  override def cancelLoyaltyPoints(userId: UUID, amount: Double, traceId: String): F[Unit] =
    for {
      _ <- randomSleep(maxRequestTimeout)
      _ <- randomFail("cancelLoyaltyPoints").whenA(flaky)
      _ <- logger.info(s"Loyalty points canceled for user $userId")
    } yield ()

}

object LoyaltyPointsServiceClientStub {

  def apply[F[_]: Async](maxRequestTimeout: Int, flaky: Boolean): F[LoyaltyPointsServiceClientStub[F]] =
    Slf4jLogger.create[F].map(new LoyaltyPointsServiceClientStub(_, maxRequestTimeout, flaky))
}
