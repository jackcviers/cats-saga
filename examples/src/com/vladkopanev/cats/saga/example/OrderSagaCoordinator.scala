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

package com.vladkopanev.cats.saga.example

import cats.effect.Temporal
import cats.effect.kernel.Async
import cats.syntax.all.*
import cats.{// Applicative,
  Parallel}
import com.vladkopanev.cats.saga.SagaTransactor
import com.vladkopanev.cats.saga.example.client.*
import com.vladkopanev.cats.saga.example.dao.SagaLogDao
import com.vladkopanev.cats.saga.example.model.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.{RetryPolicies, Sleep}

import java.util.UUID
import scala.concurrent.duration.*
import scala.annotation.nowarn

trait OrderSagaCoordinator[F[_]] {
  def runSaga(userId: UUID, orderId: BigInt, money: BigDecimal, bonuses: Double, sagaIdOpt: Option[Long]): F[Unit]

  def recoverSagas: F[Unit]
}

class OrderSagaCoordinatorImpl[F[_]](
  paymentServiceClient: PaymentServiceClient[F],
  loyaltyPointsServiceClient: LoyaltyPointsServiceClient[F],
  orderServiceClient: OrderServiceClient[F],
  sagaLogDao: SagaLogDao[F],
  maxRequestTimeout: Int,
  logger: StructuredLogger[F],
)(implicit A: Async[F], sagaInterpreter: SagaTransactor[F], P: Parallel[F], S: Sleep[F]) extends OrderSagaCoordinator[F] {

  import com.vladkopanev.cats.saga.Saga.*

  def runSaga(
    userId: UUID,
    orderId: BigInt,
    money: BigDecimal,
    bonuses: Double,
    sagaIdOpt: Option[Long]
  ): F[Unit] = {

    def mkSagaRequest(
      request: F[Unit],
      sagaId: Long,
      stepName: String,
      executedSteps: List[SagaStep],
      compensating: Boolean = false
    ) = {
      val maybeError = executedSteps
        .find(step => step.name == stepName && !compensating)
        .flatMap(_.failure)
        .map(new OrderSagaError(_))
        .pure[F]

        maybeError.flatMap(_.fold(A.unit)(A.raiseError)) *>
          Temporal[F].timeout(request, maxRequestTimeout.seconds)
          .attempt
          .flatMap {
            case Left(e) => sagaLogDao.createSagaStep(stepName, sagaId, result = None, failure = Some(e.getMessage))
            case _ => sagaLogDao.createSagaStep(stepName, sagaId, result = None)
          }
          .whenA(!executedSteps.exists(_.name == stepName))
    }

    def collectPayments(executed: List[SagaStep], sagaId: Long) = mkSagaRequest(
      paymentServiceClient.collectPayments(userId, money, sagaId.toString),
      sagaId,
      "collectPayments",
      executed
    )

    def assignLoyaltyPoints(executed: List[SagaStep], sagaId: Long) = mkSagaRequest(
      loyaltyPointsServiceClient.assignLoyaltyPoints(userId, bonuses, sagaId.toString),
      sagaId,
      "assignLoyaltyPoints",
      executed
    )

    def closeOrder(executed: List[SagaStep], sagaId: Long) =
      mkSagaRequest(orderServiceClient.closeOrder(userId, orderId, sagaId.toString), sagaId, "closeOrder", executed)

    def refundPayments(executed: List[SagaStep], sagaId: Long) = mkSagaRequest(
      paymentServiceClient.refundPayments(userId, money, sagaId.toString),
      sagaId,
      "refundPayments",
      executed,
      compensating = true
    )

    def cancelLoyaltyPoints(executed: List[SagaStep], sagaId: Long) = mkSagaRequest(
      loyaltyPointsServiceClient.cancelLoyaltyPoints(userId, bonuses, sagaId.toString),
      sagaId,
      "cancelLoyaltyPoints",
      executed,
      compensating = true
    )

    def reopenOrder(executed: List[SagaStep], sagaId: Long) =
      mkSagaRequest(
        orderServiceClient.reopenOrder(userId, orderId, sagaId.toString),
        sagaId,
        "reopenOrder",
        executed,
        compensating = true
      )

    val expSchedule = RetryPolicies.exponentialBackoff[F](1.second)
    def buildSaga(sagaId: Long, executedSteps: List[SagaStep]) =
      for {
        _ <- collectPayments(executedSteps, sagaId).retryableCompensate(refundPayments(executedSteps, sagaId), expSchedule)
        _ <- assignLoyaltyPoints(executedSteps, sagaId).retryableCompensate(cancelLoyaltyPoints(executedSteps, sagaId), expSchedule)
        _ <- closeOrder(executedSteps, sagaId).retryableCompensate(reopenOrder(executedSteps, sagaId), expSchedule)
      } yield ()

    import io.circe.syntax.*

    val mdcLog = wrapMDC(logger, userId, orderId, sagaIdOpt)
    val data   = OrderSagaData(userId, orderId, money, bonuses).asJson

    for {
      _        <- mdcLog.info("Saga execution started")
      sagaId   <- sagaIdOpt.fold(sagaLogDao.startSaga(userId, data))(A.pure)
      executed <- sagaLogDao.listExecutedSteps(sagaId)
      _ <- buildSaga(sagaId, executed).transact.attempt.flatMap {
            case Left(_: OrderSagaError) => sagaLogDao.finishSaga(sagaId)
            case Left(_)                 => A.unit
            case Right(_)                => sagaLogDao.finishSaga(sagaId)
          }
      _ <- mdcLog.info("Saga execution finished")
    } yield ()

  }

  @nowarn
  override def recoverSagas: F[Unit] = {
    import cats.instances.all.*
    for {
      _     <- logger.info("Sagas recovery stared")
      sagas <- sagaLogDao.listUnfinishedSagas
      _     <- logger.info(s"Found unfinished sagas: $sagas")
      _ <- sagas.parTraverse { sagaInfo =>
            A.fromEither(sagaInfo.data.as[OrderSagaData]).flatMap {
              case OrderSagaData(userId, orderId, money, bonuses) =>
                runSaga(userId, orderId, money, bonuses, Some(sagaInfo.id)).recover {
                  case e: OrderSagaError => A.unit
                }
            }
          }
      _ <- logger.info("Sagas recovery finished")
    } yield ()
  }

  private def wrapMDC(logger: StructuredLogger[F], userId: UUID, orderId: BigInt, sagaIdOpt: Option[Long]) =
    StructuredLogger.withContext(logger)(
      Map("userId" -> userId.toString, "orderId" -> orderId.toString, "sagaId" -> sagaIdOpt.toString)
    )
}

object OrderSagaCoordinatorImpl {

  def apply[F[_]: Async: Sleep: Parallel: SagaTransactor](
    paymentServiceClient: PaymentServiceClient[F],
    loyaltyPointsServiceClient: LoyaltyPointsServiceClient[F],
    orderServiceClient: OrderServiceClient[F],
    sagaLogDao: SagaLogDao[F],
    maxRequestTimeout: Int
  ): F[OrderSagaCoordinatorImpl[F]] =Slf4jLogger.create[F].map(
    new OrderSagaCoordinatorImpl(
      paymentServiceClient,
      loyaltyPointsServiceClient,
      orderServiceClient,
      sagaLogDao,
      maxRequestTimeout,
      _
    )
  )
}
