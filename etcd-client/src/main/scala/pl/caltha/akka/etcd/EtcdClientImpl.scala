package pl.caltha.akka.etcd

import java.net.URLEncoder

import scala.collection.immutable.Traversable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.http.ClientConnectionSettings
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri._
import akka.io.Inet.SocketOption
import akka.stream.ActorFlowMaterializer
import akka.stream.FlowMaterializer
import akka.stream.scaladsl._
import akka.util.ByteString

import spray.json._

import pl.caltha.akka.http.HttpRedirects

/**
 * `etcd` client implementation.
 */
private[etcd] class EtcdClientImpl(host: String, port: Int = 4001,
    socketOptions: Traversable[SocketOption] = Nil,
    httpClientSettings: Option[ClientConnectionSettings] = None)(implicit system: ActorSystem) extends EtcdClient {

  def bool(name: String, value: Boolean): Option[(String, String)] =
    if (value) Some(name -> value.toString)
    else None

  def get(key: String, recursive: Boolean, sorted: Boolean): Future[EtcdResponse] =
    run(GET, key, bool("recursive", recursive), bool("sorted", sorted))

  def wait(key: String, waitIndex: Option[Int] = None, recursive: Boolean,
    sorted: Boolean, quorum: Boolean): Future[EtcdResponse] =
    run(GET, key, Some("wait" -> "true"), waitIndex.map("waitIndex" -> _.toString),
      bool("recursive", recursive), bool("sorted", sorted), bool("quorum", quorum))

  def set(key: String, value: String, ttl: Option[Int] = None): Future[EtcdResponse] =
    run(PUT, key, Some("value" -> value), ttl.map("ttl" -> _.toString))

  def compareAndSet(key: String, value: String, ttl: Option[Int] = None, prevValue: Option[String] = None,
    prevIndex: Option[Int] = None, prevExist: Option[Boolean] = None): Future[EtcdResponse] =
    run(PUT, key, Some("value" -> value), ttl.map("ttl" -> _.toString), prevValue.map("prevValue" -> _),
      prevIndex.map("prevIndex" -> _.toString), prevExist.map("prevExist" -> _.toString))
      
  def clearTTL(key: String): Future[EtcdResponse] =
    run(PUT, key, Some("ttl" -> ""), Some("prevExists" -> "true"))

  def create(parentKey: String, value: String): Future[EtcdResponse] =
    run(POST, parentKey, Some("value" -> value))

  def createDir(key: String, ttl: Option[Int] = None): Future[EtcdResponse] =
    run(PUT, key, Some("dir" -> "true"), ttl.map("ttl" -> _.toString))

  def delete(key: String, recursive: Boolean = false): Future[EtcdResponse] =
    run(DELETE, key, bool("recursive", recursive))

  def compareAndDelete(key: String, prevValue: Option[String] = None, prevIndex: Option[Int] = None): Future[EtcdResponse] =
    run(DELETE, key, prevValue.map("prevValue" -> _), prevIndex.map("prevIndex" -> _.toString))

  def watch(key: String, waitIndex: Option[Int] = None, recursive: Boolean,
    quorum: Boolean): Source[EtcdResponse, Unit] = {
    case class WatchRequest(key: String, waitIndex: Option[Int], recursive: Boolean, quorum: Boolean)
    val init = WatchRequest(key, waitIndex, recursive, quorum)
    Source[EtcdResponse]() { implicit b =>
      import FlowGraph.Implicits._

      val initReq = b.add(Source.single(init))
      val reqMerge = b.add(Merge[WatchRequest](2))
      val runWait = b.add(Flow[WatchRequest].mapAsync(1, req => {
        this.wait(req.key, req.waitIndex, req.recursive, req.quorum).map { resp =>
          (req.copy(waitIndex = Some(resp.node.modifiedIndex + 1)), resp)
        }
      }))
      val respUnzip = b.add(Unzip[WatchRequest, EtcdResponse]())

      initReq ~> reqMerge.in(0)
      reqMerge ~> runWait
      runWait ~> respUnzip.in
      respUnzip.out0 ~> reqMerge.in(1)
      respUnzip.out1
    }
  }

  // ---------------------------------------------------------------------------------------------  

  private implicit val executionContext = system.dispatcher

  private implicit val flowMaterializer: FlowMaterializer = ActorFlowMaterializer()

  private val client =
    Http(system).outgoingConnection(host, port, options = socketOptions, settings = httpClientSettings.getOrElse(ClientConnectionSettings(system)))

  private val redirectHandlingClient = HttpRedirects(client, 3)

  private val decode = Flow[HttpResponse].mapAsync(1, response => {
    response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).
      map(_.utf8String).map { body =>
        import EtcdJsonProtocol._
        if (response.status.isSuccess) body.parseJson.convertTo[EtcdResponse]
        else throw EtcdException(body.parseJson.convertTo[EtcdError])
      }
  })

  private def run(req: HttpRequest): Future[EtcdResponse] =
    Source.single(req).via(redirectHandlingClient).via(decode).runWith(Sink.head)

  private def mkParams(params: Seq[Option[(String, String)]]) =
    params.collect { case Some((k, v)) => (k, v) }

  private def mkQuery(params: Seq[Option[(String, String)]]) = {
    Query(mkParams(params).toMap)
  }

  private def enc(s: String) = URLEncoder.encode(s, "UTF-8")

  private def mkEntity(params: Seq[Option[(String, String)]]) = {
    val present = mkParams(params).map { case (k, v) => s"${enc(k)}=${enc(v)}" }
    HttpEntity(ContentType(`application/x-www-form-urlencoded`), present.mkString("&"))
  }

  private val apiV2 = Path / "v2" / "keys"

  private def run(method: HttpMethod, key: String, params: Option[(String, String)]*): Future[EtcdResponse] =
    run(if (method == GET || method == DELETE) {
      HttpRequest(method, Uri(path = apiV2 / key, query = mkQuery(params.toSeq)))
    } else {
      HttpRequest(method, Uri(path = apiV2 / key), entity = mkEntity(params.toSeq))
    })
}