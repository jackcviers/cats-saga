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

package com.vladkopanev.cats.saga.example.endpoint

import cats.effect.Concurrent
import cats.syntax.all.*
import com.vladkopanev.cats.saga.example.OrderSagaCoordinator
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits.*
import org.http4s.{HttpApp, HttpRoutes}
import com.vladkopanev.cats.saga.example.model.OrderInfo
import org.http4s.EntityDecoder

final class SagaEndpoint[F[_]: Concurrent](orderSagaCoordinator: OrderSagaCoordinator[F]) extends Http4sDsl[F] {

  private implicit val decoder:EntityDecoder[F, OrderInfo] = jsonOf[F, OrderInfo]

  val service: HttpApp[F] = HttpRoutes
    .of[F] {
      case req @ POST -> Root / "saga" / "finishOrder" =>
        for {
          OrderInfo(userId, orderId, money, bonuses) <- req.as[OrderInfo]
          resp <- orderSagaCoordinator
                   .runSaga(userId, orderId, money, bonuses, None)
                   .attempt
                   .flatMap {
                     case Left(fail) => InternalServerError(fail.getMessage)
                     case Right(_)   => Ok("Saga submitted")
                   }
        } yield resp
    }
    .orNotFound
}
