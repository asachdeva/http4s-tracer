/*
 * Copyright 2018 com.github.gvolpe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.gvolpe.tracer

import cats.data.Kleisli
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.syntax.all._
import com.gilt.timeuuid.TimeUuid
import org.http4s.syntax.StringSyntax
import org.http4s.{Header, HttpApp, Request}

/**
  * `org.http4s.server.HttpMiddleware` that either tries to get a Trace-Id from the headers or otherwise
  * creates one with a unique Time-based UUID value, adds it to the headers and logs the http request and
  * http response with it.
  *
  * Quite useful to trace the flow of each request. For example:
  *
  * [TraceId] - [72b079c8-fc92-4c4f-aa5a-c0cd91ea221c] - Request(method=GET, uri=/users, ...)
  * [TraceId] - [72b079c8-fc92-4c4f-aa5a-c0cd91ea221c] - UserAlgebra requesting users
  * [TraceId] - [72b079c8-fc92-4c4f-aa5a-c0cd91ea221c] - UserRepository fetching users from DB
  * [TraceId] - [72b079c8-fc92-4c4f-aa5a-c0cd91ea221c] - MetricsService saving users metrics
  * [TraceId] - [72b079c8-fc92-4c4f-aa5a-c0cd91ea221c] - Response(status=200, ...)
  *
  * In a normal application, you will have thousands of requests and tracing the call chain in
  * a failure scenario will be invaluable.
  * */
object Tracer extends StringSyntax {

  private[tracer] val DefaultTraceIdHeader = "Trace-Id"

  final case class TraceId(value: String) extends AnyVal {
    override def toString = s"[Trace-Id] - [$value]"
  }

  def apply[F[_]](implicit ev: Tracer[F]): Tracer[F] = ev

  def create[F[_]: Sync](headerName: String = DefaultTraceIdHeader): F[Tracer[F]] =
    Ref.of[F, String](headerName).map(ref => new Tracer[F](ref))

}

class Tracer[F[_]: Sync] private (ref: Ref[F, String]) {

  import Trace._, Tracer._

  // format: off
  def middleware(http: HttpApp[F])(implicit L: TracerLog[Trace[F, ?]]): HttpApp[F] =
    Kleisli { req =>
      val createId: F[(Request[F], TraceId)] =
        for {
          id <- Sync[F].delay(TraceId(TimeUuid().toString))
          tr <- ref.get.map(h => req.putHeaders(Header(h, id.value)))
        } yield (tr, id)

      for {
        mi       <- getTraceId(req)
        (tr, id) <- mi.fold(createId){ id => (req, id).pure[F] }
        _        <- L.info[Tracer[F]](s"$req").run(id)
        header   <- ref.get
        rs       <- http(tr).map(_.putHeaders(Header(header, id.value)))
        _        <- L.info[Tracer[F]](s"$rs").run(id)
      } yield rs
    }

  def getTraceId(request: Request[F]): F[Option[TraceId]] =
    ref.get.map(hn => request.headers.get(hn.ci).map(h => TraceId(h.value)))

}