package au.csiro.data61.magda.search.elasticsearch

import java.net.URL

import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, JsonFraming, Source}
import au.csiro.data61.magda.spatial.RegionSource
import spray.json._


object RegionLoader {

  /**
   * Reads the ABS regions in from a gigantic (165mb!!) GeoJSON file that we download as part of the build, and
   * indexes them into ES
   */
  def loadABSRegions(regionSource: RegionSource)(implicit materializer: Materializer): Source[JsObject, Any] = {
    val connectionFlow: Flow[HttpRequest, HttpResponse, Any] =
      Http().outgoingConnection(regionSource.url.getHost, regionSource.url.getPort)
    val splitFlow = JsonFraming.objectScanner(Int.MaxValue)

    val request = RequestBuilding.Get(regionSource.url.toString)

    // Here we use an akka stream to read the file chunk by chunk and pass it down the stream to the parser.
    //    val fileSource = FileIO.fromPath(blah)
    val parseResult = Source.single(request)
      .via(connectionFlow)
      .flatMapConcat(_.entity.dataBytes)
      .via(splitFlow)
      .map(byteString => byteString.decodeString("UTF-8"))
      .map(string => string.parseJson)
      .map(jsValue => jsValue.asJsObject)

    // Create a future that will resolve when every index operation has resolved.
    parseResult
  }
}