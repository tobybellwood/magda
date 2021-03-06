package au.csiro.data61.magda.external.registry

import java.time.format.DateTimeParseException
import java.time.{OffsetDateTime, ZoneOffset}

import au.csiro.data61.magda.external.InterfaceConfig
import au.csiro.data61.magda.model.misc._
import au.csiro.data61.magda.model.temporal.{ApiDate, PeriodOfTime, Periodicity}
import spray.json.{DefaultJsonProtocol, JsArray, JsObject}
import spray.json.lenses.JsonLenses._
import spray.json.DefaultJsonProtocol._

import scala.util.Try

case class RegistryRecordsResponse(
  totalCount: Long,
  nextPageToken: Option[String],
  records: List[RegistryRecord])

case class RegistryRecord(
  id: String,
  name: String,
  aspects: Map[String, JsObject]
)

trait RegistryProtocols extends DefaultJsonProtocol {
  implicit val registryRecordFormat = jsonFormat3(RegistryRecord.apply)
  implicit val registryRecordsResponseFormat = jsonFormat3(RegistryRecordsResponse.apply)
}

trait RegistryConverters extends RegistryProtocols {
  implicit def registryDataSetConv(interface: InterfaceConfig)(hit: RegistryRecord): DataSet = {
    val dcatStrings = hit.aspects("dcat-dataset-strings")
    val temporalCoverage = hit.aspects.getOrElse("temporal-coverage", JsObject())
    val distributions = hit.aspects.getOrElse("dataset-distributions", JsObject("distributions" -> JsArray()))

    val coverageStart = ApiDate(tryParseDate(temporalCoverage.extract[String]('intervals.? / element(0) / 'start.?)), dcatStrings.extract[String]('temporal.? / 'start.?).getOrElse(""))
    val coverageEnd = ApiDate(tryParseDate(temporalCoverage.extract[String]('intervals.? / element(0) / 'end.?)), dcatStrings.extract[String]('temporal.? / 'end.?).getOrElse(""))
    val temporal = (coverageStart, coverageEnd) match {
      case (ApiDate(None, ""), ApiDate(None, "")) => None
      case (ApiDate(None, ""), end) => Some(PeriodOfTime(None, Some(end)))
      case (start, ApiDate(None, "")) => Some(PeriodOfTime(Some(start), None))
      case (start, end) => Some(PeriodOfTime(Some(start), Some(end)))
    }

    DataSet(
      identifier = hit.id,
      title = dcatStrings.extract[String]('title.?),
      catalog = interface.name,
      description = dcatStrings.extract[String]('description.?),
      issued = tryParseDate(dcatStrings.extract[String]('issued.?)),
      modified = tryParseDate(dcatStrings.extract[String]('modified.?)),
      language = dcatStrings.extract[String]('languages.? / find(_ => true)),
      publisher = Some(Agent(dcatStrings.extract[String]('publisher.?))),
      accrualPeriodicity = dcatStrings.extract[String]('accrualPeriodicity.?).map(Periodicity.fromString(_)),
      spatial = dcatStrings.extract[String]('spatial.?).map(Location(_)), // TODO: move this to the CKAN Connector
      temporal = temporal,
      theme = dcatStrings.extract[String]('themes.? / *),
      keyword = dcatStrings.extract[String]('keywords.? / *),
      contactPoint = dcatStrings.extract[String]('contactPoint.?).map(cp => Agent(Some(cp))),
      distributions = distributions.extract[JsObject]('distributions.? / *).map(convertDistribution(_, hit)),
      landingPage = dcatStrings.extract[String]('landingPage.?)
    )
  }

  private def convertDistribution(distribution: JsObject, hit: RegistryRecord): Distribution = {
    val distributionRecord = distribution.convertTo[RegistryRecord]
    val dcatStrings = distributionRecord.aspects.getOrElse("dcat-distribution-strings", JsObject())

    val mediaTypeString = dcatStrings.extract[String]('mediaType.?)
    val formatString = dcatStrings.extract[String]('format.?)
    val urlString = dcatStrings.extract[String]('downloadURL.?)
    val descriptionString = dcatStrings.extract[String]('description.?)

    // Get the mediatype first because we'll need it to determine the format if none is provided.
    val mediaType = Distribution.parseMediaType(mediaTypeString, formatString, urlString)
    val format = Distribution.parseFormat(formatString, urlString, mediaType, descriptionString)

    Distribution(
      title = dcatStrings.extract[String]('title.?).getOrElse(distributionRecord.name),
      description = descriptionString,
      issued = tryParseDate(dcatStrings.extract[String]('issued.?)),
      modified = tryParseDate(dcatStrings.extract[String]('modified.?)),
      license = dcatStrings.extract[String]('license.?).map(name => License(Some(name))),
      rights = dcatStrings.extract[String]('rights.?),
      accessURL = dcatStrings.extract[String]('accessURL.?),
      downloadURL = urlString,
      byteSize = dcatStrings.extract[String]('byteSize.?).flatMap(bs => Try(bs.toInt).toOption),
      mediaType = mediaType,
      format = format
    )
  }

  private def tryParseDate(dateString: Option[String]): Option[OffsetDateTime] = {
    dateString.flatMap(s => Try(OffsetDateTime.parse(s)).toOption)
  }
}
